package com.walk_nie.bizgen.agent.generator.sql;

import java.util.Locale;

/** SQL の種別。prompt ファイル名と 1 対 1 で対応する。 */
public enum SqlType {

    SELECT("select-sql"),
    UPDATE("update-sql"),
    DELETE("delete-sql");

    private final String promptName;

    SqlType(String promptName) {
        this.promptName = promptName;
    }

    public String promptName() {
        return promptName;
    }

    public static SqlType fromToken(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim().toLowerCase(Locale.ROOT);
        for (SqlType type : values()) {
            if (type.name().toLowerCase(Locale.ROOT).equals(value)
                    || type.promptName.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
