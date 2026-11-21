package com.walk_nie.bizgen.agent.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 文字列処理ユーティリティ。LLM 出力の後処理（コードフェンス除去、JSON 抽出）を決定論的に行う。
 */
public final class StrUtil {

    private StrUtil() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** LLM が返す ```java ... ``` のフェンスを取り除く。 */
    public static String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (!value.startsWith("```")) {
            return value;
        }
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd < 0) {
            return value;
        }
        String body = value.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.stripTrailing() + "\n";
    }

    /**
     * テキストから最初の波括弧で囲まれた JSON オブジェクトを取り出す。
     * LLM が説明文と一緒に JSON を返しても、決定論的に抽出するため。
     */
    public static String extractJsonObject(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    /** カンマ区切りを Trim 済みリストにする。空要素は除外する。 */
    public static List<String> splitCsv(String value) {
        List<String> result = new ArrayList<String>();
        if (isBlank(value)) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 文字列を count 回繰り返す。 */
    public static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(value);
        }
        return out.toString();
    }

    /** 改行を LF に正規化する（Windows でも生成結果を一定にするため）。 */
    public static String normalizeNewline(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
