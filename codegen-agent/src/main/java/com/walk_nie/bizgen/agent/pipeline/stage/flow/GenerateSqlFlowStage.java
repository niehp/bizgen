package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.AgentLoop;
import com.walk_nie.bizgen.agent.agent.AgentResult;
import com.walk_nie.bizgen.agent.agent.ToolRegistry;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.sql.SqlFlowSupport;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenRequest;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;

/**
 * SQL flow のモデル担当ステージ。
 * 表構造・术语・日英対訳辞書・変数・テンプレートは function tool で自ら取得させ、
 * 取得した情報だけを根拠に SQL を生成する。ループ上限・状態遷移はコード側が管理する。
 */
public final class GenerateSqlFlowStage implements Stage<SqlGenState, SqlGenState> {

    @Override
    public String id() {
        return "sql-flow-02-generate";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public SqlGenState execute(SqlGenState state, PipelineContext context) {
        state.assertPhase(SqlGenState.Phase.FLOW_READY);
        AgentConfig config = context.config();
        SqlGenRequest request = state.request();

        VariableTable variables = state.flowVariables();
        String system;
        try {
            system = variables.resolveText(context.repository().prompt("sql-flow"));
        } catch (IOException e) {
            throw new IllegalStateException("sql-flow prompt の読み込みに失敗", e);
        }
        String user = request.type().name() + " SQL を生成してください。要求: " + request.description()
                + "\n必要な情報は function tool で取得し、最後のターンは SQL 文のみを出力すること。";

        ToolRegistry tools = SqlFlowSupport.tools(config);
        AgentContext agentContext = SqlFlowSupport.agentContext(context, variables);
        AgentResult result = new AgentLoop(tools, agentContext).run(system, user);
        if (!result.success()) {
            throw new IllegalStateException("SQL flow 生成に失敗しました: " + result.error());
        }

        String sql = StrUtil.stripCodeFence(result.finalAnswer()).trim();
        if (sql.isEmpty()) {
            throw new IllegalStateException("LLM が SQL を返しませんでした");
        }
        state.setSql(sql);
        state.setUsedTables(agentContext.selectedTables());
        state.setAgentResult(result);
        state.advance(SqlGenState.Phase.GENERATED);
        return state;
    }
}
