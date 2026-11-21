package com.walk_nie.bizgen.agent.llm;

import com.walk_nie.bizgen.agent.agent.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM への 1 メッセージ。
 * role=tool の場合は toolCallId を、アシスタントが tool 呼び出しを求める場合は toolCalls を保持する。
 */
public final class LlmMessage {

    private final String role;
    private final String content;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;

    public LlmMessage(String role, String content) {
        this(role, content, "", new ArrayList<ToolCall>());
    }

    public LlmMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.toolCalls = toolCalls == null
                ? new ArrayList<ToolCall>() : new ArrayList<ToolCall>(toolCalls);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }

    /** LLM が tool 呼び出しを要求したターンのアシスタントメッセージ。 */
    public static LlmMessage assistantWithTools(String content, List<ToolCall> toolCalls) {
        return new LlmMessage("assistant", content, "", toolCalls);
    }

    /** tool の実行結果をモデルへ返すメッセージ。 */
    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage("tool", content, toolCallId, null);
    }

    public String role() {
        return role;
    }

    public String content() {
        return content;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public List<ToolCall> toolCalls() {
        return Collections.unmodifiableList(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
