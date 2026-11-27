package com.walk_nie.bizgen.agent.generator.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL 生成の入力。型付きで表現し、必須項目の検証はステージ側で行う。 */
public final class SqlGenRequest {

    private final SqlType type;
    private final String description;
    private final String sqlShape;
    private final List<String> tables;
    private final List<String> fields;
    private final Map<String, String> variables;
    private final String outputFile;
    private final String promptName;

    /** 対象フィールド指定が無い呼び出し用（既存 CLI オプション互換）。 */
    public SqlGenRequest(SqlType type, String description, String sqlShape, List<String> tables,
                         Map<String, String> variables, String outputFile, String promptName) {
        this(type, description, sqlShape, tables, new ArrayList<String>(),
                variables, outputFile, promptName);
    }

    public SqlGenRequest(SqlType type, String description, String sqlShape, List<String> tables,
                         List<String> fields, Map<String, String> variables,
                         String outputFile, String promptName) {
        if (type == null) {
            throw new IllegalArgumentException("SQL 種別は必須です");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL の要求内容は必須です");
        }
        this.type = type;
        this.description = description;
        this.sqlShape = sqlShape == null ? "" : sqlShape;
        this.tables = tables == null ? new ArrayList<String>() : new ArrayList<String>(tables);
        this.fields = fields == null ? new ArrayList<String>() : new ArrayList<String>(fields);
        this.variables = variables == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(variables);
        this.outputFile = outputFile == null ? "" : outputFile;
        this.promptName = promptName == null || promptName.isEmpty() ? type.promptName() : promptName;
    }

    public SqlType type() {
        return type;
    }

    public String description() {
        return description;
    }

    public String sqlShape() {
        return sqlShape;
    }

    public List<String> tables() {
        return tables;
    }

    /** md で指定された対象フィールド（日本語名 / 表.フィールド名）。 */
    public List<String> fields() {
        return fields;
    }

    public Map<String, String> variables() {
        return variables;
    }

    public String outputFile() {
        return outputFile;
    }

    public String promptName() {
        return promptName;
    }
}
