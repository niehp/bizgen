package com.walk_nie.bizgen.agent.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** function tool 引数処理の共通ヘルパ。 */
public final class ToolSupport {

    private static final int DEFAULT_MAX_CHARS = 16000;

    private ToolSupport() {
    }

    public static String str(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    public static List<String> strings(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (value != null) {
            for (String item : String.valueOf(value).split(",")) {
                if (!item.trim().isEmpty()) {
                    result.add(item.trim());
                }
            }
        }
        return result;
    }

    /** 14B モデルのコンテキストを守るため、ツール結果は必ず上限を付ける。 */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit)
                + "\n...（省略: 全 " + text.length() + " 文字中 " + limit + " 文字のみ渡しています）";
    }

    public static Map<String, Object> props(Map<String, Object>... entries) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map<String, Object> entry : entries) {
            result.putAll(entry);
        }
        return result;
    }
}
