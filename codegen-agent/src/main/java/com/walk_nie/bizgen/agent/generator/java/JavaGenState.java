package com.walk_nie.bizgen.agent.generator.java;

import com.walk_nie.bizgen.agent.md.FeatureSpec;
import com.walk_nie.bizgen.agent.md.MdDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 生成パイプラインの状態。フェーズを明示的に保持し、不正な遷移はコード側で即座に拒否する。
 * モデルが書き換えられるのは intent / semanticMap / draft のみで、それ以外はすべて決定論的に埋まる。
 */
public final class JavaGenState {

    public enum Phase {
        INIT,
        FEATURE_LOADED,
        DESIGNS_LOADED,
        VARIABLES_RESOLVED,
        INTENT_DECIDED,
        MAPPED,
        SIMILAR_CODE_FOUND,
        DRAFTED,
        POLISHED,
        WRITTEN
    }

    private final String featureName;
    private Phase phase = Phase.INIT;

    private FeatureSpec spec;
    private List<MdDoc> designs = new ArrayList<MdDoc>();
    private ResolvedFeature resolved;
    private IntentResult intent;
    private SemanticMap semanticMap;
    private SimilarCodeResult similarCode;
    private JavaDraft draft;
    private JavaWriteResult writeResult;

    public JavaGenState(String featureName) {
        this.featureName = featureName;
    }

    public String featureName() {
        return featureName;
    }

    public Phase phase() {
        return phase;
    }

    public void assertPhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("期待フェーズ " + expected + " に対し現在 " + phase
                    + " です。ステージ順が不正です。");
        }
    }

    public void advance(Phase next) {
        this.phase = next;
    }

    public FeatureSpec spec() {
        return spec;
    }

    public void setSpec(FeatureSpec spec) {
        this.spec = spec;
    }

    public List<MdDoc> designs() {
        return designs;
    }

    public void setDesigns(List<MdDoc> designs) {
        this.designs = designs;
    }

    public ResolvedFeature resolved() {
        return resolved;
    }

    public void setResolved(ResolvedFeature resolved) {
        this.resolved = resolved;
    }

    public IntentResult intent() {
        return intent;
    }

    public void setIntent(IntentResult intent) {
        this.intent = intent;
    }

    public SemanticMap semanticMap() {
        return semanticMap;
    }

    public void setSemanticMap(SemanticMap semanticMap) {
        this.semanticMap = semanticMap;
    }

    public SimilarCodeResult similarCode() {
        return similarCode;
    }

    public void setSimilarCode(SimilarCodeResult similarCode) {
        this.similarCode = similarCode;
    }

    public JavaDraft draft() {
        return draft;
    }

    public void setDraft(JavaDraft draft) {
        this.draft = draft;
    }

    public JavaWriteResult writeResult() {
        return writeResult;
    }

    public void setWriteResult(JavaWriteResult writeResult) {
        this.writeResult = writeResult;
    }
}
