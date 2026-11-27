package com.walk_nie.bizgen.agent.generator.flow;

import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.reuse.ReuseSelection;
import com.walk_nie.bizgen.agent.semantic.SemanticSearchIntent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「機能ブロック → API 定位／新規コード → 結合・潤色」を回すための状態。
 * 前置ステージの決定論的な状態は JavaGenState が持ち、こちらはブロック単位の進行だけを管理する。
 */
public final class FlowGenState {

    public enum Phase {
        BASE_READY,
        PLANNED,
        REUSED,
        BLOCKS_RESOLVED,
        COMBINED
    }

    private final JavaGenState java;
    private Phase phase = Phase.BASE_READY;
    private CodePlan plan;
    private final List<String> toolResolvedApis = new ArrayList<String>();
    private final List<ReuseSelection> reuseSelections = new ArrayList<ReuseSelection>();
    /** モデルがブロックごとに自ら立てた意味解析の検索意図（人工レビュー用）。 */
    private final List<SemanticSearchIntent> semanticSearches = new ArrayList<SemanticSearchIntent>();
    private int blockRounds;

    public FlowGenState(JavaGenState java) {
        this.java = java;
    }

    public JavaGenState java() {
        return java;
    }

    public Phase phase() {
        return phase;
    }

    public void assertPhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("期待フェーズ " + expected + " に対し現在 " + phase);
        }
    }

    public void advance(Phase next) {
        this.phase = next;
    }

    public CodePlan plan() {
        return plan;
    }

    public void setPlan(CodePlan plan) {
        this.plan = plan;
    }

    public void recordToolApi(String signature) {
        if (signature != null && !signature.trim().isEmpty()
                && !toolResolvedApis.contains(signature)) {
            toolResolvedApis.add(signature);
        }
    }

    public List<String> toolResolvedApis() {
        return Collections.unmodifiableList(toolResolvedApis);
    }

    /** コード側で確定した再利用テンプレート（人工レビュー用）。 */
    public void recordReuse(ReuseSelection selection) {
        if (selection != null) {
            reuseSelections.add(selection);
        }
    }

    public List<ReuseSelection> reuseSelections() {
        return Collections.unmodifiableList(reuseSelections);
    }

    /** モデルが semantic_search ツールで自ら発行した検索意図を記録する。 */
    public void recordSemanticSearch(SemanticSearchIntent intent) {
        if (intent != null) {
            semanticSearches.add(intent);
        }
    }

    public List<SemanticSearchIntent> semanticSearches() {
        return Collections.unmodifiableList(semanticSearches);
    }

    public void countBlockRound() {
        blockRounds++;
    }

    public int blockRounds() {
        return blockRounds;
    }
}
