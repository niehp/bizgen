package com.walk_nie.bizgen.agent.cli;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaGenPipeline;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.JavaWriteResult;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.generator.java.SemanticMap;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeResult;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenPipeline;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenRequest;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenResult;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.generator.sql.SqlType;
import com.walk_nie.bizgen.agent.llm.HttpLlmClient;
import com.walk_nie.bizgen.agent.md.DesignLevel;
import com.walk_nie.bizgen.agent.md.MdDoc;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.PromptRenderer;
import com.walk_nie.bizgen.agent.pipeline.StageTrace;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadDesignStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadFeatureStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ResolveVariablesStage;
import com.walk_nie.bizgen.agent.pipeline.stage.SearchExistingCodeStage;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.NamingStyle;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** コマンドライン対話インタフェース。 */
public final class Cli {

    private final AgentConfig config;
    private final MdRepository repository;
    private final PipelineContext context;
    private final PrintStream out;

    public Cli(AgentConfig config, PrintStream out) {
        this.config = config;
        this.out = out;
        this.repository = new MdRepository(config);
        this.context = new PipelineContext(config, repository,
                new VariableTable(), new HttpLlmClient(config));
    }

    public void loop() throws IOException {
        out.println("bizgen-agent を起動しました。'help' でコマンド一覧、'exit' で終了。");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            out.print("agent> ");
            out.flush();
            String line = reader.readLine();
            if (line == null) {
                out.println();
                return;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
                out.println("終了します。");
                return;
            }
            try {
                dispatch(trimmed);
            } catch (RuntimeException e) {
                out.println("[ERROR] " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
    }

    public void dispatch(String line) throws IOException {
        List<String> tokens = tokenize(line);
        String command = tokens.get(0).toLowerCase();
        List<String> args = tokens.subList(1, tokens.size());
        Map<String, String> options = parseOptions(args);

        if ("help".equals(command)) {
            printHelp();
        } else if ("config".equals(command)) {
            out.println(config.describe());
        } else if ("list".equals(command)) {
            list(options);
        } else if ("template".equals(command)) {
            template(options);
        } else if ("pipeline".equals(command)) {
            pipeline(options);
        } else if ("preview".equals(command)) {
            preview(options);
        } else if ("search".equals(command)) {
            search(options);
        } else if ("java-gen".equals(command)) {
            javaGen(options);
        } else if ("sql-gen".equals(command)) {
            sqlGen(options);
        } else {
            out.println("不明なコマンド: " + command + "（'help' を参照）");
        }
    }

    private void printHelp() {
        out.println("使用可能なコマンド:");
        out.println("  help");
        out.println("  config");
        out.println("  list java|project|feature|class|method|schema|prompt|template");
        out.println("  template java-class|table-schema [--out <file>]");
        out.println("  pipeline java|sql");
        out.println("  preview java --feature <md名>");
        out.println("  search --feature <md名>                    既存 Java の類似コード検索のみ実行（LLM 不使用）");
        out.println("  preview sql --type select|update|delete --desc <内容> --tables <日本語表名,...> [--shape <SQL雛形>]");
        out.println("  java-gen --feature <md名>");
        out.println("  sql-gen --type select|update|delete --desc <内容> --tables <日本語表名,...>");
        out.println("           [--shape <SQL雛形>] [--prompt <prompt名>] [--var key=value]... [--out <file>]");
        out.println("  exit");
        out.println("");
        out.println("※ LLM 接続情報は config/agent-config.properties に記載します（--live 引数はありません）。");
        out.println("※ 生成コードは必ず人間がレビューしてから取り込んでください。");
    }

    private void list(Map<String, String> options) throws IOException {
        String target = options.get("0");
        if (target == null) {
            for (DesignLevel level : DesignLevel.values()) {
                out.println("[" + level.label() + "] " + level.folder());
                for (MdDoc doc : repository.list(level)) {
                    out.println("  - " + doc.name() + " : " + doc.title());
                }
            }
            return;
        }
        if ("schema".equals(target)) {
            out.println(repository.schemaNames().toString());
            return;
        }
        Path dir = "prompt".equals(target) ? repository.promptDir()
                : "template".equals(target) ? repository.templateDir()
                : levelFrom(target) == null ? null : repository.levelDir(levelFrom(target));
        if (dir == null) {
            out.println("不明なレベル: " + target);
            return;
        }
        for (Path path : FileUtil.listMarkdown(dir)) {
            out.println("  - " + FileUtil.baseName(path));
        }
    }

    private DesignLevel levelFrom(String token) {
        DesignLevel level = DesignLevel.fromToken(token);
        if (level != null) {
            return level;
        }
        if ("java".equalsIgnoreCase(token)) {
            return DesignLevel.JAVA_COMMON;
        }
        if ("project".equalsIgnoreCase(token)) {
            return DesignLevel.PROJECT_COMMON;
        }
        if ("class".equalsIgnoreCase(token)) {
            return DesignLevel.JAVA_CLASS;
        }
        return null;
    }

    private void template(Map<String, String> options) throws IOException {
        String name = options.get("0");
        if (name == null) {
            name = "create-single-java-class";
        }
        String templateName = name.contains("table") ? "table-schema" : "create-single-java-class";
        String content = repository.template(templateName);
        Path path = options.containsKey("out")
                ? Paths.get(options.get("out"))
                : config.outputRoot().resolve("templates/" + templateName + ".md");
        path = path.toAbsolutePath().normalize();
        FileUtil.write(path, content);
        out.println("テンプレートを出力しました: " + path);
    }

    private void pipeline(Map<String, String> options) {
        String target = options.get("0");
        if ("sql".equalsIgnoreCase(target)) {
            out.println(new SqlGenPipeline().pipeline().stageIds().toString());
        } else {
            out.println(new JavaGenPipeline().pipeline().stageIds().toString());
        }
    }

    private void preview(Map<String, String> options) throws IOException {
        String target = options.get("0");
        if ("java".equalsIgnoreCase(target)) {
            previewJava(options.get("feature"));
        } else if ("sql".equalsIgnoreCase(target)) {
            SqlGenRequest request = buildSqlRequest(options);
            SqlGenState state = new SqlGenState(request);
            new com.walk_nie.bizgen.agent.pipeline.stage.LoadTableSchemaStage().execute(state, context);
            new com.walk_nie.bizgen.agent.pipeline.stage.RenderSqlPromptStage().execute(state, context);
            out.println(state.renderedPrompt());
        } else {
            out.println("preview java|sql を指定してください");
        }
    }

    /** LLM を使わず、類似コード検索だけを実行して結果を表示する。 */
    private void search(Map<String, String> options) throws IOException {
        String featureName = options.get("feature");
        if (StrUtil.isBlank(featureName)) {
            throw new IllegalArgumentException("--feature が必要です");
        }
        JavaGenState state = new JavaGenState(featureName);
        new LoadFeatureStage().execute(state, context);
        new LoadDesignStage().execute(state, context);
        new ResolveVariablesStage().execute(state, context);
        ResolvedFeature resolved = state.resolved();
        state.setSemanticMap(stubSemanticMap(resolved));
        state.advance(JavaGenState.Phase.MAPPED);
        new SearchExistingCodeStage().execute(state, context);

        SimilarCodeResult result = state.similarCode();
        out.println("検索ルート : " + result.root());
        out.println("キーワード : " + result.keywords());
        out.println("走査ファイル: " + result.scannedFiles() + " / 抽出: " + result.snippets().size());
        for (int i = 0; i < result.snippets().size(); i++) {
            out.println("  " + (i + 1) + ". " + result.snippets().get(i).relativePath()
                    + " (一致度 " + result.snippets().get(i).score() + ")");
        }
    }

    /** CLI プレビュー用の軽量マッパー。LLM を呼ばずに md の記述だけからキーワードを作る。 */
    private SemanticMap stubSemanticMap(ResolvedFeature resolved) {
        String className = resolved.variable("className", "");
        if (StrUtil.isBlank(className)) {
            className = NamingStyle.UPPER_CAMEL.convert(resolved.variable("entityName", "") + "Service");
        }
        List<SemanticMap.NamePair> methods = new ArrayList<SemanticMap.NamePair>();
        List<SemanticMap.NamePair> fields = new ArrayList<SemanticMap.NamePair>();
        for (String line : resolved.featureBody().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                String name = trimmed.substring(4).replace("`", "").trim();
                methods.add(new SemanticMap.NamePair(name, NamingStyle.LOWER_CAMEL.convert(name)));
            } else if (trimmed.startsWith("|") && trimmed.contains("|")) {
                String[] cells = trimmed.split("\\|");
                if (cells.length >= 3) {
                    String jp = cells[1].trim();
                    String en = cells[2].trim();
                    if (!jp.isEmpty() && !en.isEmpty() && !jp.equals("日本語名")
                            && !en.startsWith("---") && !en.equals("英語物理名")) {
                        fields.add(new SemanticMap.NamePair(jp, en));
                    }
                }
            }
        }
        return new SemanticMap(className, fields, methods,
                new ArrayList<SemanticMap.NamePair>(), "CLI プレビュー用の仮マッピング");
    }

    private void previewJava(String featureName) throws IOException {
        if (StrUtil.isBlank(featureName)) {
            throw new IllegalArgumentException("--feature が必要です");
        }
        JavaGenState state = new JavaGenState(featureName);
        new LoadFeatureStage().execute(state, context);
        new LoadDesignStage().execute(state, context);
        new ResolveVariablesStage().execute(state, context);
        ResolvedFeature resolved = state.resolved();
        state.setSemanticMap(stubSemanticMap(resolved));
        state.advance(JavaGenState.Phase.MAPPED);
        new SearchExistingCodeStage().execute(state, context);

        String className = state.semanticMap().className();
        Map<String, String> variables = new LinkedHashMap<String, String>();
        variables.put("intent", StrUtil.isBlank(resolved.spec().mode()) ? "NEW_FILE" : resolved.spec().mode());
        variables.put("targetFile", resolved.spec().targetFile());
        variables.put("existingCode", "（プレビューのため省略）");
        variables.put("packageName", resolved.variable("packageName", "com.walk_nie.bizgen.generated"));
        variables.put("className", className);
        variables.put("semanticMap", "（プレビューのため省略）");
        variables.put("featureBody", resolved.featureBody());
        variables.put("designDocs", resolved.designText());
        variables.put("similarCode", state.similarCode().promptText());
        out.println(PromptRenderer.render(repository, "java-generate", variables));
    }

    private void javaGen(Map<String, String> options) {
        String featureName = options.get("feature");
        if (StrUtil.isBlank(featureName)) {
            throw new IllegalArgumentException("--feature が必要です");
        }
        JavaGenPipeline pipeline = new JavaGenPipeline();
        JavaGenState state = pipeline.run(featureName, context);
        JavaWriteResult result = state.writeResult();
        out.println("Java 生成完了: " + result.classFile());
        if (result.packageInfoFile() != null) {
            out.println("パッケージコメント: " + result.packageInfoFile());
        }
        out.println("変更種別: " + result.intent() + "（" + state.intent().source() + "）");
        if (!result.warnings().isEmpty()) {
            out.println("[WARN] 検証警告: " + result.warnings());
        }
        if (config.reviewRequired()) {
            out.println("[REVIEW] 生成コードは人間がレビューしてから取り込んでください。");
        }
        printTraces(context);
    }

    private void sqlGen(Map<String, String> options) {
        SqlGenRequest request = buildSqlRequest(options);
        SqlGenPipeline pipeline = new SqlGenPipeline();
        SqlGenState state = pipeline.run(request, context);
        SqlGenResult result = state.result();
        out.println("SQL 生成完了: " + result.file());
        out.println("使用表: " + result.tableNames());
        out.println("----- SQL -----");
        out.println(result.sql());
        printTraces(context);
    }

    private SqlGenRequest buildSqlRequest(Map<String, String> options) {
        SqlType type = SqlType.fromToken(options.get("type"));
        if (type == null) {
            throw new IllegalArgumentException("--type select|update|delete が必要です");
        }
        String description = options.get("desc");
        if (StrUtil.isBlank(description)) {
            throw new IllegalArgumentException("--desc が必要です");
        }
        List<String> tables = StrUtil.splitCsv(options.get("tables"));
        Map<String, String> variables = new LinkedHashMap<String, String>();
        if (options.containsKey("var")) {
            String pair = options.get("var");
            int equal = pair.indexOf('=');
            if (equal > 0) {
                variables.put(pair.substring(0, equal).trim(), pair.substring(equal + 1).trim());
            }
        }
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (entry.getKey().startsWith("var.")) {
                variables.put(entry.getKey().substring(4), entry.getValue());
            }
        }
        return new SqlGenRequest(type, description, options.get("shape"), tables,
                variables, options.get("out"), options.get("prompt"));
    }

    private void printTraces(PipelineContext context) {
        out.println("----- 実行トレース -----");
        for (StageTrace trace : context.traces()) {
            out.println("  " + trace);
        }
    }

    /** 引用符付きトークンを扱う簡易トークナイザ。 */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** --key value と位置引数を 0,1,... として保持する。--var.k=v も許可。 */
    static Map<String, String> parseOptions(List<String> args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        int positional = 0;
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (token.startsWith("--")) {
                String key = token.substring(2);
                int equal = key.indexOf('=');
                if (equal > 0) {
                    options.put(key.substring(0, equal), key.substring(equal + 1));
                } else if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                    options.put(key, args.get(++i));
                } else {
                    options.put(key, "true");
                }
            } else {
                options.put(String.valueOf(positional++), token);
            }
        }
        return options;
    }
}
