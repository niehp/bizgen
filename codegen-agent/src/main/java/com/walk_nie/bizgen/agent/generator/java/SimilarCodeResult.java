package com.walk_nie.bizgen.agent.generator.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 類似コード検索の結果。prompt にそのまま差し込めるテキストを保持する。 */
public final class SimilarCodeResult {

    private final Path root;
    private final List<SimilarCodeSnippet> snippets;
    private final List<String> keywords;
    private final int scannedFiles;
    private final String promptText;

    public SimilarCodeResult(Path root, List<SimilarCodeSnippet> snippets, List<String> keywords,
                             int scannedFiles, String promptText) {
        this.root = root;
        this.snippets = snippets == null ? new ArrayList<SimilarCodeSnippet>() : snippets;
        this.keywords = keywords == null ? new ArrayList<String>() : keywords;
        this.scannedFiles = scannedFiles;
        this.promptText = promptText == null ? "" : promptText;
    }

    public Path root() {
        return root;
    }

    public List<SimilarCodeSnippet> snippets() {
        return Collections.unmodifiableList(snippets);
    }

    public List<String> keywords() {
        return Collections.unmodifiableList(keywords);
    }

    public int scannedFiles() {
        return scannedFiles;
    }

    public String promptText() {
        return promptText;
    }
}
