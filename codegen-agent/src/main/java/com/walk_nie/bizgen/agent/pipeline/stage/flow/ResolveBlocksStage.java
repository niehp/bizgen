package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.AgentLoop;
import com.walk_nie.bizgen.agent.agent.AgentResult;
import com.walk_nie.bizgen.agent.agent.ToolRegistry;
import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.flow.CodePlan;
import com.walk_nie.bizgen.agent.generator.flow.FlowGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.semantic.SemanticSearchIntent;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.List;

/**
 * ステップ 4・5: 機能ブロックを 1 つずつ処理する。
 * API ブロックも新規コードブロックも、変数・API・用語・対訳・類似コード・テンプレートは
 * function tool で自ら取得させる。ループ回数と状態遷移はすべてコード側が管理する。
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
        state.assertPhase(FlowGenState.Phase.REUSED);
        CodePlan plan = state.plan();
        if (plan == null) {
            throw new IllegalStateException("機能ブロックが作られていません");
        }

        for (CodePlan.Block block : plan.blocks()) {
            // コード側でテンプレートが確定したブロックは LLM を一切呼ばない。
            if (block.status() == CodePlan.Status.DONE) {
                continue;
            }
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

    /** API ブロック: find_api などで実在シグネチャを確定してからコード片を書かせる。 */
    private BlockResult resolveWithTool(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        AgentConfig config = context.config();
        VariableTable variables = variables(block, state, context);
        variables.put("knowledgeInstruction", FlowAgentSupport.knowledgeInstruction(
                config.semanticRetrievalEnabled())
                + "- このブロックは API 担当。`find_api` で確定した実在シグネチャ以外は呼ばない。\n");
        try {
            String system = variables.resolveText(context.repository().prompt("code-block-api"));
            String user = "機能ブロック [" + block.index() + "]: " + block.description()
                    + "\n検索キーワード: " + block.apiQuery()
                    + "\n必要なら function tool で情報を取得し、このブロックの Java コード片のみを出力すること。";
            AgentOutcome outcome = runAgent(block, state, context, variables, system, user);
            List<ApiCatalog.ApiEntry> apis = outcome.context.selectedApis();
            String signature = apis.isEmpty() ? "" : apis.get(apis.size() - 1).signature();
            return new BlockResult(StrUtil.stripCodeFence(outcome.result.finalAnswer()), signature);
        } catch (IOException e) {
            throw new IllegalStateException("code-block-api prompt の読み込みに失敗", e);
        }
    }

    /** 新規コードブロック: 事前差し込みは行わず、類似コードもツールで探させる。 */
    private String generateNewCode(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        AgentConfig config = context.config();
        VariableTable variables = variables(block, state, context);
        variables.put("knowledgeInstruction", FlowAgentSupport.knowledgeInstruction(
                config.semanticRetrievalEnabled())
                + "- このブロックは NEW_CODE 担当。既存 API 呼び出しが必要なら API ブロックに任せる。\n");
        try {
            String system = variables.resolveText(context.repository().prompt("code-block-new"));
            String user = "機能ブロック [" + block.index() + "]: " + block.description()
                    + "\n必要なら function tool で用語・テンプレート・類似コードを取得し、"
                    + "このブロックの Java コード片のみを出力すること。";
            return StrUtil.stripCodeFence(
                    runAgent(block, state, context, variables, system, user).result.finalAnswer());
        } catch (IOException e) {
            throw new IllegalStateException("code-block-new prompt の読み込みに失敗", e);
        }
    }

    private AgentOutcome runAgent(CodePlan.Block block, FlowGenState state, PipelineContext context,
                                  VariableTable variables, String system, String user) {
        AgentConfig config = context.config();
        boolean semanticEnabled = config.semanticRetrievalEnabled();
        ToolRegistry registry = FlowAgentSupport.tools(config, semanticEnabled);
        AgentContext agentContext = FlowAgentSupport.agentContext(context, toolVariables(variables, state));
        AgentResult result = new AgentLoop(registry, agentContext).run(system, user);
        if (!result.success()) {
            throw new IllegalStateException("ブロック " + block.index()
                    + " のツール呼び出しに失敗: " + result.error());
        }
        for (SemanticSearchIntent intent : agentContext.semanticSearches()) {
            state.recordSemanticSearch(intent);
        }
        return new AgentOutcome(result, agentContext);
    }

    private static final class AgentOutcome {
        final AgentResult result;
        final AgentContext context;

        AgentOutcome(AgentResult result, AgentContext context) {
            this.result = result;
            this.context = context;
        }
    }

    /** semantic_search ツールが対象ファイルを除外できるよう、ブロック変数に targetFile を足す。 */
    private VariableTable toolVariables(VariableTable source, FlowGenState state) {
        VariableTable vars = new VariableTable();
        vars.putAll(source.snapshot());
        vars.put("targetFile", state.java().resolved().spec().targetFile());
        return vars;
    }

    private VariableTable variables(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        com.walk_nie.bizgen.agent.generator.java.JavaGenState java = state.java();
        VariableTable variables = new VariableTable();
        variables.put("blockIndex", String.valueOf(block.index()));
        variables.put("blockDescription", block.description());
        variables.put("blockKind", block.kind().name());
        variables.put("apiQuery", block.apiQuery());
        variables.put("flow", state.plan().flow());
        variables.put("className", state.plan().className());
        variables.put("featureBody", java.resolved().featureBody());
        variables.put("designDocs", java.resolved().designText());
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
