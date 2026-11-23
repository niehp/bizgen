package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaChangeIntent;
import com.walk_nie.bizgen.agent.generator.java.JavaDraft;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.JavaSourceWriter;
import com.walk_nie.bizgen.agent.generator.java.JavaWriteResult;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 生成結果の検証と書き出し。すべて決定論的に行い、モデルには一切任せない（決定論的）。 */
public final class ValidateWriteStage implements Stage<JavaGenState, JavaGenState> {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([\\w.]+)\\s*;");

    @Override
    public String id() {
        return "12-validate-write";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.VALIDATED);
        AgentConfig config = context.config();
        JavaDraft draft = state.draft();
        JavaChangeIntent intent = state.intent().intent();

        Path target = resolveClassPath(state, context);
        String existing = "";
        if (intent != JavaChangeIntent.NEW_FILE) {
            if (config.existingJavaRoot() == null) {
                throw new IllegalStateException("既存ファイル修正には path.existingJavaRoot の設定が必要です");
            }
            try {
                existing = FileUtil.read(target);
            } catch (IOException e) {
                throw new IllegalStateException("既存 Java ファイルの読み込みに失敗: " + target, e);
            }
        }

        String finalCode;
        try {
            finalCode = applyGeneratedCode(state, draft.classCode(), existing, intent);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("生成コードの適用に失敗: " + e.getMessage(), e);
        }

        List<String> warnings = new ArrayList<String>(JavaSourceWriter.validate(finalCode));
        Path packageInfoFile = null;
        try {
            FileUtil.write(target, finalCode);
            if (intent == JavaChangeIntent.NEW_FILE) {
                String packageName = extractPackageName(draft.packageInfo(), state);
                packageInfoFile = target.getParent().resolve("package-info.java");
                FileUtil.write(packageInfoFile, draft.packageInfo().isEmpty()
                        ? JavaSourceWriter.buildPackageInfo(packageName, state.resolved().spec().title())
                        : draft.packageInfo());
            }
        } catch (IOException e) {
            throw new IllegalStateException("生成 Java の書き出しに失敗: " + target, e);
        }

        state.setWriteResult(new JavaWriteResult(target, packageInfoFile, intent, warnings));
        state.advance(JavaGenState.Phase.WRITTEN);
        return state;
    }

    private String applyGeneratedCode(JavaGenState state, String generated, String existing,
                                      JavaChangeIntent intent) {
        if (intent == JavaChangeIntent.NEW_FILE) {
            return StrUtil.stripCodeFence(generated);
        }
        List<JavaSourceWriter.MethodBlock> methods = JavaSourceWriter.extractMethods(
                StrUtil.stripCodeFence(generated));
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("追加／修正するメソッドが生成コードから検出されませんでした");
        }
        if (intent == JavaChangeIntent.ADD_METHOD) {
            StringBuilder block = new StringBuilder();
            for (JavaSourceWriter.MethodBlock method : methods) {
                if (block.length() > 0) {
                    block.append("\n\n");
                }
                block.append(method.text());
            }
            return JavaSourceWriter.insertMethods(existing, block.toString());
        }
        String methodName = state.resolved().spec().targetMethod();
        if (StrUtil.isBlank(methodName)) {
            throw new IllegalArgumentException("MODIFY_METHOD には targetMethod が必要です");
        }
        String replacement = null;
        for (JavaSourceWriter.MethodBlock method : methods) {
            if (method.name().equals(methodName)) {
                replacement = method.text();
                break;
            }
        }
        if (replacement == null) {
            replacement = methods.get(0).text();
        }
        return JavaSourceWriter.replaceMethod(existing, methodName, replacement);
    }

    private Path resolveClassPath(JavaGenState state, PipelineContext context) {
        AgentConfig config = context.config();
        JavaChangeIntent intent = state.intent().intent();
        if (intent != JavaChangeIntent.NEW_FILE) {
            String target = state.resolved().spec().targetFile();
            if (StrUtil.isBlank(target)) {
                throw new IllegalStateException("既存ファイル修正には targetFile が必要です");
            }
            return config.existingJavaRoot().resolve(target).toAbsolutePath().normalize();
        }
        // 保存先は機能 md ではなく設定ファイルの path.generatedJavaRoot のみで決める。
        String packageName = extractPackageName(state.draft().packageInfo(), state);
        String className = state.semanticMap().className();
        return config.generatedJavaRoot().resolve(FileUtil.packageToPath(packageName, className))
                .toAbsolutePath().normalize();
    }

    private String extractPackageName(String packageInfo, JavaGenState state) {
        Matcher matcher = PACKAGE_PATTERN.matcher(packageInfo == null ? "" : packageInfo);
        if (matcher.find()) {
            return matcher.group(1);
        }
        String declared = state.resolved().variable("packageName", "");
        return StrUtil.isBlank(declared) ? "com.walk_nie.bizgen.generated" : declared;
    }
}
