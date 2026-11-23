package com.walk_nie.bizgen.agent.pipeline.stage.feature;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.feature.FeatureGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Step 5: パッケージ構造に従って UTF-8 でファイルを書き出す（決定論的）。 */
public final class WriteFeatureJavaStage implements Stage<FeatureGenState, FeatureGenState> {

    private static final Pattern PACKAGE = Pattern.compile("package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS = Pattern.compile(
            "(?:public\\s+)?(?:final\\s+)?class\\s+(\\w+)");

    @Override
    public String id() {
        return "05-write-file";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public FeatureGenState execute(FeatureGenState state, PipelineContext context) {
        state.assertPhase(FeatureGenState.Phase.VALIDATED);
        AgentConfig config = context.config();
        String packageName = extract(PACKAGE, state.code(), state.packageName());
        String className = extract(CLASS, state.code(), state.className());

        Path root = config.featureOutputRoot().toAbsolutePath().normalize();
        Path target = root.resolve(FileUtil.packageToPath(packageName, className))
                .toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("出力先が指定ディレクトリの外です: " + target);
        }
        try {
            FileUtil.write(target, state.code());
        } catch (IOException e) {
            throw new IllegalStateException("生成 Java の書き出しに失敗: " + target, e);
        }
        state.setTargetPath(target);
        state.advance(FeatureGenState.Phase.WRITTEN);
        return state;
    }

    private static String extract(Pattern pattern, String code, String fallback) {
        Matcher matcher = pattern.matcher(code == null ? "" : code);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return StrUtil.isBlank(fallback) ? "com.walk_nie.bizgen.generated" : fallback;
    }
}
