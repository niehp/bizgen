package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.generator.java.SemanticMap;
import com.walk_nie.bizgen.agent.md.JavaFeatureOutline;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.NamingStyle;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 日本語の機能記述を Java 識別子へ対応付ける（モデル担当）。 */
public final class SemanticMapStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "05-semantic-mapping";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.INTENT_DECIDED);
        ResolvedFeature resolved = state.resolved();
        JavaFeatureOutline outline = JavaFeatureOutline.parse(resolved.featureBody());
        try {
            VariableTable variables = new VariableTable();
            variables.put("featureBody", resolved.featureBody());
            variables.put("designDocs", resolved.designText());
            variables.put("featureOutline", outline.isEmpty() ? "（md に Java 契約の明示なし）" : outline.toPromptText());
            String prompt = variables.resolveText(context.repository().prompt("semantic-mapping"));

            AgentConfig config = context.config();
            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);

            String json = StrUtil.extractJsonObject(response.content());
            if (json.isEmpty()) {
                throw new IllegalStateException("意味マッピングの JSON が取得できませんでした");
            }
            SemanticMap map = SemanticMap.fromJson(json);
            // md に明記されたクラス／メソッド／フィールドは模型的推測で上書きしない
            map = applyOutline(map, outline);

            // クラス名が欠落した場合のみ、md の変数から決定論的に補完する（推測はしない）
            if (StrUtil.isBlank(map.className())) {
                String candidate = resolved.variable("className", "");
                if (StrUtil.isBlank(candidate)) {
                    candidate = resolved.variable("entityName", "") + "Service";
                }
                if (StrUtil.isBlank(candidate)) {
                    throw new IllegalStateException("クラス名を決定できませんでした。md に className を指定してください。");
                }
                map = new SemanticMap(NamingStyle.UPPER_CAMEL.convert(candidate),
                        map.fields(), map.methods(), map.tables(),
                        map.notes() + "（クラス名は md の変数から補完）");
            }

            state.setSemanticMap(map);
            state.advance(JavaGenState.Phase.MAPPED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("semantic-mapping prompt の読み込みに失敗", e);
        }
    }

    /** md の契約を優先し、モデルは日本語の意味付けだけを担当させる。 */
    private SemanticMap applyOutline(SemanticMap map, JavaFeatureOutline outline) {
        String className = StrUtil.isBlank(outline.className()) ? map.className() : outline.className();
        if (!StrUtil.isBlank(className)) {
            className = NamingStyle.UPPER_CAMEL.convert(className);
        }

        List<SemanticMap.NamePair> fields = map.fields();
        if (!outline.fields().isEmpty()) {
            fields = new ArrayList<SemanticMap.NamePair>();
            for (JavaFeatureOutline.FieldDef field : outline.fields()) {
                String japanese = StrUtil.isBlank(field.comment()) ? field.name() : field.comment();
                fields.add(new SemanticMap.NamePair(japanese,
                        NamingStyle.LOWER_CAMEL.convert(field.name())));
            }
        }

        List<SemanticMap.NamePair> methods = map.methods();
        if (!outline.methods().isEmpty()) {
            methods = new ArrayList<SemanticMap.NamePair>();
            for (JavaFeatureOutline.MethodDef method : outline.methods()) {
                String japanese = StrUtil.isBlank(method.purpose()) ? method.name() : method.purpose();
                methods.add(new SemanticMap.NamePair(japanese,
                        NamingStyle.LOWER_CAMEL.convert(method.name())));
            }
        }

        String notes = map.notes();
        if (!outline.isEmpty()) {
            notes = (notes.isEmpty() ? "" : notes + " / ")
                    + "md に明記されたクラス・メソッド・フィールドを固定";
        }
        return new SemanticMap(className, fields, methods, map.tables(), notes);
    }
}
