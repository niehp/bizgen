package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.PromptRenderer;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;

/** SQL 文を生成する（モデル担当）。 */
public final class GenerateSqlStage implements Stage<SqlGenState, SqlGenState> {

    @Override
    public String id() {
        return "03-generate-sql";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public SqlGenState execute(SqlGenState state, PipelineContext context) {
        state.assertPhase(SqlGenState.Phase.PROMPT_READY);
        AgentConfig config = context.config();
        LlmRequest request = PromptRenderer.request(config, state.renderedPrompt());
        LlmResponse response = context.ask(request);
        String sql = StrUtil.stripCodeFence(response.content()).trim();
        if (sql.isEmpty()) {
            throw new IllegalStateException("LLM が SQL を返しませんでした");
        }
        state.setSql(sql);
        state.advance(SqlGenState.Phase.GENERATED);
        return state;
    }
}
