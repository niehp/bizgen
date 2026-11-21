package com.walk_nie.bizgen.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 极薄 vLLM 客户端：只做一件事——把 JSON Schema 塞进 extra_body.guided_json，
 * 取回 content 文本。没有工具循环、没有路由、没有记忆，路由权 100% 在 Java 侧。
 *
 * vLLM 启动需带 --guided-decoding-backend xgrammar（0.19.x 起 guided_json 还需 pip install lark）。
 */
public final class VllmClient {

    private final String endpoint;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public VllmClient(String baseUrl, String model) {
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        this.model = model;
    }

    /** 返回模型输出的 JSON 文本，由调用方还原成 record。 */
    public String guidedJson(String system, String user, String jsonSchema) throws IOException, InterruptedException {
        String body = buildBody(system, user, jsonSchema);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("vLLM 返回 " + response.statusCode() + ": " + response.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) Json.parse(response.body());
        @SuppressWarnings("unchecked")
        java.util.List<Object> choices = (java.util.List<Object>) root.get("choices");
        if (choices == null || choices.isEmpty()) throw new IOException("vLLM 响应无 choices");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) choices.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) first.get("message");
        Object content = message == null ? null : message.get("content");
        if (content == null) throw new IOException("vLLM 响应无 message.content");
        return String.valueOf(content);
    }

    private String buildBody(String system, String user, String jsonSchema) {
        StringBuilder sb = new StringBuilder();
        sb.append('{')
          .append("\"model\":").append(Json.quote(model)).append(',')
          .append("\"temperature\":0.0,")
          .append("\"top_p\":0.5,")
          .append("\"max_tokens\":1024,")
          .append("\"messages\":[")
          .append("{\"role\":\"system\",\"content\":").append(Json.quote(system)).append("},")
          .append("{\"role\":\"user\",\"content\":").append(Json.quote(user)).append("}],")
          // 关键：schema 作为对象嵌入 extra_body.guided_json，XGrammar 在解码期强制结构合法
          .append("\"extra_body\":{\"guided_json\":").append(jsonSchema).append("}")
          .append('}');
        return sb.toString();
    }
}
