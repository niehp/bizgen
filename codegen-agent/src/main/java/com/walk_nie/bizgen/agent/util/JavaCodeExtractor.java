package com.walk_nie.bizgen.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LLM の返答から Java ソースだけを決定論的に取り出す。 */
public final class JavaCodeExtractor {

    private static final Pattern FENCE = Pattern.compile("(?s)```(?:java)?\\s*([\\s\\S]*?)```");
    private static final Pattern PACKAGE = Pattern.compile("package\\s+[\\w.]+\\s*;");
    private static final Pattern TYPE = Pattern.compile("(?:public\\s+)?(?:final\\s+)?(?:class|interface|enum)\\s+\\w+");

    private JavaCodeExtractor() {
    }

    public static String extract(String raw) {
        String text = StrUtil.trimToEmpty(raw);
        if (text.isEmpty()) {
            return "";
        }

        // 1) コードフェンス優先。 Java らしいブロックのうち最も長いものを採る。
        Matcher fence = FENCE.matcher(text);
        String best = "";
        while (fence.find()) {
            String candidate = trimEnd(fence.group(1));
            if (looksLikeJava(candidate) && candidate.length() > best.length()) {
                best = candidate;
            }
        }
        if (!best.isEmpty()) {
            return trimEnd(best) + "\n";
        }

        // 2) フェンスが無ければ package / class 宣言から末尾の } までを採る。
        int start = indexOfAny(text, PACKAGE, TYPE);
        if (start < 0) {
            return text;
        }
        int lastBrace = text.lastIndexOf('}');
        int end = lastBrace >= start ? lastBrace + 1 : text.length();
        return trimEnd(text.substring(start, end)) + "\n";
    }

    private static String trimEnd(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean looksLikeJava(String text) {
        return PACKAGE.matcher(text).find() || TYPE.matcher(text).find();
    }

    private static int indexOfAny(String text, Pattern... patterns) {
        int result = -1;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find() && (result < 0 || matcher.start() < result)) {
                result = matcher.start();
            }
        }
        return result;
    }
}
