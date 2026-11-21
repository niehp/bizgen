package com.walk_nie.bizgen.agent.mdgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 変数の 6 種類の命名規則を<b>確定的に</b>自動生成／自動解決する。
 *
 * <p>{@code template-studio/shared/casevars.js} と同じ規約を Java 側にも持つ。
 * 基礎変数 {@code org_tree} を定義すると、追加入力なしで次が使える：
 * <pre>
 *   {{org_treeUpper}}      先頭大文字                 Org_tree
 *   {{org_treeLower}}      先頭小文字                 org_tree
 *   {{org_treePascal}}     大驼峰 PascalCase          OrgTree
 *   {{org_treeCamel}}      小驼峰 camelCase           orgTree
 *   {{org_treeUpperSnake}} 大蛇形 UPPER_SNAKE_CASE    ORG_TREE
 *   {{org_treeSnake}}      小蛇形 snake_case          org_tree
 * </pre>
 *
 * <p>なぜ確定的にやるか：命名規則は「推論」ではなく「変換」なのでモデルに委ねない。
 * md を読む前に Java が全部具体値へ置き換え、LLM にはプレースホルダを見せない。
 *
 * <p>解決順（曖昧さを残さない）：
 * <b>明示定義した変数名 ＞ 接尾辞による派生 ＞ camel 正規化一致 ＞ 既定値 ＞ 拒否</b>。
 * 同じ変数名が直接定義されていれば派生扱いしない（下位の実変数が優先）。
 */
public final class CaseVars {

    /** 6 種類の命名規則。接尾辞は {@code casevars.js} の既定値と同一。 */
    public enum Variant {
        UPPER_FIRST("Upper", "首字母大写（先頭だけ大文字）"),
        LOWER_FIRST("Lower", "首字母小写（先頭だけ小文字）"),
        PASCAL("Pascal", "大驼峰 PascalCase"),
        CAMEL("Camel", "小驼峰 camelCase"),
        UPPER_SNAKE("UpperSnake", "大蛇形 UPPER_SNAKE"),
        SNAKE("Snake", "小蛇形 snake_case");

        private final String suffix;
        private final String label;

        Variant(String suffix, String label) {
            this.suffix = suffix;
            this.label = label;
        }

        public String suffix() { return suffix; }
        public String label() { return label; }
    }

    /** 派生の解決結果：どの基礎変数のどの変換か。 */
    public record Ref(String base, Variant variant) {
        public String placeholder() { return "{{" + base + variant.suffix() + "}}"; }
    }

    /** 長い接尾辞を優先（UpperSnake を Upper より先に判定するため）。 */
    private static final List<Variant> BY_SUFFIX_LENGTH = new ArrayList<>(List.of(Variant.values()));
    static {
        BY_SUFFIX_LENGTH.sort((a, b) -> {
            int cmp = Integer.compare(b.suffix().length(), a.suffix().length());
            return cmp != 0 ? cmp : a.name().compareTo(b.name());
        });
    }

    /** 基礎変数名（英字／アンダースコア始まり、ドット・ハイフン許可）。 */
    private static final Pattern BASE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private CaseVars() {}

    /**
     * {@code name} が「基礎変数名 ＋ いずれかの接尾辞」なら派生として解決する。
     * 長い接尾辞から判定し、接尾辞だけの名前（基礎が空）は派生とみなさない。
     */
    public static Ref resolve(String name) {
        if (name == null || name.isBlank()) return null;
        for (Variant v : BY_SUFFIX_LENGTH) {
            String suffix = v.suffix();
            if (!name.endsWith(suffix) || name.length() <= suffix.length()) continue;
            String base = name.substring(0, name.length() - suffix.length());
            if (BASE_NAME.matcher(base).matches()) return new Ref(base, v);
        }
        return null;
    }

    /** 値を指定の命名規則へ変換する（元の値は変えない）。 */
    public static String apply(String text, Variant variant) {
        String s = text == null ? "" : text;
        if (variant == null) return s;
        return switch (variant) {
            case UPPER_FIRST -> upperFirst(s);
            case LOWER_FIRST -> lowerFirst(s);
            case PASCAL -> String.join("", words(s).stream().map(w -> cap(lower(w))).toList());
            case CAMEL -> camel(s);
            case UPPER_SNAKE -> String.join("_", words(s).stream()
                    .map(w -> lower(w).toUpperCase(Locale.ROOT)).toList());
            case SNAKE -> String.join("_", words(s).stream().map(CaseVars::lower).toList());
        };
    }

    /** 小驼峰（camelCase）。SQL の &#47;*变量的小驼峰*&#47; 名にも使う。 */
    public static String camel(String text) {
        String s = text == null ? "" : text;
        List<String> ws = words(s);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ws.size(); i++) {
            String w = lower(ws.get(i));
            out.append(i == 0 ? w : cap(w));
        }
        return out.toString();
    }

    /** 基礎変数 1 個から 6 種の派生変数（名前 → 値）を作る。明示定義と同名なら上書きしない。 */
    public static Map<String, String> expand(String base, String value, Map<String, String> declared) {
        Map<String, String> out = new LinkedHashMap<>();
        if (base == null || base.isBlank()) return out;
        for (Variant v : Variant.values()) {
            String name = base + v.suffix();
            if (declared != null && declared.containsKey(name)) continue;
            out.put(name, apply(value, v));
        }
        return out;
    }

    /**
     * 単語分割。{@code transforms.js} の {@code words()} と同じ順番で 2 つの境界を入れてから
     * 英数字以外で分割する。英数字が無い日本語だけの文字列は 1 語として扱う（空出力を避ける）。
     */
    private static List<String> words(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        String s = raw.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        List<String> parts = new ArrayList<>();
        for (String p : s.split("[^A-Za-z0-9]+")) if (!p.isEmpty()) parts.add(p);
        if (!parts.isEmpty()) return parts;
        String trimmed = raw.strip();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed);
    }

    private static String upperFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String cap(String w) {
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    private static String lower(String w) {
        return w.toLowerCase(Locale.ROOT);
    }
}
