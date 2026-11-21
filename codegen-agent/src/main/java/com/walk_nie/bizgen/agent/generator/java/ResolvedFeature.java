package com.walk_nie.bizgen.agent.generator.java;

import com.walk_nie.bizgen.agent.md.FeatureSpec;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 変数展開が完了した機能定義と設計資料。ここから先はモデルへ渡す入力が確定する。 */
public final class ResolvedFeature {

    private final FeatureSpec spec;
    private final String featureBody;
    private final List<ResolvedDoc> designs;
    private final VariableTable variables;

    public ResolvedFeature(FeatureSpec spec, String featureBody,
                           List<ResolvedDoc> designs, VariableTable variables) {
        this.spec = spec;
        this.featureBody = featureBody;
        this.designs = designs == null ? new ArrayList<ResolvedDoc>() : designs;
        this.variables = variables;
    }

    public FeatureSpec spec() {
        return spec;
    }

    public String featureBody() {
        return featureBody;
    }

    public List<ResolvedDoc> designs() {
        return Collections.unmodifiableList(designs);
    }

    public VariableTable variables() {
        return variables;
    }

    /** 設計資料を 1 つのプロンプト用テキストに結合する（レベル順は登録順で固定）。 */
    public String designText() {
        StringBuilder out = new StringBuilder();
        for (ResolvedDoc doc : designs) {
            out.append("===== [").append(doc.level().label()).append("] ")
                    .append(doc.name()).append(" =====\n")
                    .append(doc.body()).append("\n\n");
        }
        return out.toString();
    }

    public String variable(String name, String defaultValue) {
        String value = variables.get(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}
