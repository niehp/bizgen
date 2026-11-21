package com.walk_nie.bizgen.agent.sqlgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日本語の論理名 → 英語の物理名への確定的な変換表。
 *
 * <p>仕様書（md）や prompt ではテーブル名・フィールド名を<b>日本語</b>で書いてよい。
 * ただし大模型へ渡す前に、Java が次の順で機械的に変換する：
 * <pre>
 *   表名（日本語） → 表構造ライブラリ → フィールド日本語名 → フィールド英語名
 * </pre>
 * モデルには英語の物理名だけが見える。したがって生成される SQL の
 * テーブル名・列名は必ず実在の物理名になり、ゲートが照合できる。
 *
 * <p>なぜモデルに翻訳させないか：14B モデルに日本語名→英語名を推させると
 * 表構造に存在しない列名を「もっともらしく」作ってしまう。翻訳は事実の
 * 引き写しなので 100% 確定側（Java）が担う。
 *
 * <p>ゲート：同じ日本語名が別の英語名へ対応する場合は曖昧なので拒否する。
 * 置換は長い名前から順に行い、短い名前による部分一致の誤変換を防ぐ。
 */
public final class NameMapping {

    /** 1 件の対応。 */
    public record Rule(String japanese, String english, String scope) {}

    private final List<Rule> rules;                 // 長い日本語名から順
    private final Map<String, String> tableByJp;   // 日本語テーブル名 → 英語テーブル名

    private NameMapping(List<Rule> rules, Map<String, String> tableByJp) {
        this.rules = List.copyOf(rules);
        this.tableByJp = Map.copyOf(tableByJp);
    }

    /** 表構造ライブラリ（1 ファイル 1 テーブル）から変換表を組み立てる。 */
    public static NameMapping from(TableSchemaLibrary library) {
        Map<String, Rule> byJp = new LinkedHashMap<>();
        Map<String, String> tableByJp = new LinkedHashMap<>();
        if (library == null) return new NameMapping(List.of(), Map.of());

        for (TableSchemaLibrary.Entry entry : library.all()) {
            TableSchema schema = entry.schema();
            if (!schema.tableJapaneseName().isBlank()) {
                add(byJp, schema.tableJapaneseName(), schema.tableName(), "テーブル");
                tableByJp.put(schema.tableJapaneseName(), schema.tableName());
            }
            for (TableColumn col : schema.columns()) {
                if (!col.japaneseName().isBlank()) {
                    add(byJp, col.japaneseName(), col.name(),
                            "カラム（" + schema.tableName() + "）");
                }
            }
        }
        // 長い名前を先に置換（「入金額」で「入金額合計」を壊さない）
        List<Rule> sorted = new ArrayList<>(byJp.values());
        sorted.sort(Comparator.comparingInt((Rule r) -> r.japanese().length())
                .reversed().thenComparing(Rule::japanese));
        return new NameMapping(sorted, tableByJp);
    }

    private static void add(Map<String, Rule> byJp, String jp, String en, String scope) {
        Rule prev = byJp.get(jp);
        if (prev != null && !prev.english().equals(en)) {
            throw new IllegalArgumentException("名称対応が曖昧: 日本語名「" + jp + "」が "
                    + prev.english() + " と " + en + " の両方に対応（" + scope + "）");
        }
        byJp.putIfAbsent(jp, new Rule(jp, en, scope));
    }

    /** テキスト中の日本語名を英語物理名へ置換する（LLM へ渡す<b>前</b>に呼ぶ）。 */
    public String translate(String text) {
        if (text == null || text.isBlank() || rules.isEmpty()) return text;
        String out = text;
        for (Rule r : rules) out = out.replace(r.japanese(), r.english());
        return out;
    }

    /** テキスト中に日本語名が残っているか（翻訳漏れ検知用）。 */
    public List<String> remainingJapanese(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (Rule r : rules) if (text.contains(r.japanese())) out.add(r.japanese());
        return out;
    }

    /** 日本語／英語どちらでも英語テーブル名へ正規化する（ファイル名などに使う）。 */
    public String englishTable(String name) {
        if (name == null) return null;
        String v = name.strip();
        return tableByJp.getOrDefault(v, v).toLowerCase(Locale.ROOT);
    }

    /** prompt／レビュー用の対応表テキスト。 */
    public String mappingTable() {
        if (rules.isEmpty()) return "（日本語名の定義なし。全て英語物理名）\n";
        StringBuilder sb = new StringBuilder();
        sb.append("| 日本語名（仕様書） | 英語物理名（SQL で使う） | 対象 |\n")
          .append("| --- | --- | --- |\n");
        for (Rule r : rules) {
            sb.append("| ").append(r.japanese()).append(" | ").append(r.english())
              .append(" | ").append(r.scope()).append(" |\n");
        }
        return sb.toString();
    }

    public int size() { return rules.size(); }
    public List<Rule> rules() { return rules; }
}
