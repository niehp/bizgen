package com.walk_nie.bizgen.agent.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * ステージを登録順に実行するオーケストレータ。
 *  flow 制御・パラメータ検証・状態遷移はすべてここで決定論的に行い、モデルには任せない。
 */
public final class Pipeline {

    private final String name;
    private final List<Stage<?, ?>> stages = new ArrayList<Stage<?, ?>>();

    public Pipeline(String name) {
        this.name = name;
    }

    public Pipeline add(Stage<?, ?> stage) {
        stages.add(stage);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <I, O> O run(I input, PipelineContext context) {
        Object current = input;
        for (Stage<?, ?> stage : stages) {
            Stage<Object, Object> typed = (Stage<Object, Object>) stage;
            long start = System.currentTimeMillis();
            try {
                current = typed.execute(current, context);
                long elapsed = System.currentTimeMillis() - start;
                context.trace(new StageTrace(stage.id(), stage.llmBacked(), elapsed,
                        StageTrace.Status.OK, ""));
            } catch (RuntimeException e) {
                long elapsed = System.currentTimeMillis() - start;
                context.trace(new StageTrace(stage.id(), stage.llmBacked(), elapsed,
                        StageTrace.Status.FAILED, e.getMessage() == null ? e.toString() : e.getMessage()));
                throw new PipelineException(name, stage.id(), e);
            }
        }
        return (O) current;
    }

    public String name() {
        return name;
    }

    public List<String> stageIds() {
        List<String> ids = new ArrayList<String>();
        for (Stage<?, ?> stage : stages) {
            ids.add(stage.id() + (stage.llmBacked() ? " [LLM]" : " [CODE]"));
        }
        return ids;
    }

    /** パイプライン実行失敗。失敗ステージ ID を必ず保持する。 */
    public static final class PipelineException extends RuntimeException {
        private final String pipelineName;
        private final String stageId;

        public PipelineException(String pipelineName, String stageId, Throwable cause) {
            super("パイプライン [" + pipelineName + "] のステージ [" + stageId + "] で失敗: "
                    + (cause.getMessage() == null ? cause.toString() : cause.getMessage()), cause);
            this.pipelineName = pipelineName;
            this.stageId = stageId;
        }

        public String pipelineName() {
            return pipelineName;
        }

        public String stageId() {
            return stageId;
        }
    }
}
