package com.walk_nie.bizgen.agent.generator.java;

import java.util.Locale;

/** Java 生成の変更種別。決定論的に扱える列挙型のみを許可する。 */
public enum JavaChangeIntent {

    NEW_FILE,
    ADD_METHOD,
    MODIFY_METHOD;

    public static JavaChangeIntent fromToken(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim().toUpperCase(Locale.ROOT)
                .replace("-", "_").replace(" ", "_");
        for (JavaChangeIntent intent : values()) {
            if (intent.name().equals(value)) {
                return intent;
            }
        }
        if ("NEW".equals(value) || "CREATE".equals(value) || "CREATE_FILE".equals(value)) {
            return NEW_FILE;
        }
        if ("ADD".equals(value)) {
            return ADD_METHOD;
        }
        if ("MODIFY".equals(value) || "UPDATE".equals(value)) {
            return MODIFY_METHOD;
        }
        return null;
    }
}
