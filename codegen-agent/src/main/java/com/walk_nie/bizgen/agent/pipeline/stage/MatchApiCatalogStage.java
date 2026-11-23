package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;

import java.io.IOException;
import java.util.List;

/** Java 生成フレームワークに組み込んだ Step 2: API 库から関連する既存シグネチャを選ぶ（決定論的）。 */
public final class MatchApiCatalogStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "05-match-apis";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.TERMS_EXTRACTED);
        try {
            List<ApiCatalog.ApiEntry> matched = CodegenSupport.matchApis(
                    state.resolved().featureBody(), state.terms(), context.config());
            state.setApis(matched);
            state.setApisText(CodegenSupport.apisText(matched));
            state.advance(JavaGenState.Phase.APIS_MATCHED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("API 库 の読み込みに失敗: "
                    + context.config().apiRoot(), e);
        }
    }
}
