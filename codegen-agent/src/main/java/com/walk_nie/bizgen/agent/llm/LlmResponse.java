package com.walk_nie.bizgen.agent.llm;

import com.walk_nie.bizgen.agent.agent.ToolCall;
import com.walk_nie.bizgen.agent.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** LLM レスポンス。text と function tool 呼び出しの両方を保持する。 */
public final class LlmResponse {

    private final String content;
    private final String finishReason;
    private final int promptTokens;
    private final int completionTokens;
    private final List<ToolCall> toolCalls;

    public LlmResponse(String content, String finishReason, int promptTokens, int completionTokens) {
        this(content, finishReason, promptTokens, completionTokens, new ArrayList<ToolCall>());
    }

    public LlmResponse(String content, String finishReason, int promptTokens, int completionTokens,
                       List<ToolCall> toolCalls) {
        this.content = content == null ? "" : content;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.toolCalls = toolCalls == null ? new ArrayList<ToolCall>() : toolCalls;
    }

    public String content() {
        return content;
    }

    public String finishReason() {
        return finishReason;
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    public List<ToolCall> toolCalls() {
        return Collections.unmodifiableList(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** OpenAI 互換レスポンスを解釈する。 */
    public static LlmResponse parse(String json) {
        Map<String, Object> root = Json.asObject(Json.parse(json));
        List<Object> choices = Json.asArray(root.get("choices"));
        String content = "";
        String finishReason = "";
        List<ToolCall> calls = new ArrayList<ToolCall>();
        if (!choices.isEmpty()) {
            Map<String, Object> choice = Json.asObject(choices.get(0));
            Map<String, Object> message = Json.asObject(choice.get("message"));
            content = Json.asString(message.get("content"));
            finishReason = Json.asString(choice.get("finish_reason"));
            for (Object raw : Json.asArray(message.get("tool_calls"))) {
                calls.add(ToolCall.parse(Json.asObject(raw)));
            }
        }
        Map<String, Object> usage = Json.asObject(root.get("usage"));
        int promptTokens = Json.asInt(usage.get("prompt_tokens"), 0);
        int completionTokens = Json.asInt(usage.get("completion_tokens"), 0);
        return new LlmResponse(content, finishReason, promptTokens, completionTokens, calls);
    }
}
