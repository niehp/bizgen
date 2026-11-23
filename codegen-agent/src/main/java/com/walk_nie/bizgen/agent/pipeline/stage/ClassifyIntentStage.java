package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.IntentResult;
import com.walk_nie.bizgen.agent.generator.java.JavaChangeIntent;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.json.Json;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * 変更種別の分類。機能 md に明示指定があればコード側で決定し、曖昧な場合のみモデルに委ねる。
 * 「決定論を優先し、不確実性はモデルへ」という方針を具体的に実装したステージ。
 */
public final class ClassifyIntentStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "06-classify-intent";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.APIS_MATCHED);
        ResolvedFeature resolved = state.resolved();

        String declared = resolved.spec().mode();
        if (!StrUtil.isBlank(declared)) {
            JavaChangeIntent intent = JavaChangeIntent.fromToken(declared);
            if (intent == null) {
                throw new IllegalStateException("機能 md の mode が不正です: " + declared);
            }
            state.setIntent(new IntentResult(intent, "機能 md に明示指定されたためコード側で決定",
                    IntentResult.Source.MD));
            state.advance(JavaGenState.Phase.INTENT_DECIDED);
            return state;
        }

        try {
            VariableTable variables = new VariableTable();
            variables.put("featureBody", resolved.featureBody());
            variables.put("designDocs", resolved.designText());
            String prompt = variables.resolveText(context.repository().prompt("intent-classification"));

            AgentConfig config = context.config();
            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);

            String json = StrUtil.extractJsonObject(response.content());
            if (json.isEmpty()) {
                throw new IllegalStateException("意図分類の JSON が取得できませんでした: "
                        + StrUtil.trimToEmpty(response.content()));
            }
            Map<String, Object> parsed = Json.asObject(Json.parse(json));
            JavaChangeIntent intent = JavaChangeIntent.fromToken(Json.asString(parsed.get("intent")));
            if (intent == null) {
                throw new IllegalStateException("意図分類の結果が不正です: " + json);
            }
            state.setIntent(new IntentResult(intent, Json.asString(parsed.get("reason")),
                    IntentResult.Source.LLM));
            state.advance(JavaGenState.Phase.INTENT_DECIDED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("intent-classification prompt の読み込みに失敗", e);
        }
    }
}
