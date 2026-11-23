package com.walk_nie.bizgen.agent.variable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {{変数名}} および {{変数名|スタイル}} を解決する可変表。
 * 変数名は小文字化して区切り記号を除いた正規形で保持するため、
 * applicationId / APPLICATION_ID / application_id はすべて同じ変数として自動マッチする。
 */
public final class VariableTable {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)\\}\\}");

    private final Map<String, String> values = new LinkedHashMap<String, String>();

    /** 変数を登録する。同名（正規形一致）は上書きする。 */
    public void put(String name, Object value) {
        if (name == null || value == null) {
            return;
        }
        values.put(normalize(name), String.valueOf(value));
    }

    public void putAll(Map<String, ?> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public boolean contains(String name) {
        return values.containsKey(normalize(name));
    }

    public String get(String name) {
        return values.get(normalize(name));
    }

    /** スタイル指定付きで変数を取得する。未定義なら null。 */
    public String resolve(String name, NamingStyle style) {
        String raw = get(name);
        if (raw == null) {
            return null;
        }
        return style == null ? raw : style.convert(raw);
    }

    /**
     * テキスト内のすべてのプレースホルダを置換する。
     * 未定義の変数はそのまま残し、決定論的に目視可能な状態にする（黙って空にしない）。
     */
    public String resolveText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String replacement = resolveExpression(expression);
            if (replacement == null) {
                replacement = Matcher.quoteReplacement(matcher.group(0));
            } else {
                replacement = Matcher.quoteReplacement(replacement);
            }
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** テキスト中に現れるプレースホルダ式を初出順に返す（重複なし、未解決のまま）。 */
    public static List<String> placeholders(String text) {
        List<String> result = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<String>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            if (!expression.isEmpty() && seen.add(expression)) {
                result.add(expression);
            }
        }
        return result;
    }

    private String resolveExpression(String expression) {
        String key = expression;
        String styleToken = null;
        int pipe = expression.indexOf('|');
        if (pipe >= 0) {
            key = expression.substring(0, pipe).trim();
            styleToken = expression.substring(pipe + 1).trim();
        }
        String raw = get(key);
        if (raw == null) {
            return null;
        }
        if (styleToken == null || styleToken.isEmpty()) {
            return raw;
        }
        NamingStyle style = NamingStyle.fromToken(styleToken);
        return style.convert(raw);
    }

    /** 正規形: 小文字化し、_ - . 空白 を除去する。 */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(".", "")
                .replace(" ", "");
    }

    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
