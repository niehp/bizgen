package com.walk_nie.bizgen.agent.generator.feature;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.md.FeatureSpec;
import com.walk_nie.bizgen.agent.term.TermDictionary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 5 ステップ Feature Pipeline の状態。フェーズ遷移はコード側で強制し、
 * モデルが書き換えられるのは rawResponse / code だけ（それもステージ経由）。
 */
public final class FeatureGenState {

    public enum Phase {
        INIT,
        TERMS_EXTRACTED,
        APIS_MATCHED,
        GENERATED,
        VALIDATED,
        WRITTEN
    }

    private final String featureName;
    private Phase phase = Phase.INIT;

    private FeatureSpec spec;
    private String featureBody;
    private String designText;
    private String packageName;
    private String className;
    private List<String> businessWords = new ArrayList<String>();
    private List<TermDictionary.TermEntry> terms = new ArrayList<TermDictionary.TermEntry>();
    private List<ApiCatalog.ApiEntry> apis = new ArrayList<ApiCatalog.ApiEntry>();
    private String rawResponse;
    private String code;
    private List<String> violations = new ArrayList<String>();
    private boolean corrected;
    private Path targetPath;

    public FeatureGenState(String featureName) {
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

    public String featureBody() {
        return featureBody;
    }

    public void setFeatureBody(String featureBody) {
        this.featureBody = featureBody;
    }

    public String designText() {
        return designText;
    }

    public void setDesignText(String designText) {
        this.designText = designText;
    }

    public String packageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String className() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public List<String> businessWords() {
        return businessWords;
    }

    public void setBusinessWords(List<String> businessWords) {
        this.businessWords = businessWords;
    }

    public List<TermDictionary.TermEntry> terms() {
        return terms;
    }

    public void setTerms(List<TermDictionary.TermEntry> terms) {
        this.terms = terms;
    }

    public List<ApiCatalog.ApiEntry> apis() {
        return apis;
    }

    public void setApis(List<ApiCatalog.ApiEntry> apis) {
        this.apis = apis;
    }

    public String rawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public String code() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public List<String> violations() {
        return violations;
    }

    public void setViolations(List<String> violations) {
        this.violations = violations;
    }

    public boolean corrected() {
        return corrected;
    }

    public void setCorrected(boolean corrected) {
        this.corrected = corrected;
    }

    public Path targetPath() {
        return targetPath;
    }

    public void setTargetPath(Path targetPath) {
        this.targetPath = targetPath;
    }
}
