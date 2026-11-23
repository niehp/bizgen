package com.walk_nie.bizgen.agent.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * テンプレート填充結果からファイル本文だけを取り出す。
 * 応答全体が 1 つのコードフェンスで包まれている場合だけ fence を外し、
 * テンプレート自身が内部に ``` を持つ場合は誤って切り出さない。
 */
public final class FilledTextExtractor {

    private static final Pattern FENCE = Pattern.compile(
            "(?s)^\\s*```[A-Za-z0-9_+\\-]*[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```[ \\t]*$");

    private FilledTextExtractor() {
    }

    public static String extract(String raw) {
        String text = StrUtil.trimToEmpty(raw);
        if (text.isEmpty()) {
            return "";
        }
        Matcher matcher = FENCE.matcher(text);
        if (matcher.matches()) {
            return trimEnd(matcher.group(1));
        }
        return text;
    }

    private static String trimEnd(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
