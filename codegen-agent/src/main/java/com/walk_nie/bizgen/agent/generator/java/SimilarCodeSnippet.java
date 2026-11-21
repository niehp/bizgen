package com.walk_nie.bizgen.agent.generator.java;

import java.nio.file.Path;

/** 類似コード 1 件分。スコアと相対パスを必ず保持し、根拠を追跡できるようにする。 */
public final class SimilarCodeSnippet {

    private final Path path;
    private final String relativePath;
    private final int score;
    private final String content;

    public SimilarCodeSnippet(Path path, String relativePath, int score, String content) {
        this.path = path;
        this.relativePath = relativePath;
        this.score = score;
        this.content = content;
    }

    public Path path() {
        return path;
    }

    public String relativePath() {
        return relativePath;
    }

    public int score() {
        return score;
    }

    public String content() {
        return content;
    }
}
