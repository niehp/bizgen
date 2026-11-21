package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenRequest;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenResult;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 生成 SQL をファイルへ保存する（決定論的）。 */
public final class WriteSqlStage implements Stage<SqlGenState, SqlGenState> {

    @Override
    public String id() {
        return "04-write-sql";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public SqlGenState execute(SqlGenState state, PipelineContext context) {
        state.assertPhase(SqlGenState.Phase.GENERATED);
        AgentConfig config = context.config();
        SqlGenRequest request = state.request();
        Path file = StrUtil.isBlank(request.outputFile())
                ? config.outputRoot().resolve("sql/" + request.type().name().toLowerCase() + ".sql")
                : Paths.get(request.outputFile());
        file = file.toAbsolutePath().normalize();
        try {
            FileUtil.write(file, state.sql() + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("SQL の書き出しに失敗: " + file, e);
        }
        state.setResult(new SqlGenResult(request.type(), state.sql(), file,
                state.dictionary().physicalNames()));
        state.advance(SqlGenState.Phase.WRITTEN);
        return state;
    }
}
