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
