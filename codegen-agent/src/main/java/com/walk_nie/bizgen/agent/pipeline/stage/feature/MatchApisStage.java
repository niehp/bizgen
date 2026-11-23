package com.walk_nie.bizgen.agent.pipeline.stage.feature;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.feature.FeatureGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;

import java.io.IOException;
import java.util.List;

/** Step 2: 機能 md + 术语 をクエリに、API 库から関連する既存クラス／メソッドを選ぶ（決定論的）。 */
public final class MatchApisStage implements Stage<FeatureGenState, FeatureGenState> {

    @Override
    public String id() {
        return "02-match-apis";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public FeatureGenState execute(FeatureGenState state, PipelineContext context) {
        state.assertPhase(FeatureGenState.Phase.TERMS_EXTRACTED);
        try {
            List<ApiCatalog.ApiEntry> matched = CodegenSupport.matchApis(
                    state.featureBody(), state.terms(), context.config());
            state.setApis(matched);
            state.advance(FeatureGenState.Phase.APIS_MATCHED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("API 库 の読み込みに失敗: "
                    + context.config().apiRoot(), e);
        }
    }
}
