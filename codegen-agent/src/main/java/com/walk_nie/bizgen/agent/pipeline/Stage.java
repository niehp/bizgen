package com.walk_nie.bizgen.agent.pipeline;

/**
 * 1 処理単位。入出力型をジェネリクスで明示する。
 * llmBacked() が true のときだけモデルを呼び、それ以外はすべて決定論的な Java 処理とする。
 */
public interface Stage<I, O> {

    String id();

    boolean llmBacked();

    O execute(I input, PipelineContext context);
}
