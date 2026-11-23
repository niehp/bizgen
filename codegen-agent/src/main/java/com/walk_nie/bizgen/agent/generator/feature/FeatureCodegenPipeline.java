package com.walk_nie.bizgen.agent.generator.feature;

import com.walk_nie.bizgen.agent.pipeline.Pipeline;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.stage.feature.LoadFeatureAndTermsStage;
import com.walk_nie.bizgen.agent.pipeline.stage.feature.MatchApisStage;
import com.walk_nie.bizgen.agent.pipeline.stage.feature.GenerateJavaStage;
import com.walk_nie.bizgen.agent.pipeline.stage.feature.ValidateAndCorrectStage;
import com.walk_nie.bizgen.agent.pipeline.stage.feature.WriteFeatureJavaStage;

/**
 * 機能描写 md を入力とする 5 ステップ Pipeline。
 * 1 术语抽出 / 2 API 匹配 / 3 日语代码生成 / 4 自检+一次修正 / 5 UTF-8 落盤。
 * 順番はここで固定し、各ステージは状態フェーズで不正な呼び出しを拒否する。
 */
public final class FeatureCodegenPipeline {

    private final Pipeline pipeline;

    public FeatureCodegenPipeline() {
        this.pipeline = new Pipeline("feature-codegen")
                .add(new LoadFeatureAndTermsStage())
                .add(new MatchApisStage())
                .add(new GenerateJavaStage())
                .add(new ValidateAndCorrectStage())
                .add(new WriteFeatureJavaStage());
    }

    public FeatureGenState run(String featureName, PipelineContext context) {
        return pipeline.run(new FeatureGenState(featureName), context);
    }

    public Pipeline pipeline() {
        return pipeline;
    }
}
