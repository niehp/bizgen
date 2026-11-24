package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;

import java.io.IOException;

/** 機能 md の参照に従って設計 md を必要分だけ読み込む（決定論的）。 */
public final class LoadDesignStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "02-load-design";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.FEATURE_LOADED);
        try {
            state.setDesigns(context.repository().loadDesigns(state.spec()));
            state.advance(JavaGenState.Phase.DESIGNS_LOADED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("設計 md の読み込みに失敗: " + e.getMessage(), e);
        }
    }
}
