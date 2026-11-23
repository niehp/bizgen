package com.walk_nie.bizgen.agent.llm;

import com.walk_nie.bizgen.agent.agent.ToolCall;
import com.walk_nie.bizgen.agent.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI 互換エンドポイントが tool_calls を structured に返さなかった場合の復元処理。
 *
 * Qwen2.5-Coder 系は LM Studio / Ollama の OpenAI 互換 API 経由でも
 * 「ツール呼び出しを message.content のテキストとして出力する」ことがある。
 * そのままだと AgentLoop はそれを最終回答とみなしてツールを実行しないため、
 * 既知の出力形式だけを保守的にStructured tool call へ昇格させる。
 *
 * 誤検出を避けるため、採用するのは次の 3 パターンのみ。
 *   1. タグで囲まれた呼び出し: <tool_call>...</tool_call> / [TOOL_REQUEST]...[END_TOOL_REQUEST]
 *   2. json コードフェンス内の単一の呼び出しオブジェクト（または配列）
 *   3. content 全体が単一の呼び出しオブジェクト（または配列）
 * 通常のコード草稿や SQL から勝手にツール呼び出しを作らないよう、
 * 文中の裸の JSON は採用しない。
 */
public final class ToolCallExtractor {

    private static final Pattern TAGGED = Pattern.compile(
            "(?is)(?:<tool_call>|\\[TOOL_REQUEST\\]|<\\|tool_call\\|>)"
                    + "([\\s\\S]*?)"
                    + "(?:</tool_call>|</tool>|\\[END_TOOL_REQUEST\\]|<\\|tool_call_end\\|>)");

    private static final Pattern FENCE = Pattern.compile(
            "(?is)```(?:json)?\\s*([\\s\\S]*?)```");

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private ToolCallExtractor() {
    }

    /** 復元結果。calls が空なら何も復元していないことを意味する。 */
    public static final class Result {
        private final List<ToolCall> calls;
        private final String remainingContent;
        private final String warning;
        private final String suspicion;

        Result(List<ToolCall> calls, String remainingContent, String warning, String suspicion) {
            this.calls = calls;
            this.remainingContent = remainingContent;
            this.warning = warning;
            this.suspicion = suspicion;
        }

        public List<ToolCall> calls() {
            return Collections.unmodifiableList(calls);
        }

        public String remainingContent() {
            return remainingContent;
        }

        public String warning() {
            return warning;
        }

        public String suspicion() {
            return suspicion;
        }
    }

    public static Result extract(String content) {
        if (content == null || content.trim().isEmpty()) {
            return empty("");
        }

        List<Span> spans = new ArrayList<Span>();
        List<ToolCall> calls = new ArrayList<ToolCall>();
        int nextId = 1;

        // 1) タグ付き呼び出し（最も確実）
        Matcher tagged = TAGGED.matcher(content);
        while (tagged.find()) {
            List<Object> values = scanAllJson(tagged.group(1));
            List<ToolCall> parsed = new ArrayList<ToolCall>();
            if (toCalls(values, parsed, nextId) && !parsed.isEmpty()) {
                spans.add(new Span(tagged.start(), tagged.end()));
                nextId += parsed.size();
                calls.addAll(parsed);
            }
        }

        // 2) json コードフェンス
        if (calls.isEmpty()) {
            Matcher fence = FENCE.matcher(content);
            while (fence.find()) {
                String body = fence.group(1).trim();
                Object value = parseWholeJson(body);
                if (value == null) {
                    continue;
                }
                List<ToolCall> parsed = new ArrayList<ToolCall>();
                if (toCalls(value instanceof List ? Json.asArray(value)
                        : Collections.singletonList(value), parsed, nextId) && !parsed.isEmpty()) {
                    spans.add(new Span(fence.start(), fence.end()));
                    nextId += parsed.size();
                    calls.addAll(parsed);
                }
            }
        }

        // 3) content 全体が呼び出しオブジェクト
        if (calls.isEmpty()) {
            Object whole = parseWholeJson(content.trim());
            if (whole != null) {
                List<ToolCall> parsed = new ArrayList<ToolCall>();
                if (toCalls(whole instanceof List ? Json.asArray(whole)
                        : Collections.singletonList(whole), parsed, nextId) && !parsed.isEmpty()) {
                    spans.add(new Span(0, content.length()));
                    calls.addAll(parsed);
                }
            }
        }

        if (calls.isEmpty()) {
            return empty(content);
        }

        String remaining = removeSpans(content, spans);
        String warning = "tool_calls が空でしたが、content 内のツール呼び出しテキストを復元しました（"
                + calls.size() + " 件）。Qwen2.5-Coder + LM Studio の既知の挙動です。";
        return new Result(calls, remaining, warning, "");
    }

    private static Result empty(String content) {
        String suspicion = "";
        if (content != null && (content.contains("<tool_call>")
                || content.contains("[TOOL_REQUEST]")
                || (content.contains("\"arguments\"") && content.contains("\"name\"")))) {
            suspicion = "tool_calls が空ですが、content にツール呼び出しらしきテキストがあります。"
                    + "モデルが規定外の形式で出力した可能性があります。";
        }
        return new Result(new ArrayList<ToolCall>(), content, "", suspicion);
    }

    /** JSON 値を ToolCall に変換する。1 つでも不正なら false（誤検出防止）。 */
    private static boolean toCalls(List<Object> values, List<ToolCall> output, int startIndex) {
        if (values.isEmpty()) {
            return false;
        }
        List<ToolCall> parsed = new ArrayList<ToolCall>();
        int index = startIndex;
        for (Object raw : values) {
            Map<String, Object> object = Json.asObject(raw);
            if (object.isEmpty()) {
                return false;
            }
            Map<String, Object> function = Json.asObject(object.get("function"));
            Map<String, Object> source = function.isEmpty() ? object : function;
            String name = firstString(source, "name", "tool");
            if (!NAME.matcher(name).matches()) {
                return false;
            }
            Object rawArguments = firstNonNull(source, "arguments", "parameters", "args");
            Map<String, Object> arguments;
            if (rawArguments == null) {
                arguments = new LinkedHashMap<String, Object>();
            } else if (rawArguments instanceof Map) {
                arguments = Json.asObject(rawArguments);
            } else {
                Object decoded;
                try {
                    decoded = Json.parse(Json.asString(rawArguments));
                } catch (RuntimeException e) {
                    return false;
                }
                if (!(decoded instanceof Map)) {
                    return false;
                }
                arguments = Json.asObject(decoded);
            }
            parsed.add(new ToolCall("recovered-" + index, name, arguments));
            index++;
        }
        output.addAll(parsed);
        return true;
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        Object value = firstNonNull(map, keys);
        return value == null ? "" : Json.asString(value);
    }

    private static Object firstNonNull(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object parseWholeJson(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 文字列内のトップレベル JSON オブジェクト／配列をすべて切り出す。 */
    private static List<Object> scanAllJson(String text) {
        List<Object> values = new ArrayList<Object>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') {
                i++;
                continue;
            }
            int end = findJsonEnd(text, i);
            if (end < 0) {
                i++;
                continue;
            }
            Object value = parseWholeJson(text.substring(i, end + 1));
            if (value != null) {
                values.add(value);
            }
            i = end + 1;
        }
        return values;
    }

    /** 対応する閉じ括弧の位置を返す。文字列とエスケープを正しく扱う。 */
    private static int findJsonEnd(String text, int start) {
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
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
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String removeSpans(String content, List<Span> spans) {
        List<Span> sorted = new ArrayList<Span>(spans);
        Collections.sort(sorted, new Comparator<Span>() {
            @Override
            public int compare(Span a, Span b) {
                return b.start - a.start;
            }
        });
        StringBuilder out = new StringBuilder(content);
        int lastStart = Integer.MAX_VALUE;
        for (Span span : sorted) {
            if (span.end <= span.start || span.end > out.length() || span.start >= lastStart) {
                continue;
            }
            out.delete(span.start, span.end);
            lastStart = span.start;
        }
        return out.toString().trim();
    }

    private static final class Span {
        final int start;
        final int end;

        Span(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
