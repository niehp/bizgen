package com.walk_nie.bizgen.agent.generator.sql;

/** SQL バインド変数の型と固定書式。 */
public enum SqlVarType {

    STRING("/*{name}*/'xx';"),
    DATE("/*{name}*/CURREN_DATE;"),
    NUMBER("/*{name}`*/9;");

    private final String template;

    SqlVarType(String template) {
        this.template = template;
    }

    public String template() {
        return template;
    }

    /** 小駝峰の変数名から SQL 変数指定文字列を生成する。 */
    public String format(String lowerCamelName) {
        return template.replace("{name}", lowerCamelName);
    }

    public static SqlVarType fromToken(String token) {
        if (token == null) {
            return STRING;
        }
        String value = token.trim().toUpperCase(java.util.Locale.ROOT);
        if ("DATE".equals(value) || "日付".equals(token.trim())) {
            return DATE;
        }
        if ("NUMBER".equals(value) || "NUMERIC".equals(value) || "数値".equals(token.trim())) {
            return NUMBER;
        }
        return STRING;
    }
}
