package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.md.FeatureSpec;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;

import java.io.IOException;

/** 機能 md を読み込み、型付きの FeatureSpec に変換する（決定論的）。 */
public final class LoadFeatureStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "01-load-feature";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.INIT);
        try {
            FeatureSpec spec = context.repository().loadFeature(state.featureName());
            state.setSpec(spec);
            state.advance(JavaGenState.Phase.FEATURE_LOADED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("機能 md の読み込みに失敗: " + state.featureName(), e);
        }
    }
}
