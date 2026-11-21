package com.walk_nie.bizgen.agent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Agent 1 ターンの監査ログ。LLM 応答とツール実行結果を対応付けて保持する。 */
public final class AgentTrace {

    private final int round;
    private final String assistantContent;
    private final String finishReason;
    private final int promptTokens;
    private final int completionTokens;
    private final List<ToolResult> toolResults = new ArrayList<ToolResult>();

    public AgentTrace(int round, String assistantContent, String finishReason,
                      int promptTokens, int completionTokens) {
        this.round = round;
        this.assistantContent = assistantContent == null ? "" : assistantContent;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public void add(ToolResult result) {
        toolResults.add(result);
    }

    public int round() {
        return round;
    }

    public String assistantContent() {
        return assistantContent;
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

    public List<ToolResult> toolResults() {
        return Collections.unmodifiableList(toolResults);
    }

    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append("round ").append(round).append(" [").append(finishReason).append("]");
        if (!assistantContent.isEmpty()) {
            String text = assistantContent.length() > 120
                    ? assistantContent.substring(0, 120) + "..." : assistantContent;
            out.append(" \"").append(text.replace('\n', ' ')).append('"');
        }
        for (ToolResult result : toolResults) {
            out.append("\n    -> ").append(result.describe());
        }
        return out.toString();
    }
}
