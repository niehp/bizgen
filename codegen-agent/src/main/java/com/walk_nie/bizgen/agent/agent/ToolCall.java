package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM が発行した function tool 呼び出し。
 * id / name / arguments はすべて LLM 出力から解析され、実行前にコード側で検証される。
 */
public final class ToolCall {

    private final String id;
    private final String name;
    private final Map<String, Object> arguments;

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.arguments = arguments == null ? new LinkedHashMap<String, Object>() : arguments;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    /** OpenAI 互換の tool_calls 要素を解釈する。arguments は JSON 文字列が基本。 */
    public static ToolCall parse(Map<String, Object> raw) {
        String id = Json.asString(raw.get("id"));
        Map<String, Object> function = Json.asObject(raw.get("function"));
        String name = Json.asString(function.get("name"));
        Object arguments = function.get("arguments");
        Map<String, Object> parsed;
        if (arguments instanceof Map) {
            parsed = Json.asObject(arguments);
        } else {
            String text = Json.asString(arguments);
            if (text.trim().isEmpty()) {
                parsed = new LinkedHashMap<String, Object>();
            } else {
                parsed = Json.asObject(Json.parse(text));
            }
        }
        return new ToolCall(id, name, parsed);
    }

    public String describe() {
        return name + "(" + Json.stringify(arguments) + ")";
    }
}
