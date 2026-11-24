package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.flow.CodePlan;
import com.walk_nie.bizgen.agent.generator.flow.FlowGenState;
import com.walk_nie.bizgen.agent.generator.java.JavaDraft;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;

/**
 * ステップ 6: 機能ブロックの結果（API 呼び出し + 新規コード）を 1 つのクラスに結合し、
 * モデルに日本語コメントを整えさせる。ロジックの変更はプロンプトで禁止する。
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
        variables.put("termsText", java.termsText());
        variables.put("apisText", java.apisText());
        variables.put("specText", java.specText());
        variables.put("intent", java.intent().intent().name());

        try {
            String prompt = variables.resolveText(context.repository().prompt("combine-polish"));
            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);

            JavaDraft draft = com.walk_nie.bizgen.agent.pipeline.stage.GenerateJavaDraftStage
                    .parseDraftText(response.content(), packageName,
                            resolved.variable("packageComment", resolved.spec().title()));
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
