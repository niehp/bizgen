package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.java.ExistingCodeSearcher;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeResult;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeSearchLog;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;

/**
 * 機能 md と意味マッピングを手がかりに、設定ファイルで指定した既存 Java から類似コードを検索する。
 * 検索・採点・切断はすべて決定論的で、モデルは呼ばない（決定論的）。
 */
public final class SearchExistingCodeStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "08-search-existing-code";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.MAPPED);
        SimilarCodeResult result = ExistingCodeSearcher.search(
                context.config(),
                state.resolved(),
                state.semanticMap(),
                state.resolved().spec().targetFile());
        if (result.snippets().isEmpty()) {
            SimilarCodeSearchLog.recordNoHit(context.config(), "feature",
                    state.resolved().spec().title(), state.resolved().featureBody(), result);
        }
        state.setSimilarCode(result);
        state.advance(JavaGenState.Phase.SIMILAR_CODE_FOUND);
        return state;
    }
}
