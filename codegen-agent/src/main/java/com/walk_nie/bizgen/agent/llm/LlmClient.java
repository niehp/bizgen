package com.walk_nie.bizgen.agent.llm;

/** LLM 呼び出しの抽象。オーケストレーション層はこのインタフェースのみに依存する。 */
public interface LlmClient {

    LlmResponse chat(LlmRequest request);
}
