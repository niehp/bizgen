package com.walk_nie.bizgen.agent.reuse;

import com.walk_nie.bizgen.agent.util.KeywordExtractor;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1 つの再利用可能なコード片 / API 呼び出しテンプレート。
 *
 * <p>spec/block/*.md の 1 ファイル = 1 ブロック。構文は決定的にパースし、
 * スコア計算もキーワード一致のみ（埋め込み・外部 API 不使用）。</p>
 *
 * <pre>
 * # ブロック: require-order-id
 *
 * - kind: API_CALL
 * - description: 受注IDが空でないことを検証する
 * - keywords: 必須チェック, 引数検証, requireNonBlank
 * - api: `public static void requireNonBlank(String value, String name)`
 * - vars: value=orderId, name="orderId"
 *
 * ```java
 * Validate.requireNonBlank({{value}}, {{name}});
 * ```
 * </pre>
 */
public final class ReusableBlock {

    public enum Kind {
        CODE, API_CALL;

        public static Kind fromToken(String token) {
            String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT)
                    .replace("-", "_").replace(" ", "_");
            if ("api".equals(value) || "api_call".equals(value)
                    || "apicall".equals(value) || "api呼び出し".equals(value)) {
                return API_CALL;
            }
            if ("code".equals(value) || "new_code".equals(value)
                    || "newcode".equals(value) || "コード".equals(value)) {
                return CODE;
            }
            return null;
        }
    }

    private final String id;
    private final Kind kind;
    private final String description;
    private final List<String> keywords;
    private final String apiSignature;
    private final Map<String, String> defaultVars;
    private final String code;
    private final String source;

    public ReusableBlock(String id, Kind kind, String description, List<String> keywords,
                         String apiSignature, Map<String, String> defaultVars,
                         String code, String source) {
        if (StrUtil.isBlank(id)) {
            throw new IllegalArgumentException("再利用ブロックに id がありません: " + source);
        }
        if (kind == null) {
            throw new IllegalArgumentException("再利用ブロック " + id + " の kind が不正です");
        }
        if (StrUtil.isBlank(code)) {
            throw new IllegalArgumentException("再利用ブロック " + id + " に java コードがありません");
        }
        if (kind == Kind.API_CALL && StrUtil.isBlank(apiSignature)) {
            throw new IllegalArgumentException(
                    "API_CALL ブロック " + id + " に api シグネチャがありません");
        }
        this.id = id.trim();
        this.kind = kind;
        this.description = StrUtil.trimToEmpty(description);
        this.keywords = Collections.unmodifiableList(
                keywords == null ? new ArrayList<String>() : new ArrayList<String>(keywords));
        this.apiSignature = StrUtil.trimToEmpty(apiSignature);
        this.defaultVars = Collections.unmodifiableMap(
                defaultVars == null ? new LinkedHashMap<String, String>()
                        : new LinkedHashMap<String, String>(defaultVars));
        this.code = code.trim();
        this.source = StrUtil.trimToEmpty(source);
    }

    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public String description() {
        return description;
    }

    public List<String> keywords() {
        return keywords;
    }

    public String apiSignature() {
        return apiSignature;
    }

    public Map<String, String> defaultVars() {
        return defaultVars;
    }

    public String code() {
        return code;
    }

    public String source() {
        return source;
    }

    /** クエリとの一致スコア。完全一致語ほど高い点を与え、同点なら id で安定ソートする。 */
    public int score(String query) {
        if (StrUtil.isBlank(query)) {
            return 0;
        }
        String haystack = query.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                score += 5;
            }
        }
        List<String> tokens = KeywordExtractor.extract(query, 60);
        String descriptionText = description.toLowerCase(Locale.ROOT);
        String signature = apiSignature.toLowerCase(Locale.ROOT);
        String name = id.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (descriptionText.contains(lower)) {
                score += 3;
            }
            if (!signature.isEmpty() && signature.contains(lower)) {
                score += 2;
            }
            if (name.contains(lower)) {
                score += 1;
            }
        }
        return score;
    }

    /** テンプレート内の {{変数}} を決定論的に置換する。未定義はそのまま残す（後段で拒否）。 */
    public String resolveCode(Map<String, String> variables) {
        VariableTable table = new VariableTable();
        table.putAll(defaultVars);
        table.putAll(variables);
        return table.resolveText(code);
    }

    /** prompt / CLI 表示用。 */
    public String promptText() {
        StringBuilder out = new StringBuilder();
        out.append("### ").append(id).append(" [").append(kind.name()).append("]\n");
        out.append("- 説明: ").append(description).append('\n');
        if (!keywords.isEmpty()) {
            out.append("- キーワード: ").append(String.join(", ", keywords)).append('\n');
        }
        if (!apiSignature.isEmpty()) {
            out.append("- 確認済み API: `").append(apiSignature).append("`\n");
        }
        out.append("```java\n").append(code).append("\n```\n");
        return out.toString();
    }

    /** md 1 ファイルを 1 ブロックとしてパースする。 */
    public static ReusableBlock parse(String raw, String fileName) {
        String source = StrUtil.trimToEmpty(fileName);
        String id = "";
        String kindToken = "CODE";
        String description = "";
        String apiSignature = "";
        List<String> keywords = new ArrayList<String>();
        Map<String, String> vars = new LinkedHashMap<String, String>();
        String code = "";

        String[] lines = raw.split("\n");
        boolean inCode = false;
        StringBuilder codeBody = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                if (!inCode) {
                    String fence = line.substring(3).trim().toLowerCase(Locale.ROOT);
                    inCode = fence.isEmpty() || fence.startsWith("java");
                } else {
                    inCode = false;
                }
                continue;
            }
            if (inCode) {
                codeBody.append(rawLine).append('\n');
                continue;
            }
            if (line.startsWith("# ")) {
                String title = line.substring(2).trim();
                String prefix = "ブロック:";
                if (title.toLowerCase(Locale.ROOT).startsWith("block:")) {
                    title = title.substring("block:".length()).trim();
                } else if (title.startsWith(prefix)) {
                    title = title.substring(prefix.length()).trim();
                }
                if (!title.isEmpty() && id.isEmpty()) {
                    id = title;
                }
                continue;
            }
            if (!line.startsWith("- ")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(2, colon).trim().toLowerCase(Locale.ROOT)
                    .replace("_", "").replace("-", "").replace(" ", "");
            String value = stripTicks(line.substring(colon + 1).trim());
            if ("id".equals(key) || "名前".equals(key)) {
                id = value;
            } else if ("kind".equals(key) || "種別".equals(key)) {
                kindToken = value;
            } else if ("description".equals(key) || "説明".equals(key)) {
                description = value;
            } else if ("keywords".equals(key) || "キーワード".equals(key)) {
                for (String item : StrUtil.splitCsv(value)) {
                    if (!item.isEmpty() && !keywords.contains(item)) {
                        keywords.add(item);
                    }
                }
            } else if ("api".equals(key) || "apissignature".equals(key)
                    || "apiシグネチャ".equals(key)) {
                apiSignature = value;
            } else if ("vars".equals(key) || "変数".equals(key)) {
                for (String item : StrUtil.splitCsv(value)) {
                    int eq = item.indexOf('=');
                    if (eq > 0) {
                        vars.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
                    }
                }
            }
        }
        if (id.isEmpty()) {
            int dot = source.lastIndexOf('.');
            id = dot > 0 ? source.substring(0, dot) : source;
        }
        Kind kind = Kind.fromToken(kindToken);
        if (kind == null) {
            throw new IllegalArgumentException("再利用ブロック " + id + " の kind が不正です: " + kindToken);
        }
        return new ReusableBlock(id, kind, description, keywords, apiSignature,
                vars, codeBody.toString(), source);
    }

    private static String stripTicks(String value) {
        String result = value.trim();
        if (result.length() >= 2 && result.startsWith("`") && result.endsWith("`")) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }
}
