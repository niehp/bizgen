package com.walk_nie.bizgen.agent.variable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 変数名の命名スタイル。
 * 首字母大寫 / 首字母小寫 / 大駝峰 / 小駝峰 / 大蛇形 / 小蛇形 の 6 種類を自動マッチングする。
 */
public enum NamingStyle {

    UPPER_FIRST("首字母大写", "UpperFirst"),
    LOWER_FIRST("首字母小写", "LowerFirst"),
    UPPER_CAMEL("大驼峰", "UpperCamel"),
    LOWER_CAMEL("小驼峰", "LowerCamel"),
    UPPER_SNAKE("大蛇形", "UpperSnake"),
    LOWER_SNAKE("小蛇形", "LowerSnake");

    private final String japaneseLabel;
    private final String englishLabel;

    NamingStyle(String japaneseLabel, String englishLabel) {
        this.japaneseLabel = japaneseLabel;
        this.englishLabel = englishLabel;
    }

    public String japaneseLabel() {
        return japaneseLabel;
    }

    public String englishLabel() {
        return englishLabel;
    }

    /** "{{変数名|スタイル}}" に書かれた表記からスタイルを決定する。 */
    public static NamingStyle fromToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return LOWER_CAMEL;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\u4e00-\u9fff]", "");
        for (NamingStyle style : values()) {
            if (normalized.equals(style.englishLabel.toLowerCase(Locale.ROOT))
                    || normalized.equals(style.name().toLowerCase(Locale.ROOT))
                    || normalized.equals(style.japaneseLabel)) {
                return style;
            }
        }
        if ("uc".equals(normalized)) {
            return UPPER_CAMEL;
        }
        if ("lc".equals(normalized)) {
            return LOWER_CAMEL;
        }
        if ("us".equals(normalized)) {
            return UPPER_SNAKE;
        }
        if ("ls".equals(normalized)) {
            return LOWER_SNAKE;
        }
        return LOWER_CAMEL;
    }

    /** 値をこのスタイルに変換する。 */
    public String convert(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        switch (this) {
            case UPPER_FIRST:
                return upperFirstRaw(value);
            case LOWER_FIRST:
                return lowerFirstRaw(value);
            case UPPER_CAMEL:
                return joinWords(splitWords(value), true, "");
            case LOWER_CAMEL:
                return joinWords(splitWords(value), false, "");
            case UPPER_SNAKE:
                return joinWords(splitWords(value), true, "_").toUpperCase(Locale.ROOT);
            case LOWER_SNAKE:
                return joinWords(splitWords(value), true, "_").toLowerCase(Locale.ROOT);
            default:
                return value;
        }
    }

    private static String upperFirstRaw(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String lowerFirstRaw(String value) {
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String joinWords(List<String> words, boolean capitalizeFirst, String separator) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(separator);
            }
            boolean capitalize = capitalizeFirst || i > 0;
            out.append(capitalize ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word);
        }
        return out.toString();
    }

    /**
     * 英数字の境界で単語に分割する。
     * applicationId -> [application, Id] / APPLICATION_ID -> [APPLICATION, ID] / HTTPServer -> [HTTP, Server]
     */
    public static List<String> splitWords(String value) {
        List<String> words = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isWordChar(c)) {
                flush(current, words);
                continue;
            }
            if (current.length() > 0 && shouldBreak(current.charAt(current.length() - 1), c,
                    i + 1 < value.length() ? value.charAt(i + 1) : '\0')) {
                flush(current, words);
            }
            current.append(c);
        }
        flush(current, words);
        return words;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c >= '\u4e00' && c <= '\u9fff';
    }

    private static boolean shouldBreak(char previous, char current, char next) {
        if (Character.isLowerCase(previous) && Character.isUpperCase(current)) {
            return true;
        }
        if (Character.isDigit(previous) != Character.isDigit(current)) {
            return true;
        }
        return Character.isUpperCase(previous) && Character.isUpperCase(current)
                && Character.isLowerCase(next);
    }

    private static void flush(StringBuilder current, List<String> words) {
        if (current.length() > 0) {
            words.add(current.toString());
            current.setLength(0);
        }
    }
}
