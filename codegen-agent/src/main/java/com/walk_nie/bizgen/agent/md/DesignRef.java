package com.walk_nie.bizgen.agent.md;

/** 設計ドキュメントへの参照。"レベル/名前" または "名前" で表す。 */
public final class DesignRef {

    private final DesignLevel level;
    private final String name;

    public DesignRef(DesignLevel level, String name) {
        this.level = level;
        this.name = name;
    }

    /** レベルが null の場合は全レベルから名前で検索する。 */
    public DesignLevel level() {
        return level;
    }

    public String name() {
        return name;
    }

    public static DesignRef parse(String token) {
        String value = token == null ? "" : token.trim().replace('\\', '/');
        String levelToken = null;
        String name = value;
        int slash = value.indexOf('/');
        if (slash > 0) {
            levelToken = value.substring(0, slash);
            name = value.substring(slash + 1);
        }
        if (name.toLowerCase().endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        return new DesignRef(DesignLevel.fromToken(levelToken), name);
    }

    @Override
    public String toString() {
        return (level == null ? "*" : level.folder()) + "/" + name;
    }
}
