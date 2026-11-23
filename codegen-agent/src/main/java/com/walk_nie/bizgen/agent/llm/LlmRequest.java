package com.walk_nie.bizgen.agent.llm;

import com.walk_nie.bizgen.agent.agent.ToolCall;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.json.Json;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI 互換 /v1/chat/completions のリクエスト。function tool 定義も保持する。 */
public final class LlmRequest {

    private final String model;
    private final List<LlmMessage> messages;
    private final double temperature;
    private final int maxTokens;
    private final Integer seed;
    private final List<ToolDefinition> tools;
    private final String toolChoice;

    public LlmRequest(String model, List<LlmMessage> messages, double temperature,
                      int maxTokens, Integer seed) {
        this(model, messages, temperature, maxTokens, seed, new ArrayList<ToolDefinition>(), "auto");
    }

    public LlmRequest(String model, List<LlmMessage> messages, double temperature,
                      int maxTokens, Integer seed, List<ToolDefinition> tools) {
        this(model, messages, temperature, maxTokens, seed, tools, "auto");
    }

    public LlmRequest(String model, List<LlmMessage> messages, double temperature,
                      int maxTokens, Integer seed, List<ToolDefinition> tools, String toolChoice) {
        this.model = model;
        this.messages = messages == null ? new ArrayList<LlmMessage>() : messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.seed = seed;
        this.tools = tools == null ? new ArrayList<ToolDefinition>() : tools;
        this.toolChoice = toolChoice == null || toolChoice.trim().isEmpty() ? "auto" : toolChoice.trim();
    }

    public String model() {
        return model;
    }

    public List<LlmMessage> messages() {
        return messages;
    }

    public double temperature() {
        return temperature;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public Integer seed() {
        return seed;
    }

    public List<ToolDefinition> tools() {
        return tools;
    }

    public String toolChoice() {
        return toolChoice;
    }

    /** 実際にモデルへ渡す内容を可視化する（プレビュー / 監査用）。 */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append("model: ").append(model).append('\n');
        out.append("temperature: ").append(temperature)
                .append(" / maxTokens: ").append(maxTokens)
                .append(" / seed: ").append(seed).append('\n');
        if (!tools.isEmpty()) {
            out.append("tool_choice: ").append(toolChoice).append('\n');
            out.append("tools: ");
            for (ToolDefinition definition : tools) {
                out.append(definition.name()).append(' ');
            }
            out.append('\n');
        }
        out.append('\n');
        for (LlmMessage message : messages) {
            out.append("===== [").append(message.role().toUpperCase()).append("] =====\n");
            out.append(message.content()).append('\n');
            for (ToolCall call : message.toolCalls()) {
                out.append("  tool_call: ").append(call.describe()).append('\n');
            }
        }
        return out.toString();
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", model);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        if (seed != null) {
            root.put("seed", seed);
        }
        List<Object> serializedMessages = new ArrayList<Object>();
        for (LlmMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", message.role());
            item.put("content", StrUtil.normalizeNewline(message.content()));
            if (!message.toolCallId().isEmpty()) {
                item.put("tool_call_id", message.toolCallId());
            }
            if (message.hasToolCalls()) {
                List<Object> calls = new ArrayList<Object>();
                for (ToolCall call : message.toolCalls()) {
                    Map<String, Object> function = new LinkedHashMap<String, Object>();
                    function.put("name", call.name());
                    function.put("arguments", Json.stringify(call.arguments()));
                    Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
                    wrapper.put("id", call.id());
                    wrapper.put("type", "function");
                    wrapper.put("function", function);
                    calls.add(wrapper);
                }
                item.put("tool_calls", calls);
            }
            serializedMessages.add(item);
        }
        root.put("messages", serializedMessages);
        if (!tools.isEmpty()) {
            List<Object> serializedTools = new ArrayList<Object>();
            for (ToolDefinition definition : tools) {
                serializedTools.add(definition.toJsonObject());
            }
            root.put("tools", serializedTools);
            root.put("tool_choice", toolChoice);
        }
        return Json.stringify(root);
    }
}
