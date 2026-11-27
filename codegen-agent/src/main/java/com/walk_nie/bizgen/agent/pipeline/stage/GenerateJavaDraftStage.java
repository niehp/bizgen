package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaChangeIntent;
import com.walk_nie.bizgen.agent.generator.java.JavaDraft;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.md.JavaFeatureOutline;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

/** Java コード草稿を生成する（モデル担当）。生成物はまだディスクに書かない。 */
public final class GenerateJavaDraftStage implements Stage<JavaGenState, JavaGenState> {

    private static final String PACKAGE_MARKER = "===== PACKAGE_INFO =====";
    private static final String CLASS_MARKER = "===== CLASS =====";

    @Override
    public String id() {
        return "09-generate-java-draft";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.SIMILAR_CODE_FOUND);
        ResolvedFeature resolved = state.resolved();
        AgentConfig config = context.config();

        String packageName = resolvePackageName(resolved, state);
        String className = state.semanticMap().className();
        String targetFile = resolved.spec().targetFile();
        String existingCode = readExistingCode(state, context);

        try {
            VariableTable variables = new VariableTable();
            variables.put("intent", state.intent().intent().name());
            variables.put("targetFile", targetFile);
            variables.put("existingCode", existingCode.isEmpty() ? "（既存ファイルなし）" : existingCode);
            variables.put("packageName", packageName);
            variables.put("className", className);
            JavaFeatureOutline outline = JavaFeatureOutline.parse(resolved.featureBody());
            variables.put("featureOutline", outline.isEmpty()
                    ? "（md に Java 契約の明示なし）" : outline.toPromptText());
            variables.put("semanticMap", state.semanticMap().toJson());
            variables.put("featureBody", resolved.featureBody());
            variables.put("designDocs", resolved.designText());
            variables.put("similarCode", state.similarCode().promptText());
            variables.put("termsText", state.termsText());
            variables.put("apisText", state.apisText());
            variables.put("specText", state.specText());
            String prompt = variables.resolveText(context.repository().prompt("java-generate"));

            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);

            JavaDraft draft = parseDraft(response.content(), packageName,
                    resolved.variable("packageComment", resolved.spec().title()));
            state.setDraft(draft);
            state.advance(JavaGenState.Phase.DRAFTED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("java-generate prompt の読み込みに失敗", e);
        }
    }

    private String resolvePackageName(ResolvedFeature resolved, JavaGenState state) {
        String declared = resolved.variable("packageName", "");
        if (!StrUtil.isBlank(declared)) {
            return declared;
        }
        String target = resolved.spec().targetFile();
        if (!StrUtil.isBlank(target) && target.contains("/") && target.endsWith(".java")) {
            String parent = target.substring(0, target.lastIndexOf('/'));
            if (parent.contains("/") || parent.contains(".")) {
                return parent.replace('/', '.');
            }
        }
        if (state.intent().intent() == JavaChangeIntent.NEW_FILE) {
            return "com.walk_nie.bizgen.generated";
        }
        return "com.walk_nie.bizgen.generated";
    }

    private String readExistingCode(JavaGenState state, PipelineContext context) {
        JavaChangeIntent intent = state.intent().intent();
        if (intent == JavaChangeIntent.NEW_FILE || context.config().existingJavaRoot() == null) {
            return "";
        }
        String target = state.resolved().spec().targetFile();
        if (StrUtil.isBlank(target)) {
            throw new IllegalStateException("既存ファイル修正には targetFile が必要です");
        }
        Path path = context.config().existingJavaRoot().resolve(target).toAbsolutePath().normalize();
        if (!FileUtil.exists(path)) {
            throw new IllegalStateException("既存 Java ファイルが見つかりません: " + path);
        }
        try {
            return FileUtil.read(path);
        } catch (IOException e) {
            throw new IllegalStateException("既存 Java ファイルの読み込みに失敗: " + path, e);
        }
    }

    private JavaDraft parseDraft(String raw, String packageName, String defaultPackageComment) {
        return parseDraftText(raw, packageName, defaultPackageComment);
    }

    /** Agent ループの最終回答も同じ形式で解釈できるように、共通の解析処理として公開する。 */
    public static JavaDraft parseDraftText(String raw, String packageName, String defaultPackageComment) {
        String text = StrUtil.stripCodeFence(raw);
        int packageAt = text.indexOf(PACKAGE_MARKER);
        int classAt = text.indexOf(CLASS_MARKER);
        String packageInfo = "";
        String classCode;
        if (packageAt >= 0 && classAt > packageAt) {
            packageInfo = text.substring(packageAt + PACKAGE_MARKER.length(), classAt).trim();
            classCode = text.substring(classAt + CLASS_MARKER.length()).trim();
        } else {
            classCode = text.trim();
        }
        if (packageInfo.isEmpty()) {
            packageInfo = "/**\n * " + defaultPackageComment + "\n"
                    + " * 生成コードは手動レビュー後に取り込む。\n */\npackage " + packageName + ";\n";
        }
        return new JavaDraft(packageInfo, classCode, raw);
    }
}
