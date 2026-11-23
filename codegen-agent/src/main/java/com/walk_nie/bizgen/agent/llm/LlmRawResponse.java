package com.walk_nie.bizgen.agent.llm;

/** LLM の生のレスポンスボディと解釈結果。tool 呼び出し不具合の切り分けに使う。 */
public final class LlmRawResponse {

    private final LlmResponse response;
    private final String rawBody;

    public LlmRawResponse(LlmResponse response, String rawBody) {
        this.response = response;
        this.rawBody = rawBody == null ? "" : rawBody;
    }

    public LlmResponse response() {
        return response;
    }

    public String rawBody() {
        return rawBody;
    }
}
