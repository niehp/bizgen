package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.generator.sql.SqlGenRequest;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.List;
import java.util.Map;

/**
 * SQL flow の決定論的な前置ステージ。
 * md / CLI の入力を検証し、prompt と lookup_variable に渡す変数を確定する。LLM は呼ばない。
 * 表構造・术语の取得はモデルが function tool で行うため、ここでは事前差し込みしない。
 */
public final class PrepareSqlFlowStage implements Stage<SqlGenState, SqlGenState> {

    @Override
    public String id() {
        return "sql-flow-01-prepare";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public SqlGenState execute(SqlGenState state, PipelineContext context) {
        state.assertPhase(SqlGenState.Phase.INIT);
        SqlGenRequest request = state.request();

        int max = context.config().maxTables();
        if (request.tables().size() > max) {
            throw new IllegalStateException("表の指定数 " + request.tables().size() + " が上限 " + max
                    + " を超えています。SQL で使用する表だけを指定してください。");
        }

        VariableTable variables = new VariableTable();
        variables.put("sqlType", request.type().name());
        variables.put("description", request.description());
        variables.put("tables", request.tables().toString());
        variables.put("fields", request.fields().toString());
        variables.put("sqlShape", request.sqlShape());
        variables.put("knowledgeInstruction",
                com.walk_nie.bizgen.agent.generator.sql.SqlFlowSupport.knowledgeInstruction());
        for (Map.Entry<String, String> entry : request.variables().entrySet()) {
            variables.put(entry.getKey(), entry.getValue());
        }
        state.flowVariables().putAll(variables.snapshot());
        state.advance(SqlGenState.Phase.FLOW_READY);
        return state;
    }
}
