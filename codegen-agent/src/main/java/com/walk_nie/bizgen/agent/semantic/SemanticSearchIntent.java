package com.walk_nie.bizgen.agent.semantic;

import com.walk_nie.bizgen.agent.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 大模型が「機能記述を意味解析した結果」として出す検索意図。
 *
 * <p>ここで重要なのは、検索語をコード側の機械的なキーワード抽出で決めないことである。
 * モデルが機能記述の意味を読み解き、「どの処理に、どんな既存実装 / 既存 API が必要か」を
 * {@link Query} として宣言する。実際に既存コード库・API 库を走査して採点・切断するのは
 * {@code SemanticRetrievalExecutor} の決定論的な仕事であり、モデルには任せない。</p>
 *
 * <p>強型・上限・重複排除・列挙値検証をここで 100% 行い、モデル出力をそのまま信用しない。</p>
 */
public final class SemanticSearchIntent {

    /** 検索対象の種別。 */
    public enum Target {
        API, CODE;

        public static Target fromToken(String token) {
            String value = token == null ? "" : token.trim().toUpperCase(Locale.ROOT)
                    .replace("-", "_").replace(" ", "_");
            if ("API".equals(value) || "API_CALL".equals(value) || "METHOD".equals(value)) {
                return API;
            }
            if ("CODE".equals(value) || "NEW_CODE".equals(value) || "SIMILAR".equals(value)
                    || "EXAMPLE".equals(value) || "INSTANCE".equals(value)) {
                return CODE;
            }
            return null;
        }
    }

    private static final int MAX_PURPOSE_CHARS = 200;
    private static final int MAX_KEYWORD_CHARS = 80;
    private static final int MAX_KEYWORDS_PER_QUERY = 6;

    /** 1 つの検索意図。 */
    public static final class Query {
        private final Target target;
        private final String purpose;
        private final List<String> keywords;

        public Query(Target target, String purpose, List<String> keywords) {
            this.target = target;
            this.purpose = purpose == null ? "" : purpose;
            this.keywords = Collections.unmodifiableList(
                    keywords == null ? new ArrayList<String>() : new ArrayList<String>(keywords));
        }

        public Target target() {
            return target;
        }

        /** 何を探すか（日本語）。無命中ログと prompt の根拠として使う。 */
        public String purpose() {
            return purpose;
        }

        /** モデルが意味解析から導いた検索語（日本語名・英語物理名・メソッド名が混在してよい）。 */
        public List<String> keywords() {
            return keywords;
        }

        public Map<String, Object> toJsonObject() {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("target", target.name());
            item.put("purpose", purpose);
            item.put("keywords", new ArrayList<String>(keywords));
            return item;
        }
    }

    private final String analysis;
    private final List<Query> queries;
    /** true のときだけモデル由来。false は決定論的フォールバック（モデル呼び出し無し）。 */
    private final boolean modelDriven;

    public SemanticSearchIntent(String analysis, List<Query> queries, boolean modelDriven) {
        this.analysis = analysis == null ? "" : analysis;
        this.queries = Collections.unmodifiableList(
                queries == null ? new ArrayList<Query>() : new ArrayList<Query>(queries));
        this.modelDriven = modelDriven;
    }

    /** 機能記述の意味解析（業務ステップの要約）。 */
    public String analysis() {
        return analysis;
    }

    public List<Query> queries() {
        return queries;
    }

    public boolean modelDriven() {
        return modelDriven;
    }

    public boolean isEmpty() {
        return queries.isEmpty();
    }

    /** この意図が「モデルによる意味解析」として実際に使えるか。 */
    public boolean drivesRetrieval() {
        return modelDriven && !queries.isEmpty();
    }

    /** target 別に検索語を集約する。順序は意図の宣言順、重複は除去。 */
    public List<String> keywordsFor(Target target) {
        Set<String> seen = new LinkedHashSet<String>();
        for (Query query : queries) {
            if (query.target() == target) {
                seen.addAll(query.keywords());
            }
        }
        return new ArrayList<String>(seen);
    }

    public List<String> codeKeywords() {
        return keywordsFor(Target.CODE);
    }

    public List<String> apiKeywords() {
        return keywordsFor(Target.API);
    }

    /** ある target の検索語が 1 つも無いか（無命中ログの対象判定に使う）。 */
    public List<Query> queriesFor(Target target) {
        List<Query> result = new ArrayList<Query>();
        for (Query query : queries) {
            if (query.target() == target) {
                result.add(query);
            }
        }
        return result;
    }

    /** 生成 prompt に「どんな意図で検索したか」を明示するためのテキスト。 */
    public String toPromptText() {
        if (queries.isEmpty()) {
            return "（意味解析による検索意図なし。既存コード・API の参照は行わない）\n";
        }
        StringBuilder out = new StringBuilder();
        if (!analysis.isEmpty()) {
            out.append("意味解析: ").append(clip(analysis, 400)).append("\n\n");
        }
        int index = 1;
        for (Query query : queries) {
            out.append(index++).append(". [").append(query.target().name()).append("] ")
                    .append(clip(query.purpose(), MAX_PURPOSE_CHARS))
                    .append("\n   検索語: ").append(query.keywords()).append('\n');
        }
        return out.toString();
    }

    /** LLM 出力 JSON を厳密に型へ落とす。不正な列挙値・空は例外（呼び出し側がフォールバック）。 */
    public static SemanticSearchIntent fromJson(String text, int maxQueries) {
        Map<String, Object> root = Json.asObject(Json.parse(text));
        String analysis = clip(Json.asString(root.get("analysis")), 600);
        List<Query> queries = new ArrayList<Query>();
        for (Object value : Json.asArray(root.get("queries"))) {
            Map<String, Object> item = Json.asObject(value);
            Target target = Target.fromToken(Json.asString(item.get("target")));
            if (target == null) {
                throw new IllegalArgumentException("検索意図の target が不正です: "
                        + Json.asString(item.get("target")));
            }
            String purpose = clip(Json.asString(item.get("purpose")).trim(), MAX_PURPOSE_CHARS);
            if (purpose.isEmpty()) {
                throw new IllegalArgumentException("検索意図の purpose が空です");
            }
            Set<String> keywords = new LinkedHashSet<String>();
            for (Object keyword : Json.asArray(item.get("keywords"))) {
                String word = clip(Json.asString(keyword).trim().replace("`", ""), MAX_KEYWORD_CHARS);
                if (!word.isEmpty() && keywords.size() < MAX_KEYWORDS_PER_QUERY) {
                    keywords.add(word);
                }
            }
            if (keywords.isEmpty()) {
                throw new IllegalArgumentException("検索意図の keywords が空です: " + purpose);
            }
            if (queries.size() >= maxQueries) {
                break;
            }
            queries.add(new Query(target, purpose, new ArrayList<String>(keywords)));
        }
        return new SemanticSearchIntent(analysis, queries, true);
    }

    /** モデルが使えない場合の決定論的フォールバック。modelDriven=false なので下流は旧経路を使う。 */
    public static SemanticSearchIntent fallback(String reason) {
        return new SemanticSearchIntent("（意味解析を実行できなかったため決定論的検索に戻します: "
                + reason + "）", new ArrayList<Query>(), false);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("analysis", analysis);
        List<Object> items = new ArrayList<Object>();
        for (Query query : queries) {
            items.add(query.toJsonObject());
        }
        root.put("queries", items);
        return Json.stringify(root);
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
