package com.walk_nie.bizgen.agent.semantic;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 意味解析 → 決定論的検索の結果。モデルが出した意図と、コードが既存库から実際に取り出した
 * 「本物の」コード / API を分けて保持する。静的な {{similarCode}} への差し込みではなく、
 * 検索の根拠（意図・検索語・命中・無命中）をすべて追跡可能にする。
 */
public final class SemanticRetrievalResult {

    private final SemanticSearchIntent intent;
    private final SimilarCodeResult codeResult;
    private final List<ApiCatalog.ApiEntry> apis;
    private final List<String> noHitPurposes;
    private final boolean executed;

    public SemanticRetrievalResult(SemanticSearchIntent intent, SimilarCodeResult codeResult,
                                   List<ApiCatalog.ApiEntry> apis, List<String> noHitPurposes,
                                   boolean executed) {
        this.intent = intent == null ? SemanticSearchIntent.fallback("結果なし") : intent;
        this.codeResult = codeResult;
        this.apis = Collections.unmodifiableList(
                apis == null ? new ArrayList<ApiCatalog.ApiEntry>() : new ArrayList<ApiCatalog.ApiEntry>(apis));
        this.noHitPurposes = Collections.unmodifiableList(
                noHitPurposes == null ? new ArrayList<String>() : new ArrayList<String>(noHitPurposes));
        this.executed = executed;
    }

    public SemanticSearchIntent intent() {
        return intent;
    }

    /** 既存 Java 库から実際に取り出したコード（無い場合は null）。 */
    public SimilarCodeResult codeResult() {
        return codeResult;
    }

    /** API 库から実際に取り出した実在シグネチャ。 */
    public List<ApiCatalog.ApiEntry> apis() {
        return apis;
    }

    /** 意図はあったが既存库に命中しなかったもの（ログ済み）。 */
    public List<String> noHitPurposes() {
        return noHitPurposes;
    }

    public boolean executed() {
        return executed;
    }

    public boolean hasCodeHits() {
        return codeResult != null && !codeResult.snippets().isEmpty();
    }

    public boolean hasApiHits() {
        return !apis.isEmpty();
    }

    /** 生成 prompt に差し込む「検索で見つかった本物の既存コード」。 */
    public String codeText() {
        if (!executed || !hasCodeHits()) {
            return "（意味解析に基づく検索では既存コードに命中しませんでした。"
                    + "類似実装を流用せず、機能記述どおりに新規コードを書くこと）\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("以下は【意味解析で立てた検索意図】に基づき、設定された既存 Java 库から"
                + "コード側が実際に取り出した本物の実装である。書き方・命名・Javadoc 粒度の根拠に使う。\n\n");
        out.append(codeResult.promptText());
        return out.toString();
    }

    /** 生成 prompt に差し込む「検索で見つかった本物の API」。 */
    public String apisText() {
        if (!executed || apis.isEmpty()) {
            return "（意味解析に基づく検索では該当する既存 API なし。存在しない API を生成しないこと。）\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("以下は【意味解析で立てた検索意図】に基づき、API 库からコード側が"
                + "実際に取り出した実在シグネチャである。ここに無いメソッドは呼ばない。\n\n");
        String currentClass = "";
        for (ApiCatalog.ApiEntry api : apis) {
            if (!currentClass.equals(api.className())) {
                currentClass = api.className();
                out.append("### ").append(currentClass).append('\n');
            }
            out.append("- `").append(api.signature()).append("` — ")
                    .append(api.description()).append('\n');
        }
        return out.toString();
    }

    /** 人工レビュー / CLI 表示用の要約。 */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("modelDriven=").append(intent.modelDriven())
                .append(", executed=").append(executed)
                .append(", codeHits=").append(hasCodeHits() ? codeResult.snippets().size() : 0)
                .append(", apiHits=").append(apis.size())
                .append(", noHit=").append(noHitPurposes.size());
        return out.toString();
    }
}
