package com.walk_nie.bizgen.agent.sqlgen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表構造 md の確定的パーサ。
 *
 * <p>対応書式（日本語仕様書文体）：
 * <pre>
 * ## テーブル
 * - テーブル名: receipt_detail
 *
 * ## カラム
 * | カラム名 | 型 | NOT NULL | PK |
 * | --- | --- | --- | --- |
 * | id | bigint | YES | YES |
 * | application_id | varchar(32) | YES | NO |
 * </pre>
 *
 * <p>解析は 100% 確定的。テーブル名・カラム表の欠落、行の桁ズレは即例外。
 * モデルには「表構造とは何か」を推させない——SQL の拼写安全性はここで決まる。
 */
public final class TableSchemaParser {

    private TableSchemaParser() {}

    public static TableSchema parse(Path md) throws Exception {
        String text = Files.readString(md, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return parse(text);
    }

    public static TableSchema parse(String text) {
        Map<String, List<String>> sections = splitSections(text);

        List<String> target = sections.get("テーブル");
        require(target != null, "## テーブル セクションが無い");
        Map<String, String> kv = keyValues(target);
        String tableName = kv.get("テーブル名");
        require(tableName != null && !tableName.isBlank(), "## テーブル に「テーブル名」が無い");

        String tableJapaneseName = kv.getOrDefault("テーブル日本語名", "");

        List<String> rows = sections.get("カラム");
        require(rows != null && !rows.isEmpty(), "## カラム が空");
        List<TableColumn> columns = new ArrayList<>();
        int[] idx = null; // {カラム名, 日本語名, 型, NOT NULL, PK}
        boolean headerSeen = false;
        for (String line : rows) {
            if (!line.startsWith("|")) continue;
            List<String> cells = splitRow(line);
            require(cells.size() >= 2, "カラム行の桁が不足: " + line);
            String first = cells.get(0).strip();
            if (first.equalsIgnoreCase("カラム名")) {
                headerSeen = true;
                idx = headerIndex(cells);
                continue; // 見出し行
            }
            if (isSeparator(first)) { headerSeen = true; continue; } // 区切り行
            if (idx == null) idx = new int[]{0, -1, 1, 2, 3}; // 旧 4 列形式（日本語名なし）
            String name = cell(cells, idx[0]);
            require(!name.isEmpty(), "カラム名が空の行がある");
            String type = cell(cells, idx[2]);
            require(!type.isEmpty(), "カラム「" + name + "」の型が無い");
            boolean notNull = yes(cell(cells, idx[3]));
            boolean pk = yes(cell(cells, idx[4]));
            String japanese = idx[1] >= 0 ? cell(cells, idx[1]) : "";
            columns.add(new TableColumn(name, type, !notNull, pk, japanese));
        }
        require(headerSeen, "## カラム に表形式の行が無い（| カラム名 | 型 | ...）");
        require(!columns.isEmpty(), "カラム定義が 1 行も無い");
        return new TableSchema(tableName.strip(), columns, tableJapaneseName);
    }

    /**
     * 見出し行から列位置を決める。「日本語名」列の有無を吸収し、
     * 旧 4 列（カラム名/型/NOT NULL/PK）と新 5 列の両方を読める。
     */
    private static int[] headerIndex(List<String> cells) {
        int jp = indexOf(cells, "日本語名", -1);
        return new int[]{
                indexOf(cells, "カラム名", 0),
                jp,
                indexOf(cells, "型", jp >= 0 ? 2 : 1),
                indexOf(cells, "NOT NULL", jp >= 0 ? 3 : 2),
                indexOf(cells, "PK", jp >= 0 ? 4 : 3)};
    }

    private static int indexOf(List<String> cells, String label, int fallback) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).strip().equalsIgnoreCase(label)) return i;
        }
        return fallback;
    }

    private static String cell(List<String> cells, int i) {
        return i >= 0 && i < cells.size() ? cells.get(i).strip() : "";
    }

    private static boolean yes(String cell) {
        String v = cell.strip().toUpperCase(Locale.ROOT);
        return v.equals("YES") || v.equals("Y") || v.equals("○") || v.equals("TRUE") || v.equals("1");
    }

    private static boolean isSeparator(String cell) {
        return cell.matches("-+:?");
    }

    /** "| a | b |" → [a, b]（両端の空要素を落とす）。 */
    private static List<String> splitRow(String line) {
        String s = line.strip();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        List<String> out = new ArrayList<>();
        for (String cell : s.split("\\|", -1)) out.add(cell.strip());
        return out;
    }

    /** "## " 見出しで分割し、見出し名→その節の行（空白行除く）。 */
    static Map<String, List<String>> splitSections(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String current = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("## ")) {
                current = line.substring(3).strip();
                out.computeIfAbsent(current, k -> new ArrayList<>());
            } else if (line.startsWith("# ")) {
                current = null;
            } else if (current != null && !line.isEmpty()) {
                out.get(current).add(line);
            }
        }
        return out;
    }

    static Map<String, String> keyValues(List<String> lines) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : lines) {
            String item = stripBullet(line);
            int colon = item.indexOf(':');
            if (colon > 0 && colon < item.length() - 1) {
                kv.put(item.substring(0, colon).strip(), item.substring(colon + 1).strip());
            }
        }
        return kv;
    }

    static String stripBullet(String line) {
        String s = line.strip();
        return s.startsWith("- ") ? s.substring(2).strip() : s;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("表構造 md 書式エラー: " + message);
    }
}
