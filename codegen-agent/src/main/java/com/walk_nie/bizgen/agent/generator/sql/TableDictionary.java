package com.walk_nie.bizgen.agent.generator.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 生成用の表構造辞書。
 * 日本語の表名・項目名を英語物理名へ変換し、LLM に渡す表構造を「SQL で使う表だけ」に限定する。
 */
public final class TableDictionary {

    private final Map<String, TableSchema> tables = new LinkedHashMap<String, TableSchema>();
    private final Map<String, String> replacements = new LinkedHashMap<String, String>();
    private List<String> sortedKeys;

    public void add(TableSchema schema) {
        tables.put(schema.physicalName(), schema);
        replacements.put(schema.japaneseName(), schema.physicalName());
        for (ColumnDef column : schema.columns()) {
            replacements.put(column.japaneseName(), column.physicalName());
        }
        sortedKeys = null;
    }

    public int size() {
        return tables.size();
    }

    public List<TableSchema> tables() {
        return new ArrayList<TableSchema>(tables.values());
    }

    public List<String> physicalNames() {
        return new ArrayList<String>(tables.keySet());
    }

    /** prompt / md に含まれる日本語の表名・項目名を英語物理名へ置換する。 */
    public String resolveText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (sortedKeys == null) {
            sortedKeys = new ArrayList<String>(replacements.keySet());
            Collections.sort(sortedKeys, new Comparator<String>() {
                @Override
                public int compare(String left, String right) {
                    return right.length() - left.length();
                }
            });
        }
        String result = text;
        for (String key : sortedKeys) {
            if (key.isEmpty()) {
                continue;
            }
            result = result.replace(key, replacements.get(key));
        }
        return result;
    }

    /** LLM へ渡す表構造コンテキスト。物理名と変数書式を明示する。 */
    public String contextText() {
        StringBuilder out = new StringBuilder();
        for (TableSchema schema : tables.values()) {
            out.append("===== ").append(schema.japaneseName())
                    .append(" / ").append(schema.physicalName()).append(" =====\n");
            if (!schema.description().isEmpty()) {
                out.append("概要: ").append(schema.description()).append('\n');
            }
            out.append("| 日本語名 | 英語物理名 | 型 | 主キー | NOT NULL | SQL変数 |\n");
            out.append("| --- | --- | --- | --- | --- | --- |\n");
            for (ColumnDef column : schema.columns()) {
                out.append("| ").append(column.japaneseName())
                        .append(" | ").append(column.physicalName())
                        .append(" | ").append(column.type().name())
                        .append(" | ").append(column.primaryKey() ? "○" : "")
                        .append(" | ").append(column.notNull() ? "○" : "")
                        .append(" | ").append(column.variableSpec())
                        .append(" |\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * md で指定された対象フィールドを英語物理名へ変換する。
     * 未読込の表・存在しないフィールドは黙って無視せずエラーとする（決定論）。
     */
    public String fieldContext(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return "（md に対象フィールドの指定なし。要求内容と表構造から判断する）";
        }
        StringBuilder out = new StringBuilder();
        out.append("| 指定（日本語） | 英語物理名 | 型 | SQL変数 |\n");
        out.append("| --- | --- | --- | --- |\n");
        for (String raw : fields) {
            String token = raw == null ? "" : raw.trim().replace("`", "");
            if (token.isEmpty()) {
                continue;
            }
            FieldRef ref = resolveField(token);
            out.append("| ").append(ref.table.japaneseName()).append('.')
                    .append(ref.column.japaneseName())
                    .append(" | ").append(ref.table.physicalName()).append('.')
                    .append(ref.column.physicalName())
                    .append(" | ").append(ref.column.type().name())
                    .append(" | ").append(ref.column.variableSpec()).append(" |\n");
        }
        return out.toString();
    }

    private FieldRef resolveField(String token) {
        String tablePart = null;
        String fieldPart = token;
        int dot = token.lastIndexOf('.');
        if (dot > 0) {
            tablePart = token.substring(0, dot).trim();
            fieldPart = token.substring(dot + 1).trim();
        }
        TableSchema table = tablePart == null ? null : findTable(tablePart);
        if (tablePart != null && table == null) {
            throw new IllegalArgumentException("対象フィールドの表名が読み込まれていません: "
                    + tablePart + "（読み込み済み: " + physicalNames() + "）");
        }
        List<TableSchema> candidates = table == null
                ? new ArrayList<TableSchema>(tables.values())
                : java.util.Collections.singletonList(table);
        FieldRef found = null;
        for (TableSchema candidate : candidates) {
            for (ColumnDef column : candidate.columns()) {
                if (!sameName(fieldPart, column.japaneseName())
                        && !sameName(fieldPart, column.physicalName())) {
                    continue;
                }
                if (found != null) {
                    throw new IllegalArgumentException("対象フィールド " + token
                            + " が複数表に存在します。表.フィールド名 で指定してください。");
                }
                found = new FieldRef(candidate, column);
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("対象フィールドが表構造に存在しません: " + token
                    + "（表.フィールド名 の形式で md に記載してください）");
        }
        return found;
    }

    private TableSchema findTable(String name) {
        for (TableSchema schema : tables.values()) {
            if (sameName(name, schema.japaneseName()) || sameName(name, schema.physicalName())) {
                return schema;
            }
        }
        return null;
    }

    private static boolean sameName(String left, String right) {
        return left != null && right != null
                && left.equalsIgnoreCase(right.trim());
    }

    private static final class FieldRef {
        private final TableSchema table;
        private final ColumnDef column;

        private FieldRef(TableSchema table, ColumnDef column) {
            this.table = table;
            this.column = column;
        }
    }

    /** 利用可能な SQL 変数の一覧。 */
    public String variableText() {
        StringBuilder out = new StringBuilder();
        for (TableSchema schema : tables.values()) {
            for (ColumnDef column : schema.columns()) {
                out.append("- ").append(column.japaneseName())
                        .append(" / ").append(column.physicalName())
                        .append(" [").append(column.type().name()).append("] : ")
                        .append(column.variableSpec()).append('\n');
            }
        }
        return out.toString();
    }
}
