package com.walk_nie.bizgen.agent.generator.sql;

import com.walk_nie.bizgen.agent.pipeline.Pipeline;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.stage.GenerateSqlStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadTableSchemaStage;
import com.walk_nie.bizgen.agent.pipeline.stage.RenderSqlPromptStage;
import com.walk_nie.bizgen.agent.pipeline.stage.WriteSqlStage;

/** SQL 生成パイプラインの組み立て。 */
public final class SqlGenPipeline {

    private final Pipeline pipeline;

    public SqlGenPipeline() {
        this.pipeline = new Pipeline("sql-gen")
                .add(new LoadTableSchemaStage())
                .add(new RenderSqlPromptStage())
                .add(new GenerateSqlStage())
                .add(new WriteSqlStage());
    }

    public SqlGenState run(SqlGenRequest request, PipelineContext context) {
        return pipeline.run(new SqlGenState(request), context);
    }

    public Pipeline pipeline() {
        return pipeline;
    }
}
