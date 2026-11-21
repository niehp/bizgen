package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.llm.LlmMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * マルチターン Agent の状態。フェーズを明示し、不正な遷移と無限ループをコード側で防ぐ。
 * モデルが自由に変更できるのは「次にどのツールを呼ぶか」だけである。
 */
public final class AgentState {

    public enum Phase {
        INIT,
        THINKING,
        TOOL_EXECUTED,
        FINAL,
        FAILED
    }

    private Phase phase = Phase.INIT;
    private int round;
    private int toolCallCount;
    private final List<LlmMessage> messages = new ArrayList<LlmMessage>();
    private final List<AgentTrace> traces = new ArrayList<AgentTrace>();
    private String finalAnswer = "";
    private String error = "";

    public AgentState(String systemPrompt, String userPrompt) {
        messages.add(LlmMessage.system(systemPrompt));
        messages.add(LlmMessage.user(userPrompt));
    }

    public Phase phase() {
        return phase;
    }

    public void assertPhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("期待フェーズ " + expected + " に対し現在 " + phase);
        }
    }

    public void advance(Phase next) {
        this.phase = next;
    }

    public int round() {
        return round;
    }

    public void startRound() {
        round++;
    }

    public int toolCallCount() {
        return toolCallCount;
    }

    public void countToolCalls(int count) {
        this.toolCallCount += count;
    }

    public List<LlmMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public void addMessage(LlmMessage message) {
        messages.add(message);
    }

    public void addTrace(AgentTrace trace) {
        traces.add(trace);
    }

    public List<AgentTrace> traces() {
        return Collections.unmodifiableList(traces);
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer == null ? "" : finalAnswer;
    }

    public String error() {
        return error;
    }

    public void setError(String error) {
        this.error = error == null ? "" : error;
    }

    /** 全トレースを整形して表示する（CLI の agent-trace 用）。 */
    public String describeTraces() {
        StringBuilder out = new StringBuilder();
        for (AgentTrace trace : traces) {
            out.append("  ").append(trace.describe()).append('\n');
        }
        return out.toString();
    }
}
