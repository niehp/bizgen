package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.AgentLoop;
import com.walk_nie.bizgen.agent.agent.AgentResult;
import com.walk_nie.bizgen.agent.agent.ToolRegistry;
import com.walk_nie.bizgen.agent.agent.tool.FindApiTool;
import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.flow.CodePlan;
import com.walk_nie.bizgen.agent.generator.flow.FlowGenState;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * ステップ 4・5: 機能ブロックを 1 つずつ処理する。
 * API ブロックは find_api function tool で実在のシグネチャを確定し、
 * それ以外はモデルに新規コードを書かせる。ループの回数と進行はすべてコード側が管理する。
 */
public final class ResolveBlocksStage implements Stage<FlowGenState, FlowGenState> {

    @Override
    public String id() {
        return "flow-02-resolve-blocks";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public FlowGenState execute(FlowGenState state, PipelineContext context) {
        state.assertPhase(FlowGenState.Phase.PLANNED);
        CodePlan plan = state.plan();
        if (plan == null) {
            throw new IllegalStateException("機能ブロックが作られていません");
        }

        for (CodePlan.Block block : plan.blocks()) {
            if (block.kind() == CodePlan.Kind.API) {
                BlockResult resolved = resolveWithTool(block, state, context);
                block.markDone(resolved.code, resolved.signature);
                state.recordToolApi(resolved.signature);
            } else {
                block.markDone(generateNewCode(block, state, context), "");
            }
            state.countBlockRound();
        }

        if (!plan.allDone()) {
            throw new IllegalStateException("処理が完了していない機能ブロックがあります");
        }
        state.advance(FlowGenState.Phase.BLOCKS_RESOLVED);
        return state;
    }

    /** API ブロック: モデルに find_api を呼ばせ、返ったシグネチャだけを使わせる。 */
    private BlockResult resolveWithTool(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        AgentConfig config = context.config();
        VariableTable variables = variables(block, state, context);
        try {
            String system = variables.resolveText(context.repository().prompt("code-block-api"));
            String user = "機能ブロック [" + block.index() + "]: " + block.description()
                    + "\n検索キーワード: " + block.apiQuery();

            ToolRegistry registry = new ToolRegistry().register(new FindApiTool());
            AgentContext agentContext = new AgentContext(config, context.repository(),
                    context.variables(), context.llm());
            AgentResult result = new AgentLoop(registry, agentContext).run(system, user);
            if (!result.success()) {
                throw new IllegalStateException("API ブロック " + block.index()
                        + " の工具呼び出しに失敗: " + result.error());
            }
            List<ApiCatalog.ApiEntry> apis = agentContext.selectedApis();
            String signature = apis.isEmpty() ? "" : apis.get(apis.size() - 1).signature();
            return new BlockResult(StrUtil.stripCodeFence(result.finalAnswer()), signature);
        } catch (IOException e) {
            throw new IllegalStateException("code-block-api prompt の読み込みに失敗", e);
        }
    }

    /** 新規コードブロック: API を使わない部分をモデルに作らせる。 */
    private String generateNewCode(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        AgentConfig config = context.config();
        VariableTable variables = variables(block, state, context);
        try {
            String prompt = variables.resolveText(context.repository().prompt("code-block-new"));
            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);
            return StrUtil.stripCodeFence(response.content());
        } catch (IOException e) {
            throw new IllegalStateException("code-block-new prompt の読み込みに失敗", e);
        }
    }

    private VariableTable variables(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        JavaGenState java = state.java();
        VariableTable variables = new VariableTable();
        variables.put("blockIndex", String.valueOf(block.index()));
        variables.put("blockDescription", block.description());
        variables.put("blockKind", block.kind().name());
        variables.put("apiQuery", block.apiQuery());
        variables.put("flow", state.plan().flow());
        variables.put("className", state.plan().className());
        variables.put("featureBody", java.resolved().featureBody());
        variables.put("designDocs", java.resolved().designText());
        variables.put("termsText", java.termsText());
        variables.put("apisText", java.apisText());
        variables.put("similarCode", java.similarCode().promptText());
        variables.put("specText", java.specText());
        return variables;
    }

    private static final class BlockResult {
        final String code;
        final String signature;

        BlockResult(String code, String signature) {
            this.code = code;
            this.signature = signature;
        }
    }
}
