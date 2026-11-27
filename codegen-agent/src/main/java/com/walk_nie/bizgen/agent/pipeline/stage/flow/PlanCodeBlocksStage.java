package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.flow.CodePlan;
import com.walk_nie.bizgen.agent.generator.flow.FlowGenState;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.md.JavaFeatureOutline;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.reuse.ReuseResolver;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;

/**
 * ステップ 3: 機能 md の意味を解析し、コードの流れと機能ブロックに分解する（モデル担当）。
 * 分解結果は CodePlan という強类型に落とし、件数・種別・説明をコード側で検証する。
 */
public final class PlanCodeBlocksStage implements Stage<FlowGenState, FlowGenState> {

    @Override
    public String id() {
        return "flow-01-plan-blocks";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public FlowGenState execute(FlowGenState state, PipelineContext context) {
        state.assertPhase(FlowGenState.Phase.BASE_READY);
        JavaGenState java = state.java();
        java.assertPhase(JavaGenState.Phase.SIMILAR_CODE_FOUND);
        ResolvedFeature resolved = java.resolved();
        AgentConfig config = context.config();

        VariableTable variables = new VariableTable();
        JavaFeatureOutline outline = JavaFeatureOutline.parse(resolved.featureBody());
        variables.put("featureOutline", outline.isEmpty()
                ? "（md に Java 契約の明示なし）" : outline.toPromptText());
        variables.put("fixedMethods", outline.methodNamesText());
        variables.put("flowSteps", outline.flowText());
        variables.put("maxBlocks", String.valueOf(config.maxBlocks()));
        variables.put("featureBody", resolved.featureBody());
        variables.put("designDocs", resolved.designText());
        variables.put("termsText", java.termsText());
        variables.put("apisText", java.apisText());
        variables.put("similarCode", java.similarCode().promptText());
        variables.put("specText", java.specText());
        variables.put("semanticMap", java.semanticMap().toJson());
        variables.put("className", java.semanticMap().className());
        try {
            variables.put("reusableHints", ReuseResolver.hintsText(
                    new ReuseResolver(config).catalog()));
        } catch (IOException e) {
            throw new IllegalStateException("再利用テンプレートの読み込みに失敗: "
                    + config.blockRoot(), e);
        }

        try {
            String prompt = variables.resolveText(context.repository().prompt("code-plan"));
            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);
            String json = StrUtil.extractJsonObject(response.content());
            if (json.isEmpty()) {
                throw new IllegalStateException("機能ブロックの JSON が取得できませんでした");
            }
            CodePlan plan = CodePlan.fromJson(json);
            if (plan.blocks().size() > config.maxBlocks()) {
                throw new IllegalStateException("機能ブロックが上限 " + config.maxBlocks()
                        + " を超えています: " + plan.blocks().size());
            }
            state.setPlan(plan);
            state.advance(FlowGenState.Phase.PLANNED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("code-plan prompt の読み込みに失敗", e);
        }
    }
}
