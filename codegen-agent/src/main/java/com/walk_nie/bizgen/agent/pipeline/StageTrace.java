package com.walk_nie.bizgen.agent.pipeline;

/** 各ステージの実行結果トレース。 */
public final class StageTrace {

    public enum Status {
        OK, FAILED
    }

    private final String stageId;
    private final boolean llmBacked;
    private final long elapsedMs;
    private final Status status;
    private final String message;

    public StageTrace(String stageId, boolean llmBacked, long elapsedMs, Status status, String message) {
        this.stageId = stageId;
        this.llmBacked = llmBacked;
        this.elapsedMs = elapsedMs;
        this.status = status;
        this.message = message == null ? "" : message;
    }

    public String stageId() {
        return stageId;
    }

    public boolean llmBacked() {
        return llmBacked;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (%s, %dms) %s",
                status, stageId, llmBacked ? "LLM" : "CODE", elapsedMs, message);
    }
}
