package com.walk_nie.bizgen.agent.generator.sql;

/** 1 カラムの定義。日本語名と英語物理名の両方を保持する。 */
public final class ColumnDef {

    private final String japaneseName;
    private final String physicalName;
    private final SqlVarType type;
    private final boolean primaryKey;
    private final boolean notNull;
    private final String remark;

    public ColumnDef(String japaneseName, String physicalName, SqlVarType type,
                     boolean primaryKey, boolean notNull, String remark) {
        this.japaneseName = japaneseName;
        this.physicalName = physicalName;
        this.type = type;
        this.primaryKey = primaryKey;
        this.notNull = notNull;
        this.remark = remark;
    }

    public String japaneseName() {
        return japaneseName;
    }

    public String physicalName() {
        return physicalName;
    }

    public SqlVarType type() {
        return type;
    }

    public boolean primaryKey() {
        return primaryKey;
    }

    public boolean notNull() {
        return notNull;
    }

    public String remark() {
        return remark;
    }

    /** SQL 変数名は英語物理名の小駝峰。 */
    public String variableName() {
        return com.walk_nie.bizgen.agent.variable.NamingStyle.LOWER_CAMEL.convert(physicalName);
    }

    public String variableSpec() {
        return type.format(variableName());
    }
}
