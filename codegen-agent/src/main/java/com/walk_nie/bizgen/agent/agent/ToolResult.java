package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.json.Json;

import java.util.Map;

/** function tool の実行結果。LLM へ role=tool メッセージとして返す内容を保持する。 */
public final class ToolResult {

    private final String callId;
    private final String name;
    private final boolean success;
    private final String content;
    private final String error;

    private ToolResult(String callId, String name, boolean success, String content, String error) {
        this.callId = callId == null ? "" : callId;
        this.name = name == null ? "" : name;
        this.success = success;
        this.content = content == null ? "" : content;
        this.error = error == null ? "" : error;
    }

    public static ToolResult ok(ToolCall call, String content) {
        return new ToolResult(idOf(call), nameOf(call), true, content, "");
    }

    public static ToolResult fail(ToolCall call, String error) {
        return new ToolResult(idOf(call), nameOf(call), false, "", error);
    }

    /** ツール実装は元の ToolCall を持たないため、null を渡しても安全に扱う。 */
    private static String idOf(ToolCall call) {
        return call == null ? "" : call.id();
    }

    private static String nameOf(ToolCall call) {
        return call == null ? "" : call.name();
    }

    public String callId() {
        return callId;
    }

    public String name() {
        return name;
    }

    public boolean success() {
        return success;
    }

    public String content() {
        return content;
    }

    public String error() {
        return error;
    }

    /** LLM へ返すテキスト。失敗時も例外を投げず、モデルが次手を打てるようにする。 */
    public String toPromptText() {
        if (success) {
            return "[OK] " + name + "\n" + content;
        }
        return "[ERROR] " + name + ": " + error;
    }

    public String describe() {
        return (success ? "OK " : "NG ") + name + (success ? "" : " - " + error);
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        map.put("callId", callId);
        map.put("name", name);
        map.put("success", success);
        map.put("content", content);
        map.put("error", error);
        return map;
    }

    @Override
    public String toString() {
        return Json.stringify(toJsonObject());
    }
}
