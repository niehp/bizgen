package com.walk_nie.bizgen.agent.generator.sql;

import java.nio.file.Path;
import java.util.List;

/** SQL 生成結果。 */
public final class SqlGenResult {

    private final SqlType type;
    private final String sql;
    private final Path file;
    private final List<String> tableNames;

    public SqlGenResult(SqlType type, String sql, Path file, List<String> tableNames) {
        this.type = type;
        this.sql = sql;
        this.file = file;
        this.tableNames = tableNames;
    }

    public SqlType type() {
        return type;
    }

    public String sql() {
        return sql;
    }

    public Path file() {
        return file;
    }

    public List<String> tableNames() {
        return tableNames;
    }
}
