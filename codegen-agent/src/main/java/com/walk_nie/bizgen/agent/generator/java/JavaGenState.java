package com.walk_nie.bizgen.agent.generator.java;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.md.FeatureSpec;
import com.walk_nie.bizgen.agent.md.MdDoc;
import com.walk_nie.bizgen.agent.term.TermDictionary;

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
        TERMS_EXTRACTED,
        APIS_MATCHED,
        INTENT_DECIDED,
        MAPPED,
        SIMILAR_CODE_FOUND,
        DRAFTED,
        POLISHED,
        VALIDATED,
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
    private List<TermDictionary.TermEntry> terms = new ArrayList<TermDictionary.TermEntry>();
    private String termsText = "";
    private List<ApiCatalog.ApiEntry> apis = new ArrayList<ApiCatalog.ApiEntry>();
    private String apisText = "";
    private String specText = "";
    private com.walk_nie.bizgen.agent.spec.SpecCatalog specCatalog;
    private JavaDraft draft;
    private List<String> violations = new ArrayList<String>();
    private boolean selfCorrected;
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

    public List<TermDictionary.TermEntry> terms() {
        return terms;
    }

    public void setTerms(List<TermDictionary.TermEntry> terms) {
        this.terms = terms == null ? new ArrayList<TermDictionary.TermEntry>() : terms;
    }

    public String termsText() {
        return termsText;
    }

    public void setTermsText(String termsText) {
        this.termsText = termsText == null ? "" : termsText;
    }

    public List<ApiCatalog.ApiEntry> apis() {
        return apis;
    }

    public void setApis(List<ApiCatalog.ApiEntry> apis) {
        this.apis = apis == null ? new ArrayList<ApiCatalog.ApiEntry>() : apis;
    }

    public String apisText() {
        return apisText;
    }

    public void setApisText(String apisText) {
        this.apisText = apisText == null ? "" : apisText;
    }

    /** 展開済みの spec 資料（api/dict/template/example）を 1 つの prompt Text として返す。 */
    public String specText() {
        return specText;
    }

    public void setSpecText(String specText) {
        this.specText = specText == null ? "" : specText;
    }

    public com.walk_nie.bizgen.agent.spec.SpecCatalog specCatalog() {
        return specCatalog;
    }

    public void setSpecCatalog(com.walk_nie.bizgen.agent.spec.SpecCatalog specCatalog) {
        this.specCatalog = specCatalog;
    }

    public JavaDraft draft() {
        return draft;
    }

    public void setDraft(JavaDraft draft) {
        this.draft = draft;
    }

    public List<String> violations() {
        return violations;
    }

    public void setViolations(List<String> violations) {
        this.violations = violations == null ? new ArrayList<String>() : violations;
    }

    public boolean selfCorrected() {
        return selfCorrected;
    }

    public void setSelfCorrected(boolean selfCorrected) {
        this.selfCorrected = selfCorrected;
    }

    public JavaWriteResult writeResult() {
        return writeResult;
    }

    public void setWriteResult(JavaWriteResult writeResult) {
        this.writeResult = writeResult;
    }
}
