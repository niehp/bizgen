package com.walk_nie.bizgen.agent.sqlgen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 確定的レンダラ（LLM は一切介在しない）。
 *
 * <p>なぜ LLM を使わないか：SQL は構造化言語であり、種別・列名・条件は仕様書から
 * 機械的に決まる。14B モデルに書かせると列名の拼写ミスや余分なセミコロンが
 * 「もっともらしく」混ざる。ここでは全て Java が組み立て、题意を壊さない。
 *
 * <p>確定的ゲート：
 * <ol>
 *   <li>表名一致（要求 vs 表構造）—— 違う表へ UPDATE/DELETE する事故を防ぐ</li>
 *   <li>SELECT 列／UPDATE SET 列／WHERE 列が表構造に存在すること</li>
 *   <li>注入防止：値・条件にセミコロン／SQL コメント／END 相当の列挙を拒否</li>
 *   <li>DELETE は条件必須（SqlRequestParser で担保）＋ 全件否定条件の警告</li>
 * </ol>
 */
public final class SqlRenderer {

    /** WHERE / SET の左辺（列名）を拾う："col = ..." の col。 */
    private static final Pattern LHS = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|<|>|!=|<=|>=|LIKE|IS|IN| BETWEEN)");

    public String render(SqlRequest req, TableSchema schema) {
        gate(req, schema);

        StringBuilder sb = new StringBuilder();
        switch (req.kind()) {
            case SELECT -> {
                String cols = req.selectColumns().isEmpty() || req.selectColumns().stream().anyMatch(c -> c.strip().equals("*"))
                        ? "*" : String.join(", ", req.selectColumns());
                sb.append("SELECT ").append(cols).append(" FROM ").append(req.tableName());
            }
            case UPDATE -> {
                sb.append("UPDATE ").append(req.tableName())
                  .append(" SET ").append(String.join(", ", req.assignments()));
            }
            case DELETE -> sb.append("DELETE FROM ").append(req.tableName());
        }
        appendWhere(sb, req.conditions());
        if (!req.orderBy().isEmpty()) sb.append(" ORDER BY ").append(String.join(", ", req.orderBy()));
        sb.append(';');
        return sb.toString();
    }

    private void appendWhere(StringBuilder sb, List<String> conditions) {
        if (!conditions.isEmpty()) sb.append(" WHERE ").append(String.join(" AND ", conditions));
    }

    /** 全ゲートを通す。1 件でも抵触したら例外（落盤前に止める）。 */
    private void gate(SqlRequest req, TableSchema schema) {
        require(req.tableName() != null && !req.tableName().isBlank(), "テーブル名が空");
        require(schema != null && !schema.columns().isEmpty(), "表構造が空");

        // 1) 表名一致（表構造側に表名が書いてある場合のみ照合）
        if (schema.tableName() != null && !schema.tableName().isBlank()) {
            require(schema.tableName().equalsIgnoreCase(req.tableName()),
                    "テーブル名が不一致: 要求=" + req.tableName() + " / 表構造=" + schema.tableName());
        }

        // 2) 列名の存在確認
        if (req.kind() == SqlKind.SELECT) {
            for (String col : req.selectColumns()) {
                if (col.strip().equals("*")) continue;
                require(schema.hasColumn(lhsOf(col, "選択列")),
                        "選択列「" + col + "」は表構造に無い（既存: " + schema.columnNames() + "）");
            }
        }
        if (req.kind() == SqlKind.UPDATE) {
            for (String set : req.assignments()) {
                String col = assignmentColumn(set);
                require(schema.hasColumn(col),
                        "設定値「" + set + "」の列「" + col + "」は表構造に無い");
            }
        }
        for (String cond : req.conditions()) {
            String col = conditionColumn(cond);
            require(schema.hasColumn(col),
                    "条件「" + cond + "」の列「" + col + "」は表構造に無い");
        }
        for (String order : req.orderBy()) {
            String col = lhsOf(order, "並び順");
            require(schema.hasColumn(col), "並び順「" + order + "」の列「" + col + "」は表構造に無い");
        }

        // 3) 注入防止：仕様書に由来しない SQL 断片を拒否（/*変数*/'値' のみ例外で通す）
        SqlVariables.gate(req.selectColumns(), "選択列");
        SqlVariables.gate(req.assignments(), "設定値");
        SqlVariables.gate(req.conditions(), "条件");
        SqlVariables.gate(req.orderBy(), "並び順");
        SqlVariables.rejectBinds(req.orderBy(), "並び順");
    }

    /** "列 = 値" から列名を取り出す（値側の = に惑わされないよう最初の 1 個だけ）。 */
    static String assignmentColumn(String assignment) {
        int eq = assignment.indexOf('=');
        require(eq > 0, "設定値は「列 = 値」形式で書くこと: " + assignment);
        return lhsOf(assignment.substring(0, eq), "設定値");
    }

    /** 条件や選択列から先頭の識別子（列名）を取り出す。 */
    static String conditionColumn(String condition) {
        Matcher m = LHS.matcher(condition);
        require(m.find(), "条件は「列 = 値」形式で始めること: " + condition);
        return m.group(1);
    }

    private static String lhsOf(String value, String label) {
        String v = value.strip();
        require(!v.isEmpty(), label + " が空");
        Matcher m = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*").matcher(v);
        require(m.find(), label + "「" + value + "」の先頭が列名ではない");
        return m.group();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("SQL ゲート拒否: " + message);
    }
}
