package com.walk_nie.bizgen.agent.generator.java;

import com.walk_nie.bizgen.agent.pipeline.Pipeline;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.stage.ClassifyIntentStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ExtractTermsStage;
import com.walk_nie.bizgen.agent.pipeline.stage.GenerateJavaDraftStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadDesignStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadFeatureStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadSpecStage;
import com.walk_nie.bizgen.agent.pipeline.stage.MatchApiCatalogStage;
import com.walk_nie.bizgen.agent.pipeline.stage.PolishJavaStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ResolveVariablesStage;
import com.walk_nie.bizgen.agent.pipeline.stage.SearchExistingCodeStage;
import com.walk_nie.bizgen.agent.pipeline.stage.SemanticMapStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ValidateCorrectDraftStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ValidateWriteStage;

/**
 * Java 生成パイプラインの組み立て。ステージ順はここで固定する。
 *
 * 01 load feature / 02 load design / 03 resolve variables / 03b load spec（参考資料）
 * 04 extract terms / 05 match APIs  ← 5 ステップ Pipeline から統合
 * 06 classify intent / 07 semantic map / 08 search existing code
 * 09 generate draft / 10 polish / 11 validate + 1 回だけ自己修正 / 12 write
 */
public final class JavaGenPipeline {

    private final Pipeline pipeline;

    public JavaGenPipeline() {
        this.pipeline = new Pipeline("java-gen")
                .add(new LoadFeatureStage())
                .add(new LoadDesignStage())
                .add(new ResolveVariablesStage())
                .add(new LoadSpecStage())
                .add(new ExtractTermsStage())
                .add(new MatchApiCatalogStage())
                .add(new ClassifyIntentStage())
                .add(new SemanticMapStage())
                .add(new SearchExistingCodeStage())
                .add(new GenerateJavaDraftStage())
                .add(new PolishJavaStage())
                .add(new ValidateCorrectDraftStage())
                .add(new ValidateWriteStage());
    }

    public JavaGenState run(String featureName, PipelineContext context) {
        return pipeline.run(new JavaGenState(featureName), context);
    }

    public Pipeline pipeline() {
        return pipeline;
    }
}
