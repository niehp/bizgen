package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.agent.AgentLoop;
import com.walk_nie.bizgen.agent.agent.AgentResult;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.flow.CodePlan;
import com.walk_nie.bizgen.agent.generator.flow.FlowGenState;
import com.walk_nie.bizgen.agent.md.JavaFeatureOutline;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;

/**
 * ステップ 3: 機能 md の意味を解析し、コードの流れと機能ブロックに分解する（モデル担当）。
 * 変数・API・用語・対訳・類似コード・テンプレートは function tool で自ら取得させ、
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
        com.walk_nie.bizgen.agent.generator.java.JavaGenState java = state.java();
        java.assertPhase(com.walk_nie.bizgen.agent.generator.java.JavaGenState.Phase.SIMILAR_CODE_FOUND);
        AgentConfig config = context.config();

        VariableTable variables = new VariableTable();
        JavaFeatureOutline outline = JavaFeatureOutline.parse(java.resolved().featureBody());
        variables.put("featureOutline", outline.isEmpty()
                ? "（md に Java 契約の明示なし）" : outline.toPromptText());
        variables.put("fixedMethods", outline.methodNamesText());
        variables.put("flowSteps", outline.flowText());
        variables.put("maxBlocks", String.valueOf(config.maxBlocks()));
        variables.put("featureBody", java.resolved().featureBody());
        variables.put("designDocs", java.resolved().designText());
        variables.put("semanticMap", java.semanticMap().toJson());
        variables.put("className", java.semanticMap().className());
        variables.put("knowledgeInstruction",
                FlowAgentSupport.knowledgeInstruction(config.semanticRetrievalEnabled()));

        try {
            String system = variables.resolveText(context.repository().prompt("code-plan"));
            String user = "機能 md を読み、必要な知識は function tool で取得すること。"
                    + "出力は JSON オブジェクトのみ。説明文・コードフェンスは禁止。";
            AgentResult result = new AgentLoop(
                    FlowAgentSupport.tools(config, config.semanticRetrievalEnabled()),
                    FlowAgentSupport.agentContext(context, variables)).run(system, user);
            if (!result.success()) {
                throw new IllegalStateException("機能ブロックの分解に失敗: " + result.error());
            }
            String json = StrUtil.extractJsonObject(result.finalAnswer());
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
