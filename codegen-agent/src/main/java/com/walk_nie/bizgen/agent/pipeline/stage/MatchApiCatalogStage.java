package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.semantic.SemanticRetrievalResult;

import java.io.IOException;
import java.util.List;

/**
 * Step 2: 既存 API の選定。
 *
 * <p>意味解析フェーズ（{@code 04b-semantic-retrieval}）で<b>モデルが機能記述を読み解いて
 * 立てた検索意図</b>に従い、コード側が API 库から実際に取り出した「本物の」シグネチャを採用する。
 * 旧方式のように機能 md + 术语から機械的にマッチした結果を {@code {{apisText}}} へ静的に
 * 差し込むのは、意味解析が無効 / 失敗した場合のフォールバックに限る。</p>
 */
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

        SemanticRetrievalResult semantic = state.semanticRetrieval();
        if (semantic != null && semantic.executed() && semantic.intent().drivesRetrieval()) {
            // モデルが立てた意味検索意図 → コード側が API 库を検索した結果をそのまま使う。
            state.setApis(semantic.apis());
            state.setApisText(withIntentHeader(semantic));
            state.advance(JavaGenState.Phase.APIS_MATCHED);
            return state;
        }

        // フォールバック: 意味解析が無い/無効/失敗した場合のみ旧来の決定論的マッチ。
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

    /** 生成 prompt に「どんな意味解析の意図で選んだ API か」を必ず添える。 */
    private String withIntentHeader(SemanticRetrievalResult semantic) {
        StringBuilder out = new StringBuilder();
        out.append("## 意味解析で立てた検索意図（モデルが機能記述を読み解いて決定）\n");
        out.append(semantic.intent().toPromptText()).append('\n');
        out.append(semantic.apisText());
        return out.toString();
    }
}
