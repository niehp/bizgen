package com.walk_nie.bizgen.agent.sqlgen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SQL 生成要求 md の確定的パーサ。
 *
 * <p>対応書式：
 * <pre>
 * ## 対象
 * - テーブル: receipt_detail
 * - 種別: SELECT        （SELECT / UPDATE / DELETE）
 *
 * ## 選択列             （SELECT のとき。空なら全列 *）
 * - id
 * - amount
 *
 * ## 設定値             （UPDATE のとき。"列 = 値" 形式）
 * - status = 'PAID'
 * - updated_at = CURRENT_TIMESTAMP
 *
 * ## 条件               （WHERE。複数は AND）
 * - application_id = {{applicationId}}
 * - amount &gt; 0
 *
 * ## 並び順             （任意）
 * - payment_date DESC
 * </pre>
 *
 * <p>種別ごとに必須セクションを検査し、欠落は即例外（確定的ゲート）。
 */
public final class SqlRequestParser {

    private SqlRequestParser() {}

    public static SqlRequest parse(Path md) throws Exception {
        String text = Files.readString(md, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return parse(text);
    }

    public static SqlRequest parse(String text) {
        Map<String, List<String>> sections = TableSchemaParser.splitSections(text);

        List<String> target = sections.get("対象");
        require(target != null, "## 対象 セクションが無い");
        Map<String, String> kv = TableSchemaParser.keyValues(target);
        String tableName = kv.get("テーブル");
        require(tableName != null && !tableName.isBlank(), "## 対象 に「テーブル」が無い");
        SqlKind kind = SqlKind.from(kv.get("種別"));

        List<String> selectColumns = bullets(sections, "選択列");
        List<String> assignments = bullets(sections, "設定値");
        List<String> conditions = bullets(sections, "条件");
        List<String> orderBy = bullets(sections, "並び順");

        // 種別ごとの必須ゲート：UPDATE に SET が無い／DELETE に条件が無い等は危険なので落とす。
        if (kind == SqlKind.SELECT) {
            require(!selectColumns.isEmpty(), "SELECT には ## 選択列 が 1 行以上必要（全列は * と書く）");
        } else if (kind == SqlKind.UPDATE) {
            require(!assignments.isEmpty(), "UPDATE には ## 設定値 が 1 行以上必要");
        } else if (kind == SqlKind.DELETE) {
            require(!conditions.isEmpty(), "DELETE には ## 条件 が必須（全件削除を防ぐゲート）");
        }

        return new SqlRequest(kind, tableName.strip(), selectColumns, assignments, conditions, orderBy);
    }

    private static List<String> bullets(Map<String, List<String>> sections, String name) {
        return sections.getOrDefault(name, List.of()).stream()
                .map(TableSchemaParser::stripBullet)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("SQL 仕様 md 書式エラー: " + message);
    }
}
