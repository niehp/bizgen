package com.walk_nie.bizgen.agent.generator.sql;

import com.walk_nie.bizgen.agent.pipeline.Pipeline;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.stage.WriteSqlStage;
import com.walk_nie.bizgen.agent.pipeline.stage.flow.GenerateSqlFlowStage;
import com.walk_nie.bizgen.agent.pipeline.stage.flow.PrepareSqlFlowStage;

import java.util.Arrays;
import java.util.List;

/**
 * SQL 生成パイプライン（flow 式）。
 * 決定論的な前置（入力検証・変数確定）→ モデルが function tool で知識を取得して SQL 生成 →
 * 決定論的な後始末（ファイル書き出し）。ループ・状態遷移はコード側が管理する。
 */
public final class SqlGenPipeline {

    private final Pipeline pipeline;

    public SqlGenPipeline() {
        this.pipeline = new Pipeline("sql-gen-flow")
                .add(new PrepareSqlFlowStage())
                .add(new GenerateSqlFlowStage())
                .add(new WriteSqlStage());
    }

    public static List<String> stageIds() {
        return Arrays.asList(
                "sql-flow-01-prepare",
                "sql-flow-02-generate [LLM]",
                "04-write-sql");
    }

    public SqlGenState run(SqlGenRequest request, PipelineContext context) {
        return pipeline.run(new SqlGenState(request), context);
    }

    public Pipeline pipeline() {
        return pipeline;
    }
}
