package com.walk_nie.bizgen.agent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Agent 実行の戻り値。状態とトレースを切り分けて保持する。 */
public final class AgentResult {

    private final AgentState state;
    private final boolean success;

    public AgentResult(AgentState state, boolean success) {
        this.state = state;
        this.success = success;
    }

    public boolean success() {
        return success;
    }

    public String finalAnswer() {
        return state.finalAnswer();
    }

    public String error() {
        return state.error();
    }

    public int rounds() {
        return state.round();
    }

    public int toolCallCount() {
        return state.toolCallCount();
    }

    public List<AgentTrace> traces() {
        return state.traces();
    }

    public List<ToolResult> allToolResults() {
        List<ToolResult> result = new ArrayList<ToolResult>();
        for (AgentTrace trace : state.traces()) {
            result.addAll(trace.toolResults());
        }
        return Collections.unmodifiableList(result);
    }

    public AgentState state() {
        return state;
    }
}
