package com.walk_nie.bizgen.agent.mdgen;

import java.util.Locale;

/**
 * md 仕様の階層レベル。粗い順に並べる（広い制約 → 具体的な実装）。
 *
 * <p>java共通 → プロジェクト共通 → 機能 → Javaクラス → メソッド。
 * 上位ほど「守るべき共通ルール」、下位ほど「今回作るもの」。
 * メソッド級だけが構造化（MethodSpec）され、他級は LLM への文脈として渡す。
 */
public enum SpecLevel {
    JAVA_COMMON("java共通", "java-common"),
    PROJECT_COMMON("プロジェクト共通", "project-common"),
    FEATURE("機能", "feature"),
    CLASS("Javaクラス", "class"),
    METHOD("メソッド", "method");

    private final String japanese;
    private final String cliName;

    SpecLevel(String japanese, String cliName) {
        this.japanese = japanese;
        this.cliName = cliName;
    }

    public String japanese() { return japanese; }
    public String cliName() { return cliName; }

    /** CLI の --layer 値（大文字小文字・アンダースコア揺れを吸収）からレベルを引く。 */
    public static SpecLevel fromCliName(String value) {
        if (value == null) return null;
        String v = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (SpecLevel level : values()) {
            if (level.cliName.equals(v)) return level;
        }
        for (SpecLevel level : values()) {
            if (level.japanese.equals(value.strip())) return level;
        }
        return null;
    }
}
