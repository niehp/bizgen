package com.walk_nie.bizgen.agent.llm;

import com.walk_nie.bizgen.agent.config.AgentConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 互換 API を持つローカル LLM（Ollama / LM Studio / llama.cpp 等）への HTTP クライアント。
 * JDK 標準の HttpURLConnection のみを使用し、外部ライブラリに依存しない。
 * 既定ではループバックアドレス以外への接続を拒否し、データ外部漏洩を構造的に防ぐ。
 */
public final class HttpLlmClient implements LlmClient {

    private final AgentConfig config;

    public HttpLlmClient(AgentConfig config) {
        this.config = config;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        assertLocalOnly(config.endpoint());
        HttpURLConnection connection = null;
        try {
            URL url = new URL(config.endpoint());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setConnectTimeout(config.timeoutMs());
            connection.setReadTimeout(config.timeoutMs());
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            if (!config.apiKey().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + config.apiKey());
            }

            byte[] payload = request.toJson().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            String body = readBody(connection);
            if (status < 200 || status >= 300) {
                throw new LlmException("LLM が HTTP " + status + " を返しました: " + abbreviate(body));
            }
            return LlmResponse.parse(body);
        } catch (IOException e) {
            throw new LlmException("LLM への接続に失敗しました: " + config.endpoint()
                    + " (" + e.getMessage() + ")", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            stream = connection.getInputStream();
        }
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    /** 明示的なオプトインがない限り、ローカルホスト以外への送信を防ぐ。 */
    public static void assertLocalOnly(String endpoint) {
        String allowRemote = System.getProperty("agent.allowRemoteLlm", "false");
        if (Boolean.parseBoolean(allowRemote)) {
            return;
        }
        try {
            String host = new URL(endpoint).getHost().toLowerCase(java.util.Locale.ROOT);
            boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
            if (!local) {
                throw new LlmException("データ外部漏洩防止のため、ローカルホスト以外への LLM 接続は拒否されます: "
                        + endpoint + "（明示的に許可する場合は -Dagent.allowRemoteLlm=true を指定）");
            }
        } catch (IOException e) {
            throw new LlmException("LLM エンドポイント URL が不正です: " + endpoint, e);
        }
    }

    /** LLM 呼び出しに伴う失敗を表す実行時例外。 */
    public static final class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
