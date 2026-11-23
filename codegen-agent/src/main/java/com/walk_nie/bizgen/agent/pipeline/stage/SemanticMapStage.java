package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.generator.java.SemanticMap;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.NamingStyle;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;

/** 日本語の機能記述を Java 識別子へ対応付ける（モデル担当）。 */
public final class SemanticMapStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "07-semantic-mapping";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.INTENT_DECIDED);
        ResolvedFeature resolved = state.resolved();
        try {
            VariableTable variables = new VariableTable();
            variables.put("featureBody", resolved.featureBody());
            variables.put("designDocs", resolved.designText());
            String prompt = variables.resolveText(context.repository().prompt("semantic-mapping"));

            AgentConfig config = context.config();
            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);

            String json = StrUtil.extractJsonObject(response.content());
            if (json.isEmpty()) {
                throw new IllegalStateException("意味マッピングの JSON が取得できませんでした");
            }
            SemanticMap map = SemanticMap.fromJson(json);

            // クラス名が欠落した場合のみ、md の変数から決定論的に補完する（推測はしない）
            if (StrUtil.isBlank(map.className())) {
                String candidate = resolved.variable("className", "");
                if (StrUtil.isBlank(candidate)) {
                    candidate = resolved.variable("entityName", "") + "Service";
                }
                if (StrUtil.isBlank(candidate)) {
                    throw new IllegalStateException("クラス名を決定できませんでした。md に className を指定してください。");
                }
                map = new SemanticMap(NamingStyle.UPPER_CAMEL.convert(candidate),
                        map.fields(), map.methods(), map.tables(),
                        map.notes() + "（クラス名は md の変数から補完）");
            }

            state.setSemanticMap(map);
            state.advance(JavaGenState.Phase.MAPPED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("semantic-mapping prompt の読み込みに失敗", e);
        }
    }
}
