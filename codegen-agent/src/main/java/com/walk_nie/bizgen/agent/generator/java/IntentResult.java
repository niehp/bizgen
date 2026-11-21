package com.walk_nie.bizgen.agent.generator.java;

/** 意図分類の結果。どこから決定したかを必ず保持する（決定論の監査用）。 */
public final class IntentResult {

    public enum Source {
        /** 機能 md に明示指定があり、コード側で決定した */
        MD,
        /** モデルが分類した */
        LLM
    }

    private final JavaChangeIntent intent;
    private final String reason;
    private final Source source;

    public IntentResult(JavaChangeIntent intent, String reason, Source source) {
        this.intent = intent;
        this.reason = reason == null ? "" : reason;
        this.source = source;
    }

    public JavaChangeIntent intent() {
        return intent;
    }

    public String reason() {
        return reason;
    }

    public Source source() {
        return source;
    }
}
