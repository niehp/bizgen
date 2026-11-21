package com.walk_nie.bizgen.agent.llm;

import com.walk_nie.bizgen.agent.json.Json;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI 互換 /v1/chat/completions のリクエスト。 */
public final class LlmRequest {

    private final String model;
    private final List<LlmMessage> messages;
    private final double temperature;
    private final int maxTokens;
    private final Integer seed;

    public LlmRequest(String model, List<LlmMessage> messages, double temperature,
                      int maxTokens, Integer seed) {
        this.model = model;
        this.messages = messages == null ? new ArrayList<LlmMessage>() : messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.seed = seed;
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

    /** 実際にモデルへ渡す内容を可視化する（プレビュー / 監査用）。 */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append("model: ").append(model).append('\n');
        out.append("temperature: ").append(temperature)
                .append(" / maxTokens: ").append(maxTokens)
                .append(" / seed: ").append(seed).append("\n\n");
        for (LlmMessage message : messages) {
            out.append("===== [").append(message.role().toUpperCase()).append("] =====\n");
            out.append(message.content()).append('\n');
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
            serializedMessages.add(item);
        }
        root.put("messages", serializedMessages);
        return Json.stringify(root);
    }
}
