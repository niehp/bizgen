package com.walk_nie.bizgen.agent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * function tool の白名单レジストリ。
 * 登録済みのツールしか実行できず、引数検証もここで必ず通す（LLM の出力を信じない）。
 */
public final class ToolRegistry {

    private final Map<String, ToolHandler> handlers = new LinkedHashMap<String, ToolHandler>();

    public ToolRegistry register(ToolHandler handler) {
        String name = handler.definition().name();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("ツール名が空です");
        }
        if (handlers.containsKey(name)) {
            throw new IllegalStateException("ツールが重複しています: " + name);
        }
        handlers.put(name, handler);
        return this;
    }

    public List<ToolDefinition> definitions() {
        List<ToolDefinition> result = new ArrayList<ToolDefinition>();
        for (ToolHandler handler : handlers.values()) {
            result.add(handler.definition());
        }
        return Collections.unmodifiableList(result);
    }

    public List<String> names() {
        return Collections.unmodifiableList(new ArrayList<String>(handlers.keySet()));
    }

    public boolean contains(String name) {
        return handlers.containsKey(name);
    }

    /** LLM の呼び出しを検証して実行する。失敗も例外にせず ToolResult として返す。 */
    public ToolResult execute(ToolCall call, AgentContext context) {
        ToolHandler handler = handlers.get(call.name());
        if (handler == null) {
            return ToolResult.fail(call, "未登録のツールです: " + call.name());
        }
        ToolDefinition definition = handler.definition();
        List<String> errors = definition.validate(call.arguments());
        if (!errors.isEmpty()) {
            return ToolResult.fail(call, "引数が不正です: " + errors);
        }
        try {
            ToolResult result = handler.execute(call.arguments(), context);
            if (result == null) {
                return ToolResult.fail(call, "ツールが結果を返しませんでした");
            }
            // ツール実装は callId を知らないため、ここで必ず元の呼び出しに対応付ける
            return result.success()
                    ? ToolResult.ok(call, result.content())
                    : ToolResult.fail(call, result.error());
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return ToolResult.fail(call, "ツール実行中に例外が発生しました: " + message);
        }
    }

    /** 監査 / 'tools' コマンド用の一覧表示。 */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (ToolDefinition definition : definitions()) {
            out.append("- ").append(definition.name()).append(": ")
                    .append(definition.description()).append('\n');
            out.append("  required: ").append(definition.required()).append('\n');
        }
        return out.toString();
    }
}
