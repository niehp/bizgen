package com.walk_nie.bizgen.agent.llm;

import com.walk_nie.bizgen.agent.json.Json;

import java.util.List;
import java.util.Map;

/** LLM レスポンス。 */
public final class LlmResponse {

    private final String content;
    private final String finishReason;
    private final int promptTokens;
    private final int completionTokens;

    public LlmResponse(String content, String finishReason, int promptTokens, int completionTokens) {
        this.content = content == null ? "" : content;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
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

    /** OpenAI 互換レスポンスを解釈する。 */
    public static LlmResponse parse(String json) {
        Map<String, Object> root = Json.asObject(Json.parse(json));
        List<Object> choices = Json.asArray(root.get("choices"));
        String content = "";
        String finishReason = "";
        if (!choices.isEmpty()) {
            Map<String, Object> choice = Json.asObject(choices.get(0));
            Map<String, Object> message = Json.asObject(choice.get("message"));
            content = Json.asString(message.get("content"));
            finishReason = Json.asString(choice.get("finish_reason"));
        }
        Map<String, Object> usage = Json.asObject(root.get("usage"));
        int promptTokens = Json.asInt(usage.get("prompt_tokens"), 0);
        int completionTokens = Json.asInt(usage.get("completion_tokens"), 0);
        return new LlmResponse(content, finishReason, promptTokens, completionTokens);
    }
}
