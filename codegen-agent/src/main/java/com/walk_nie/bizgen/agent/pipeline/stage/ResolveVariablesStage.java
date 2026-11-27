package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedDoc;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.md.FrontMatterResolver;
import com.walk_nie.bizgen.agent.md.MdDoc;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 機能 md・設計 md 内の {{変数}} をモデルへ渡す前にすべて展開する（決定論的）。 */
public final class ResolveVariablesStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "03-resolve-variables";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.DESIGNS_LOADED);

        VariableTable variables = new VariableTable();
        variables.putAll(state.spec().variables());
        List<MdDoc> resolvedDesigns = new ArrayList<MdDoc>();
        for (MdDoc doc : state.designs()) {
            // 設計 md の front matter も機能側の変数を使って定義順に解決する。
            MdDoc resolved = FrontMatterResolver.resolveDoc(doc, variables.snapshot());
            resolvedDesigns.add(resolved);
            for (Map.Entry<String, Object> entry : resolved.frontMatter().entrySet()) {
                String key = entry.getKey();
                if ("designs".equalsIgnoreCase(key) || "designrefs".equalsIgnoreCase(key)
                        || "mode".equalsIgnoreCase(key) || "title".equalsIgnoreCase(key)) {
                    continue;
                }
                variables.put(key, entry.getValue());
            }
        }
        // パイプライン共有の変数もマージする（CLI から渡した値を最優先しない: md を信頼するため）
        for (Map.Entry<String, String> entry : context.variables().snapshot().entrySet()) {
            if (!variables.contains(entry.getKey())) {
                variables.put(entry.getKey(), entry.getValue());
            }
        }

        String featureBody = variables.resolveText(state.spec().body());
        List<ResolvedDoc> resolvedDocs = new ArrayList<ResolvedDoc>();
        for (MdDoc doc : resolvedDesigns) {
            resolvedDocs.add(new ResolvedDoc(doc.level(), doc.name(), variables.resolveText(doc.body())));
        }

        state.setResolved(new ResolvedFeature(state.spec(), featureBody, resolvedDocs, variables));
        state.advance(JavaGenState.Phase.VARIABLES_RESOLVED);
        return state;
    }
}
