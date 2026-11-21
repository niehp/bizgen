package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.sql.SqlGenRequest;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.PromptRenderer;
import com.walk_nie.bizgen.agent.pipeline.Stage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * prompt md を展開する（決定論的）。
 * 表構造・要求内容・SQL 雛形・追加変数に含まれる日本語の表名／項目名を英語物理名へ変換してから渡す。
 */
public final class RenderSqlPromptStage implements Stage<SqlGenState, SqlGenState> {

    @Override
    public String id() {
        return "02-render-sql-prompt";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public SqlGenState execute(SqlGenState state, PipelineContext context) {
        state.assertPhase(SqlGenState.Phase.TABLES_LOADED);
        SqlGenRequest request = state.request();
        Map<String, String> variables = new LinkedHashMap<String, String>();
        variables.put("description", state.dictionary().resolveText(request.description()));
        variables.put("sqlShape", state.dictionary().resolveText(request.sqlShape()));
        variables.put("tableContext", state.dictionary().contextText());
        variables.put("sqlVariables", state.dictionary().variableText());
        variables.put("maxTables", String.valueOf(context.config().maxTables()));
        for (Map.Entry<String, String> entry : request.variables().entrySet()) {
            variables.put(entry.getKey(), state.dictionary().resolveText(entry.getValue()));
        }
        try {
            String rendered = PromptRenderer.render(context.repository(), request.promptName(), variables);
            state.setRenderedPrompt(rendered);
            state.advance(SqlGenState.Phase.PROMPT_READY);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("SQL prompt の読み込みに失敗: " + request.promptName(), e);
        }
    }
}
