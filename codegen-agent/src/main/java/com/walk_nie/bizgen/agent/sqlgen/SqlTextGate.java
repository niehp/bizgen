package com.walk_nie.bizgen.agent.sqlgen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型が生成した SQL 文を落盤前に検査する<b>確定的ゲート</b>。
 *
 * <p>LLM に SQL を書かせても、正しさの最終判定は必ず Java が行う。
 * 「確定性はコードへ、不確定性はモデルへ」という方針を SQL 側でも徹底する：
 * <ol>
 *   <li>バインド変数は {@link SqlVariables} の 3 形式のみ、値の注入断片も検査</li>
 *   <li>SQL は 1 文だけ、末尾セミコロン 1 個、DDL / UNION / コメント断片を拒否</li>
 *   <li>先頭キーワードが要求種別と一致（UPDATE と DELETE の取り違えを防ぐ）</li>
 *   <li>FROM / JOIN / UPDATE のテーブルは<b>全て表構造ライブラリに実在</b>すること</li>
 *   <li>「別名.カラム名」のカラムは、その別名が指すテーブルに実在すること</li>
 *   <li>非修飾の識別子も、いずれかの参画テーブルの列または SQL キーワードであること</li>
 *   <li>DELETE / UPDATE は WHERE 必須（全件削除・全件更新を防ぐ）</li>
 * </ol>
 *
 * <p>多表結合（JOIN）もここで扱う：各表に別名を付け、結合キーが両表に
 * 実在することを上の 5 が担保する。モデルは JOIN を書けるが、
 * 存在しない列を書いた瞬間に落とされる。
 */
public final class SqlTextGate {

    /** 検査結果（ファイル名・ログに使う）。 */
    public record Result(String primaryTable, List<String> tables,
                         Map<String, SqlVariableType> binds) {}

    /** SQL キーワード・関数（非修飾識別子の誤判定を避けるホワイトリスト）。 */
    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "DISTINCT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN",
            "LIKE", "BETWEEN", "EXISTS", "AS", "ON", "INNER", "LEFT", "RIGHT", "FULL",
            "OUTER", "CROSS", "JOIN", "USING", "ORDER", "BY", "GROUP", "HAVING", "ASC",
            "DESC", "LIMIT", "OFFSET", "FETCH", "FIRST", "ONLY", "UPDATE", "SET",
            "DELETE", "CASE", "WHEN", "THEN", "ELSE", "END", "COUNT", "SUM", "AVG",
            "MIN", "MAX", "COALESCE", "CAST", "CONVERT", "NULLIF", "CURRENT_DATE",
            "CURRENT_TIME", "CURRENT_TIMESTAMP", "SYSDATE", "NOW", "GETDATE", "DATE",
            "TRUE", "FALSE", "ALL", "ANY", "SOME", "WITH", "OVER", "PARTITION",
            "ROWS", "RANGE", "ROW", "ROWNUM", "INSERT", "INTO", "VALUES", "UNION",
            "INTERSECT", "EXCEPT");

    /** 別名として誤読しない節キーワード。 */
    private static final Set<String> CLAUSE_WORDS = Set.of(
            "WHERE", "ON", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "JOIN",
            "USING", "SET", "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "AND", "OR",
            "AS", "SELECT", "FROM", "BY", "DESC", "ASC", "VALUES", "INTO", "WHEN",
            "THEN", "ELSE", "END", "WITH");

    /** 仕様書由来ではない危険断片。 */
    private static final List<String> DANGEROUS = List.of(
            "--", "/*", "*/", "DROP", "TRUNCATE", "UNION", "INSERT", "CREATE",
            "ALTER", "EXEC", "XP_", "GRANT", "REVOKE");

    /** FROM / JOIN / UPDATE の直後のテーブル名と、続く別名（AS は任意）。 */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(FROM|JOIN|UPDATE)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "(?:\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*))?");

    /** 別名.カラム名 / テーブル名.カラム名。 */
    private static final Pattern QUALIFIED = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\b");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern WHERE = Pattern.compile("(?i)\\bWHERE\\b");

    private SqlTextGate() {}

    /** 全ゲートを通す。1 件でも抵触したら例外（落盤前に止める）。 */
    public static Result verify(String sql, SqlKind kind, TableSchemaLibrary library) {
        require(sql != null && !sql.isBlank(), "SQL が空");
        require(kind != null, "SQL 種別が未指定");
        require(library != null && library.size() > 0, "表構造ライブラリが空（照合できない）");
        String s = sql.strip();

        // 1) バインド変数：3 形式だけを許可し、値の注入断片も検査
        List<String> binds = SqlVariables.bindsIn(s);
        SqlVariables.gate(binds, "SQL バインド変数");
        String cleaned = SqlVariables.stripBinds(s);
        require(!cleaned.contains("/*") && !cleaned.contains("*/"),
                "不正なコメント断片（バインド変数は 3 形式のみ）");

        // 2) 1 文だけ・末尾セミコロン 1 個・危険断片なし
        require(cleaned.endsWith(";"), "SQL はセミコロンで終わること");
        String body = cleaned.substring(0, cleaned.length() - 1);
        require(!body.contains(";"), "SQL は 1 文だけ（余分なセミコロン）");
        String upper = body.toUpperCase(Locale.ROOT);
        for (String bad : DANGEROUS) {
            require(!upper.contains(bad), "禁止断片「" + bad + "」が含まれる");
        }

        // 3) 先頭キーワードが要求種別と一致
        Matcher fw = WORD.matcher(body);
        String first = fw.find() ? fw.group() : "";
        require(first.equalsIgnoreCase(kind.name()),
                "種別不一致: 要求=" + kind + " / SQL 先頭=" + first);

        // 4) DELETE / UPDATE は WHERE 必須
        if (kind == SqlKind.DELETE || kind == SqlKind.UPDATE) {
            require(WHERE.matcher(body).find(),
                    kind + " には WHERE が必須（全件変更／全件削除を防ぐ）");
        }

        // 5) 文字列リテラルを消して識別子抽出の誤判定を防ぐ
        String noStrings = body.replaceAll("'(?:[^']|'')*'", " ");

        // 6) テーブル名と別名を抽出（全てライブラリに実在すること）
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        String primary = null;
        Matcher tm = TABLE_REF.matcher(noStrings);
        while (tm.find()) {
            String kw = tm.group(1).toUpperCase(Locale.ROOT);
            String tableName = tm.group(2);
            if (KEYWORDS.contains(tableName.toUpperCase(Locale.ROOT))) continue;
            require(library.find(tableName) != null,
                    "テーブル「" + tableName + "」は表構造ライブラリに無い（存在しない表の SQL は落とせない）");
            if (!tables.contains(tableName)) tables.add(tableName);
            if (primary == null && ("FROM".equals(kw) || "UPDATE".equals(kw))) primary = tableName;

            String alias = tm.group(3);
            if (alias != null && !CLAUSE_WORDS.contains(alias.toUpperCase(Locale.ROOT))) {
                aliasToTable.put(alias.toLowerCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT));
            }
            aliasToTable.putIfAbsent(tableName.toLowerCase(Locale.ROOT),
                    tableName.toLowerCase(Locale.ROOT));
        }
        require(primary != null, "FROM / UPDATE 句が無い（対象テーブルを確定できない）");
        require(!tables.isEmpty(), "テーブルが 1 つも検出できない");

        // 7) 修飾列「別名.カラム名」の存在チェック（JOIN の要）
        Matcher qm = QUALIFIED.matcher(noStrings);
        while (qm.find()) {
            String alias = qm.group(1).toLowerCase(Locale.ROOT);
            String column = qm.group(2);
            String table = aliasToTable.get(alias);
            require(table != null,
                    "未知の別名「" + qm.group(1) + "」（JOIN するテーブルには AS で別名を付けること）");
            TableSchema ts = library.find(table).schema();
            require(ts.hasColumn(column),
                    "列「" + qm.group(1) + "." + column + "」はテーブル「" + table + "」に無い");
        }

        // 8) 非修飾の識別子も、参画テーブルの列か SQL キーワードであること
        String noQualified = QUALIFIED.matcher(noStrings).replaceAll(" ");
        Set<String> knownCols = new HashSet<>();
        Set<String> knownTables = new HashSet<>();
        for (String t : tables) {
            knownTables.add(t.toLowerCase(Locale.ROOT));
            for (String c : library.find(t).schema().columnNames()) {
                knownCols.add(c.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> unknown = new LinkedHashSet<>();
        Matcher wm = WORD.matcher(noQualified);
        while (wm.find()) {
            String w = wm.group();
            String low = w.toLowerCase(Locale.ROOT);
            if (KEYWORDS.contains(w.toUpperCase(Locale.ROOT))) continue;
            if (w.equals("__BIND__")) continue;
            if (knownCols.contains(low) || knownTables.contains(low)) continue;
            if (aliasToTable.containsKey(low)) continue;
            unknown.add(w);
        }
        require(unknown.isEmpty(),
                "未知の識別子（列名の拼写ミス／存在しない列）: " + unknown
                        + "（参画テーブル: " + tables + "）");

        return new Result(primary, List.copyOf(tables),
                Map.copyOf(SqlVariables.bindTypes(binds)));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException("SQL テキストゲート拒否: " + message);
    }
}
