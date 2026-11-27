package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.agent.AgentLoop;
import com.walk_nie.bizgen.agent.agent.AgentResult;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.flow.CodePlan;
import com.walk_nie.bizgen.agent.generator.flow.FlowGenState;
import com.walk_nie.bizgen.agent.generator.java.JavaDraft;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.reuse.ReuseVerifier;
import com.walk_nie.bizgen.agent.util.JavaCodeExtractor;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.List;

/**
 * ステップ 6: 機能ブロックの結果を 1 つのクラスに結合し、日本語コメントを整える。
 * 用語・spec 資料は function tool で取得させ、ロジック変更はプロンプトで禁止する。
 */
public final class CombinePolishStage implements Stage<FlowGenState, FlowGenState> {

    @Override
    public String id() {
        return "flow-03-combine-polish";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public FlowGenState execute(FlowGenState state, PipelineContext context) {
        state.assertPhase(FlowGenState.Phase.BLOCKS_RESOLVED);
        JavaGenState java = state.java();
        java.assertPhase(JavaGenState.Phase.SIMILAR_CODE_FOUND);
        ResolvedFeature resolved = java.resolved();
        AgentConfig config = context.config();
        CodePlan plan = state.plan();

        String packageName = resolvePackageName(resolved);
        VariableTable variables = new VariableTable();
        variables.put("className", plan.className());
        variables.put("packageName", packageName);
        variables.put("flow", plan.flow());
        variables.put("blocksText", blocksText(plan));
        variables.put("featureBody", resolved.featureBody());
        variables.put("designDocs", resolved.designText());
        variables.put("intent", java.intent().intent().name());
        variables.put("fixedFragments", com.walk_nie.bizgen.agent.reuse.ReuseResolver.promptText(
                state.reuseSelections()));
        variables.put("knowledgeInstruction",
                FlowAgentSupport.knowledgeInstruction(config.semanticRetrievalEnabled()));

        try {
            String system = variables.resolveText(context.repository().prompt("combine-polish"));
            String user = "機能ブロックの結果を結合し、日本語コメントを整えた Java クラスのみを出力すること。";
            AgentResult result = new AgentLoop(
                    FlowAgentSupport.tools(config, config.semanticRetrievalEnabled()),
                    FlowAgentSupport.agentContext(context, variables)).run(system, user);
            if (!result.success()) {
                throw new IllegalStateException("結合・潤色に失敗: " + result.error());
            }

            JavaDraft draft = com.walk_nie.bizgen.agent.pipeline.stage.GenerateJavaDraftStage
                    .parseDraftText(result.finalAnswer(), packageName,
                            resolved.variable("packageComment", resolved.spec().title()));

            // コード側で確定したフラグメントは一字も変えさせない。欠落していれば結合失敗として停止する。
            if (!state.reuseSelections().isEmpty()) {
                String extracted = JavaCodeExtractor.extract(draft.classCode());
                List<String> missing = ReuseVerifier.missing(extracted, state.reuseSelections());
                if (!missing.isEmpty()) {
                    throw new IllegalStateException(
                            "再利用テンプレートが結合結果に含まれていません: " + missing
                                    + "。モデルに固定フラグメントの変更を許可していないか確認してください。");
                }
            }
            java.setDraft(draft);
            // 結合ステージ自体が大模型による潤色を兼ねるため、DRAFTED を飛ばして POLISHED へ。
            java.advance(JavaGenState.Phase.POLISHED);
            state.advance(FlowGenState.Phase.COMBINED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("combine-polish prompt の読み込みに失敗", e);
        }
    }

    private static String blocksText(CodePlan plan) {
        StringBuilder out = new StringBuilder();
        for (CodePlan.Block block : plan.blocks()) {
            out.append("### ブロック ").append(block.index()).append(" (")
                    .append(block.kind().name()).append(")\n");
            out.append("目的: ").append(block.description()).append('\n');
            if (!block.apiSignature().isEmpty()) {
                out.append("確認済み API: `").append(block.apiSignature()).append("`\n");
            }
            out.append("```java\n").append(block.code()).append("\n```\n\n");
        }
        return out.toString();
    }

    private static String resolvePackageName(ResolvedFeature resolved) {
        String declared = resolved.variable("packageName", "");
        if (!StrUtil.isBlank(declared)) {
            return declared;
        }
        String target = resolved.spec().targetFile();
        if (!StrUtil.isBlank(target) && target.contains("/") && target.endsWith(".java")) {
            return target.substring(0, target.lastIndexOf('/')).replace('/', '.');
        }
        return "com.walk_nie.bizgen.generated";
    }
}
