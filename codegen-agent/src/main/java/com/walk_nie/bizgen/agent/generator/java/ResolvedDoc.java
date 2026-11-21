package com.walk_nie.bizgen.agent.generator.java;

import com.walk_nie.bizgen.agent.md.DesignLevel;

/** 変数展開済みの設計ドキュメント。 */
public final class ResolvedDoc {

    private final DesignLevel level;
    private final String name;
    private final String body;

    public ResolvedDoc(DesignLevel level, String name, String body) {
        this.level = level;
        this.name = name;
        this.body = body;
    }

    public DesignLevel level() {
        return level;
    }

    public String name() {
        return name;
    }

    public String body() {
        return body;
    }
}
