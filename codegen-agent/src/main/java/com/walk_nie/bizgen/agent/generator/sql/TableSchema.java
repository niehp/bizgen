package com.walk_nie.bizgen.agent.generator.sql;

import com.walk_nie.bizgen.agent.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/** 1 表の構造。md ファイルから決定論的にパースする。 */
public final class TableSchema {

    private final String japaneseName;
    private final String physicalName;
    private final String description;
    private final List<ColumnDef> columns;

    public TableSchema(String japaneseName, String physicalName, String description,
                       List<ColumnDef> columns) {
        this.japaneseName = japaneseName;
        this.physicalName = physicalName;
        this.description = description;
        this.columns = columns == null ? new ArrayList<ColumnDef>() : columns;
    }

    public String japaneseName() {
        return japaneseName;
    }

    public String physicalName() {
        return physicalName;
    }

    public String description() {
        return description;
    }

    public List<ColumnDef> columns() {
        return columns;
    }

    /** 表構造 md をパースする。 */
    public static TableSchema parse(String markdown, String fallbackName) {
        String japanese = extractLabel(markdown, "日本語名", fallbackName);
        String physical = extractLabel(markdown, "英語物理名", fallbackName);
        String description = extractLabel(markdown, "概要", "");
        List<ColumnDef> columns = new ArrayList<ColumnDef>();
        boolean inColumnSection = false;

        for (String rawLine : StrUtil.normalizeNewline(markdown).split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#")) {
                // 「カラム定義」セクションのみをカラムとして解析し、リレーション表などを誤読しない
                inColumnSection = line.contains("カラム定義");
                continue;
            }
            if (!inColumnSection || !line.startsWith("|")) {
                continue;
            }
            String[] cells = line.split("\\|");
            List<String> values = new ArrayList<String>();
            for (String cell : cells) {
                values.add(cell.trim());
            }
            // 先頭と末尾は空要素になる
            if (!values.isEmpty() && values.get(0).isEmpty()) {
                values.remove(0);
            }
            if (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) {
                values.remove(values.size() - 1);
            }
            if (values.size() < 3) {
                continue;
            }
            String jp = values.get(0);
            String en = values.get(1);
            if (jp.equals("日本語名") || en.equals("英語物理名") || en.startsWith("---")
                    || jp.isEmpty() || en.isEmpty()) {
                continue;
            }
            SqlVarType type = SqlVarType.fromToken(values.get(2));
            boolean pk = values.size() > 3 && "○".equals(values.get(3));
            boolean notNull = values.size() > 4 && "○".equals(values.get(4));
            String remark = values.size() > 5 ? values.get(5) : "";
            columns.add(new ColumnDef(jp, en, type, pk, notNull, remark));
        }
        return new TableSchema(japanese, physical, description, columns);
    }

    private static String extractLabel(String markdown, String label, String defaultValue) {
        String prefix = "- " + label + ":";
        for (String rawLine : StrUtil.normalizeNewline(markdown).split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                return value.isEmpty() ? defaultValue : value;
            }
        }
        return defaultValue;
    }
}
