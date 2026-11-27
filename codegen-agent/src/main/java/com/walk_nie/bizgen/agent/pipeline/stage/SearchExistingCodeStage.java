package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.java.ExistingCodeSearcher;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeResult;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeSearchLog;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.semantic.SemanticRetrievalResult;

/**
 * 機能記述の<b>意味解析</b>から得た検索意図に従って、設定ファイルで指定した既存 Java 库から
 * 本物の類似実装を取り出す段階。
 *
 * <p>旧方式は機能 md・意味マッピング・ファイル名からコード側が機械的にキーワードを抽出し、
 * {@code {{similarCode}}} へ静的に差し込んでいた。本段階はモデルが「どんな実装を探すか」を
 * 決め（{@code 04b-semantic-retrieval}）、走査・採点・切断・無命中ログは
 * {@code SemanticRetrievalExecutor} が決定論的に行う、という役割分担に変えた。</p>
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

        SemanticRetrievalResult semantic = state.semanticRetrieval();
        if (semantic != null && semantic.executed() && semantic.intent().drivesRetrieval()) {
            SimilarCodeResult wrapped = wrapSemantic(semantic);
            if (!wrapped.snippets().isEmpty()) {
                state.setSimilarCode(wrapped);
            } else {
                // 無命中でも入力ログは Executor が残している。ここでも二重に残す。
                SimilarCodeSearchLog.recordNoHit(context.config(), "semantic-code-final",
                        state.resolved().spec().title(), semantic.intent().toPromptText(), wrapped);
                state.setSimilarCode(wrapped);
            }
            state.advance(JavaGenState.Phase.SIMILAR_CODE_FOUND);
            return state;
        }

        // フォールバック: 意味解析が無い/無効/失敗した場合のみ旧来の決定論的検索。
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

    /** 意味解析の意図 + 本物の命中を 1 つの SimilarCodeResult にまとめる。 */
    private SimilarCodeResult wrapSemantic(SemanticRetrievalResult semantic) {
        SimilarCodeResult code = semantic.codeResult();
        StringBuilder prompt = new StringBuilder();
        prompt.append("## 意味解析で立てた検索意図（モデルが機能記述を読み解いて決定）\n");
        prompt.append(semantic.intent().toPromptText()).append('\n');
        prompt.append(semantic.codeText());
        if (code == null) {
            return new SimilarCodeResult(null,
                    new java.util.ArrayList<com.walk_nie.bizgen.agent.generator.java.SimilarCodeSnippet>(),
                    semantic.intent().codeKeywords(), 0, prompt.toString());
        }
        return new SimilarCodeResult(code.root(), code.snippets(), code.keywords(),
                code.scannedFiles(), prompt.toString());
    }
}
