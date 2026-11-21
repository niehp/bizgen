package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * マルチターン Agent ループ。1 ターンごとに LLM を呼び、
 * tool_calls が来たら「登録済みツールだけ」を実行して結果を role=tool で返す。
 *
 * ループ回数・ツール呼び出し回数・同一呼び出しの連続回数はすべてコード側で制限し、
 * 14B モデルが暴走しても無限ループにならないようにする。
 */
public final class AgentLoop {

    private final ToolRegistry registry;
    private final AgentContext context;
    private final int maxRounds;
    private final int maxToolCalls;
    private final int maxSameCall;

    public AgentLoop(ToolRegistry registry, AgentContext context) {
        this(registry, context,
                context.config().agentMaxRounds(),
                context.config().agentMaxToolCalls(),
                context.config().agentMaxSameCall());
    }

    public AgentLoop(ToolRegistry registry, AgentContext context,
                     int maxRounds, int maxToolCalls, int maxSameCall) {
        this.registry = registry;
        this.context = context;
        this.maxRounds = maxRounds;
        this.maxToolCalls = maxToolCalls;
        this.maxSameCall = maxSameCall;
    }

    public ToolRegistry registry() {
        return registry;
    }

    public LlmRequest lastRequest() {
        return lastRequest;
    }

    private LlmRequest lastRequest;

    public AgentResult run(String systemPrompt, String userPrompt) {
        AgentState state = new AgentState(systemPrompt, userPrompt);
        Map<String, Integer> callCounts = new HashMap<String, Integer>();

        for (int round = 1; round <= maxRounds; round++) {
            state.startRound();
            state.assertPhase(round == 1 ? AgentState.Phase.INIT : AgentState.Phase.TOOL_EXECUTED);
            state.advance(AgentState.Phase.THINKING);

            LlmRequest request = new LlmRequest(
                    context.config().model(),
                    state.messages(),
                    context.config().temperature(),
                    context.config().maxTokens(),
                    context.config().seed(),
                    registry.definitions());
            this.lastRequest = request;
            LlmResponse response = context.llm().chat(request);

            AgentTrace trace = new AgentTrace(round, response.content(), response.finishReason(),
                    response.promptTokens(), response.completionTokens());

            if (!response.hasToolCalls()) {
                state.addMessage(LlmMessage.assistant(response.content()));
                state.addTrace(trace);
                state.setFinalAnswer(response.content());
                state.advance(AgentState.Phase.FINAL);
                return new AgentResult(state, true);
            }

            state.addMessage(LlmMessage.assistantWithTools(response.content(), response.toolCalls()));

            for (ToolCall call : response.toolCalls()) {
                String signature = call.describe();
                int count = increment(callCounts, signature);
                if (count > maxSameCall) {
                    trace.add(ToolResult.fail(call, "同一ツール呼び出しが " + count
                            + " 回繰り返されたため停止しました"));
                    state.addTrace(trace);
                    state.setError("Agent ループを停止しました: 同一呼び出しの繰り返し (" + signature + ")");
                    state.advance(AgentState.Phase.FAILED);
                    return new AgentResult(state, false);
                }
                if (state.toolCallCount() + 1 > maxToolCalls) {
                    trace.add(ToolResult.fail(call, "ツール呼び出し上限 " + maxToolCalls + " に達しました"));
                    state.addTrace(trace);
                    state.setError("Agent ループを停止しました: ツール呼び出し上限に達しました");
                    state.advance(AgentState.Phase.FAILED);
                    return new AgentResult(state, false);
                }
                ToolResult result = registry.execute(call, context);
                trace.add(result);
                state.addMessage(LlmMessage.tool(call.id(), result.toPromptText()));
                state.countToolCalls(1);
            }
            state.addTrace(trace);
            state.advance(AgentState.Phase.TOOL_EXECUTED);
        }

        state.setError("Agent ループを停止しました: 最大ターン数 " + maxRounds + " に達しました");
        state.advance(AgentState.Phase.FAILED);
        return new AgentResult(state, false);
    }

    private static int increment(Map<String, Integer> counts, String key) {
        int value = counts.containsKey(key) ? counts.get(key) : 0;
        value++;
        counts.put(key, value);
        return value;
    }
}
