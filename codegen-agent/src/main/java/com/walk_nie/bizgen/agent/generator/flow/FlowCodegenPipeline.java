package com.walk_nie.bizgen.agent.generator.flow;

import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.stage.AnalyzeSemanticsStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ClassifyIntentStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ExtractTermsStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadDesignStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadFeatureStage;
import com.walk_nie.bizgen.agent.pipeline.stage.MatchApiCatalogStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ResolveVariablesStage;
import com.walk_nie.bizgen.agent.pipeline.stage.SearchExistingCodeStage;
import com.walk_nie.bizgen.agent.pipeline.stage.SemanticMapStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ValidateCorrectDraftStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ValidateWriteStage;
import com.walk_nie.bizgen.agent.pipeline.stage.flow.CombinePolishStage;
import com.walk_nie.bizgen.agent.pipeline.stage.flow.PlanCodeBlocksStage;
import com.walk_nie.bizgen.agent.pipeline.stage.flow.ReuseBlocksStage;
import com.walk_nie.bizgen.agent.pipeline.stage.flow.ResolveBlocksStage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 6 ステップの Java 生成フロー。
 * 1 機能 md 読込 / 2 変数展開 / 3 意味解析（機能ブロック分解） /
 * 4 API は function tool で定位・それ以外は新規生成 / 5 ブロックを最後までループ /
 * 6 結合・日本語コメント潤色 → 検証・一度だけ自己修正 → UTF-8 落盤。
 *
 * ループの回数・順序・状態遷移はすべてコード側が決め、モデルには各ブロックの中身だけを任せる。
 */
public final class FlowCodegenPipeline {

    public static List<String> stageIds() {
        return Arrays.asList(
                "01-load-feature",
                "02-load-design",
                "03-resolve-variables",
                "04-extract-terms",
                "04b-semantic-retrieval",
                "05-match-apis",
                "06-classify-intent",
                "07-semantic-mapping",
                "08-search-existing-code",
                "flow-01-plan-blocks",
                "flow-01a-reuse-blocks",
                "flow-02-resolve-blocks",
                "flow-03-combine-polish",
                "11-validate-correct",
                "12-validate-write");
    }

    /** 実行結果。機能ブロックの詳細も人工レビューに使えるよう返す。 */
    public static final class Result {
        private final JavaGenState java;
        private final FlowGenState flow;

        public Result(JavaGenState java, FlowGenState flow) {
            this.java = java;
            this.flow = flow;
        }

        public JavaGenState java() {
            return java;
        }

        public FlowGenState flow() {
            return flow;
        }
    }

    public Result run(String featureName, PipelineContext context) {
        JavaGenState java = new JavaGenState(featureName);

        // --- 決定論的な前置 ---
        new LoadFeatureStage().execute(java, context);
        new LoadDesignStage().execute(java, context);
        new ResolveVariablesStage().execute(java, context);
        new ExtractTermsStage().execute(java, context);
        new com.walk_nie.bizgen.agent.pipeline.stage.AnalyzeSemanticsStage().execute(java, context);
        new MatchApiCatalogStage().execute(java, context);
        new ClassifyIntentStage().execute(java, context);
        new SemanticMapStage().execute(java, context);
        new SearchExistingCodeStage().execute(java, context);

        // --- 機能ブロックのループ（ステップ 3〜6）---
        FlowGenState flow = new FlowGenState(java);
        new PlanCodeBlocksStage().execute(flow, context);
        new ReuseBlocksStage().execute(flow, context);
        new ResolveBlocksStage().execute(flow, context);
        new CombinePolishStage().execute(flow, context);

        // --- 決定論的な後始末（既存の java-gen と同じ検証・写盤）---
        new ValidateCorrectDraftStage().execute(java, context);
        new ValidateWriteStage().execute(java, context);

        return new Result(java, flow);
    }

    public List<String> stages() {
        return new ArrayList<String>(stageIds());
    }
}
