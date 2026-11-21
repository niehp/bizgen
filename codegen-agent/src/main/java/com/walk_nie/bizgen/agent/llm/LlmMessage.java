package com.walk_nie.bizgen.agent.llm;

/** LLM への 1 メッセージ。 */
public final class LlmMessage {

    private final String role;
    private final String content;

    public LlmMessage(String role, String content) {
        this.role = role;
        this.content = content == null ? "" : content;
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

    public String role() {
        return role;
    }

    public String content() {
        return content;
    }
}
