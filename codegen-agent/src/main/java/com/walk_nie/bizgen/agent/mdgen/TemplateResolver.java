package com.walk_nie.bizgen.agent.mdgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * md テンプレートの変数解決（100% 確定的）。
 *
 * <p>書式（3 種類）：
 * <ul>
 *   <li>{@code {{key=value}}} … <b>共通 md での定義</b>。変数表に入れ、本文は値へ展開する。</li>
 *   <li>{@code {{key}}} … 参照。未解決なら全件列挙して拒否。</li>
 *   <li>{@code {{key:default}}} … デフォルト付き参照。</li>
 * </ul>
 *
 * <p>命名規則の自動マッチ（{@link CaseVars}）：
 * {@code {{keyUpper}}} / {@code {{keyLower}}} / {@code {{keyPascal}}} /
 * {@code {{keyCamel}}} / {@code {{keyUpperSnake}}} / {@code {{keySnake}}}
 * は定義済み {@code key} から Java が確定的に派生させる。さらに
 * {@code {{application_id}}} と {@code {{applicationId}}} のように camel 正規化で
 * 同じになる名前も自動で引き当てる（複数該当は曖昧なので拒否）。
 * 明示定義 ＞ 接尾辞派生 ＞ camel 一致 ＞ {@code :既定値} ＞ 拒否 の順。
 *
 * <p>優先順位（上位が勝つ、確定的）：
 * <b>--var の外部指定 ＞ 共通 md の定義 ＞ {{key:default}} の既定値 ＞ 拒否</b>。
 * 定義は共通側（java共通／プロジェクト共通／機能／クラス）だけで許可し、
 * メソッド級で定義すると「下位が共通契約を上書きする」ため拒否する。
 *
 * <p>なぜゲートにするか：変数が unresolved のまま LLM へ渡ると、
 * モデルが {@code {{applicationId}}} を勝手に推測して別値を埋めてしまう。
 * 仕様は契約なので、欠落は黙って通さず全件列挙して拒否する。
 *
 * <p>置換後は定義記法 {@code {{...}}} が文脈に残らない（値へ展開される）ため、
 * モデルは解決済みの値だけを見る。複数 md を跨ぐ定義・参照は
 * 呼び出し側が 1 つの変数表（このインスタンス）を共有することで対応する。
 */
public final class TemplateResolver {

    /** {{name=value}}：共通 md での変数定義（値は { } 以外）。 */
    private static final Pattern DEFINE =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*=\\s*([^{}]*?)\\s*}}");

    /** {{name}} または {{name:default}}。名前は英数字・アンダースコア・ハイフン・ドット。 */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*(?::([^{}]*))?\\s*}}");

    /** 定義・既定値付き・単純参照を 1 パスで捌く統合パターン。 */
    private static final Pattern ANY =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*(?:(=|:)\\s*([^{}]*?)\\s*)?}}");

    private final Map<String, String> vars;

    public TemplateResolver(Map<String, String> vars) {
        this.vars = vars == null ? new LinkedHashMap<>() : new LinkedHashMap<>(vars);
    }

    /**
     * プレースホルダを置換する。定義に出会ったら変数表へ登録（--var は上書きしない）。
     * 未解決が 1 件でもあれば例外（全部列挙）。
     */
    public String resolve(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = ANY.matcher(text);
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1).strip();
            String sep = m.group(2);
            String arg = m.group(3) == null ? null : m.group(3).strip();
            String value;
            if ("=".equals(sep)) {
                // 共通 md の定義。外部指定済みならそちらを優先（putIfAbsent）
                vars.putIfAbsent(name, arg == null ? "" : arg);
                value = vars.get(name);
            } else {
                // 単純参照 or :既定値付き参照。外部指定／共通定義が優先
                value = resolveValue(name, arg);
            }
            if (value == null || value.isBlank()) {
                missing.add(name + (arg != null ? "(既定値も空)" : ""));
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0))); // 可視化のため残す
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
        }
        m.appendTail(out);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "md 変数が未解決（--var または共通 md の {{key=value}} で指定）: "
                            + new LinkedHashSet<>(missing));
        }
        return out.toString();
    }

    /**
     * 参照 1 件の値を確定的に決める。
     * 直接定義 → 6 種の接尾辞派生 → camel 正規化一致 → 既定値。
     * 派生・正規化一致は「変換」であって「推論」ではないのでモデルは介在しない。
     */
    private String resolveValue(String name, String defaultValue) {
        if (vars.containsKey(name)) return vars.get(name);

        CaseVars.Ref ref = CaseVars.resolve(name);
        if (ref != null && vars.containsKey(ref.base())) {
            return CaseVars.apply(vars.get(ref.base()), ref.variant());
        }

        // application_id ⇔ applicationId のような表記ゆれを camel 正規化で吸収する。
        // 複数の実変数が同じ camel に潰れる場合は曖昧なので即拒否（黙って選ばない）。
        String wanted = CaseVars.camel(name);
        List<String> hits = new ArrayList<>();
        for (String key : vars.keySet()) {
            if (CaseVars.camel(key).equals(wanted) && !key.equals(name)) hits.add(key);
        }
        if (hits.size() == 1) return vars.get(hits.get(0));
        if (hits.size() > 1) {
            throw new IllegalArgumentException(
                    "md 変数「" + name + "」は camel 正規化「" + wanted
                            + "」で複数の定義と一致する（曖昧）: " + hits);
        }
        return defaultValue;
    }

    /** テキスト中の「定義」だけを取り出す（共通 md からの事前回収用）。 */
    public static Map<String, String> definitions(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        Matcher m = DEFINE.matcher(text);
        while (m.find()) out.put(m.group(1).strip(), m.group(2).strip());
        return out;
    }

    /** テキスト中の「参照」プレースホルダ名を重複なし・出現順で返す（定義は含まない）。 */
    public static List<String> placeholders(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = PLACEHOLDER.matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            if (seen.add(m.group(1).strip())) out.add(m.group(1).strip());
        }
        return out;
    }

    /** 置換に使った変数表のスナップショット（ログ用／共通定義を含む）。 */
    public Map<String, String> snapshot() {
        return Map.copyOf(vars);
    }
}
