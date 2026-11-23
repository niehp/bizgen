package com.walk_nie.bizgen.agent.pipeline.stage.feature;

import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.feature.FeatureGenState;
import com.walk_nie.bizgen.agent.md.FeatureSpec;
import com.walk_nie.bizgen.agent.md.MdDoc;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Step 1: 機能 md を読み、业务词汇を抽出して术语库から日英マッピングを引く（決定論的）。 */
public final class LoadFeatureAndTermsStage implements Stage<FeatureGenState, FeatureGenState> {

    private static final Pattern PACKAGE_IN_BODY = Pattern.compile("- パッケージ名:\\s*`([^`]+)`");
    private static final Pattern CLASS_IN_BODY = Pattern.compile("- クラス名:\\s*`([^`]+)`");

    @Override
    public String id() {
        return "01-extract-terms";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public FeatureGenState execute(FeatureGenState state, PipelineContext context) {
        state.assertPhase(FeatureGenState.Phase.INIT);
        try {
            FeatureSpec spec = context.repository().loadFeature(state.featureName());
            VariableTable variables = new VariableTable();
            variables.putAll(spec.variables());
            variables.put("featureName", state.featureName());
            variables.put("title", spec.title());
            String body = variables.resolveText(spec.body());

            state.setSpec(spec);
            state.setFeatureBody(body);
            state.setPackageName(resolvePackage(spec, body));
            state.setClassName(resolveClass(spec, body));

            CodegenSupport.TermAnalysis analysis = CodegenSupport.analyzeTerms(
                    body, context.config());
            state.setBusinessWords(analysis.words());
            state.setTerms(analysis.entries());

            List<MdDoc> designs = context.repository().loadDesigns(spec.designRefs());
            StringBuilder designText = new StringBuilder();
            for (MdDoc doc : designs) {
                designText.append("## ").append(doc.name()).append('\n')
                        .append(doc.body()).append("\n\n");
            }
            state.setDesignText(designText.toString());

            state.advance(FeatureGenState.Phase.TERMS_EXTRACTED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("機能 md / 术语库 の読み込みに失敗: "
                    + state.featureName(), e);
        }
    }

    private String resolvePackage(FeatureSpec spec, String body) {
        String declared = spec.variables().get("packageName");
        if (!StrUtil.isBlank(declared)) {
            return declared;
        }
        Matcher matcher = PACKAGE_IN_BODY.matcher(body);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        String target = spec.targetFile();
        if (!StrUtil.isBlank(target) && target.contains("/") && target.endsWith(".java")) {
            return target.substring(0, target.lastIndexOf('/')).replace('/', '.');
        }
        return "com.walk_nie.bizgen.generated";
    }

    private String resolveClass(FeatureSpec spec, String body) {
        String declared = spec.variables().get("className");
        if (!StrUtil.isBlank(declared)) {
            return declared;
        }
        Matcher matcher = CLASS_IN_BODY.matcher(body);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        String target = spec.targetFile();
        if (!StrUtil.isBlank(target) && target.endsWith(".java")) {
            String name = target.substring(target.lastIndexOf('/') + 1);
            return name.substring(0, name.length() - ".java".length());
        }
        return "GeneratedService";
    }
}
