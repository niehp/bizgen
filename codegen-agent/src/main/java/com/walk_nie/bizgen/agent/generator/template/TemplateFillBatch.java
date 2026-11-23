package com.walk_nie.bizgen.agent.generator.template;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.md.FeatureSpec;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.FilledTextExtractor;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * テンプレートをまとめて LLM に渡し、填充後のファイルを元の相対ディレクトリ構造で書き出す。
 * ファイル列挙・パス決定・書き出しはすべてコード側で行い、モデルには 1 ファイルずつ渡す。
 */
public final class TemplateFillBatch {

    public static final class Result {
        private final String relativePath;
        private final Path outputPath;
        private final List<String> placeholders;
        private final List<String> remaining;

        public Result(String relativePath, Path outputPath,
                      List<String> placeholders, List<String> remaining) {
            this.relativePath = relativePath;
            this.outputPath = outputPath;
            this.placeholders = Collections.unmodifiableList(new ArrayList<String>(placeholders));
            this.remaining = Collections.unmodifiableList(new ArrayList<String>(remaining));
        }

        public String relativePath() {
            return relativePath;
        }

        public Path outputPath() {
            return outputPath;
        }

        public List<String> placeholders() {
            return placeholders;
        }

        public List<String> remaining() {
            return remaining;
        }
    }

    public List<Result> run(String featureName, Map<String, String> extraVariables,
                            PipelineContext context) throws IOException {
        AgentConfig config = context.config();
        Path root = config.templateFillRoot();
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalStateException("テンプレート入力ディレクトリが未設定、または存在しません: "
                    + (root == null ? "(未設定)" : root));
        }
        root = root.toAbsolutePath().normalize();
        Path outputRoot = config.templateFillOutputRoot().toAbsolutePath().normalize();
        if (outputRoot.startsWith(root)) {
            throw new IllegalStateException("出力ディレクトリは入力ディレクトリ配下に置けません: " + outputRoot);
        }

        List<Path> files = collectFiles(root, config.templateFillMaxFiles());
        String contextText = buildContext(featureName, extraVariables, context);

        List<Result> results = new ArrayList<Result>();
        for (Path file : files) {
            String relative = toRelative(root, file);
            String template = FileUtil.read(file);
            List<String> placeholders = VariableTable.placeholders(template);

            VariableTable variables = new VariableTable();
            if (extraVariables != null) {
                variables.putAll(extraVariables);
            }
            variables.put("templatePath", relative);
            variables.put("templateContent", template);
            variables.put("placeholdersText", formatPlaceholders(placeholders));
            variables.put("context", contextText);
            variables.put("featureName", StrUtil.trimToEmpty(featureName));
            String prompt = variables.resolveText(context.repository().prompt(config.templateFillPrompt()));

            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);
            String filled = FilledTextExtractor.extract(response.content());
            if (StrUtil.isBlank(filled)) {
                throw new IllegalStateException("テンプレートの填充結果が空です: " + relative);
            }

            List<String> remaining = VariableTable.placeholders(filled);
            Path target = outputRoot.resolve(relative).normalize();
            if (!target.startsWith(outputRoot)) {
                throw new IllegalStateException("出力先が指定ディレクトリの外です: " + target);
            }
            FileUtil.write(target, filled);
            results.add(new Result(relative, target, placeholders, remaining));
        }
        return results;
    }

    private static List<Path> collectFiles(Path root, int limit) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.toString().compareTo(right.toString());
            }
        });
        if (files.size() > limit) {
            files = new ArrayList<Path>(files.subList(0, Math.max(0, limit)));
        }
        return files;
    }

    private static String toRelative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String formatPlaceholders(List<String> placeholders) {
        if (placeholders.isEmpty()) {
            return "（このファイルにプレースホルダはありません。元の内容をそのまま返してください。）\n";
        }
        StringBuilder out = new StringBuilder();
        for (String placeholder : placeholders) {
            out.append("- {{").append(placeholder).append("}}\n");
        }
        return out.toString();
    }

    private static String buildContext(String featureName, Map<String, String> extraVariables,
                                       PipelineContext context) throws IOException {
        StringBuilder out = new StringBuilder();
        if (!StrUtil.isBlank(featureName)) {
            FeatureSpec spec = context.repository().loadFeature(featureName);
            VariableTable variables = new VariableTable();
            variables.putAll(spec.variables());
            variables.put("featureName", featureName);
            variables.put("title", spec.title());
            out.append("## 機能描写\n\n").append(variables.resolveText(spec.body())).append("\n\n");
        }
        if (extraVariables != null && !extraVariables.isEmpty()) {
            out.append("## 追加変数\n\n");
            for (Map.Entry<String, String> entry : new LinkedHashMap<String, String>(extraVariables).entrySet()) {
                out.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        if (out.length() == 0) {
            out.append("（参照情報なし。テンプレートの文脈から妥当な内容を埋めてください。）\n");
        }
        return out.toString();
    }
}
