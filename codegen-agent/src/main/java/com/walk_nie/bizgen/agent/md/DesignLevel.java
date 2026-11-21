package com.walk_nie.bizgen.agent.md;

import java.nio.file.Path;
import java.util.Locale;

/** 設計 md の 5 段階。1 レベル = 1 フォルダ。 */
public enum DesignLevel {

    JAVA_COMMON("01-java-common", "Java共通"),
    PROJECT_COMMON("02-project-common", "プロジェクト共通"),
    FEATURE("03-feature", "機能"),
    JAVA_CLASS("04-java-class", "Javaクラス"),
    METHOD("05-method", "メソッド");

    private final String folder;
    private final String label;

    DesignLevel(String folder, String label) {
        this.folder = folder;
        this.label = label;
    }

    public String folder() {
        return folder;
    }

    public String label() {
        return label;
    }

    public Path resolve(Path mdRoot) {
        return mdRoot.resolve(folder);
    }

    /** "java-common" / "01-java-common" / "Java共通" などをレベルに変換する。 */
    public static DesignLevel fromToken(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim().toLowerCase(Locale.ROOT)
                .replace("_", "-").replace(" ", "");
        for (DesignLevel level : values()) {
            if (value.equals(level.folder) || value.equals(level.name().toLowerCase(Locale.ROOT))
                    || value.equals(level.label.toLowerCase(Locale.ROOT))) {
                return level;
            }
        }
        if (value.equals("java-common") || value.equals("javacommon")) {
            return JAVA_COMMON;
        }
        if (value.equals("project-common") || value.equals("projectcommon")) {
            return PROJECT_COMMON;
        }
        if (value.equals("feature") || value.equals("機能")) {
            return FEATURE;
        }
        if (value.equals("class") || value.equals("java-class") || value.equals("javaclass")) {
            return JAVA_CLASS;
        }
        if (value.equals("method") || value.equals("メソッド")) {
            return METHOD;
        }
        return null;
    }
}
