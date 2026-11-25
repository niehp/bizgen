package com.walk_nie.bizgen.agent.cli;

import com.walk_nie.bizgen.agent.agent.AgentResult;
import com.walk_nie.bizgen.agent.agent.AgentRunner;
import com.walk_nie.bizgen.agent.agent.ToolCall;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.feature.FeatureCodegenPipeline;
import com.walk_nie.bizgen.agent.generator.feature.FeatureGenState;
import com.walk_nie.bizgen.agent.generator.java.JavaGenPipeline;
import com.walk_nie.bizgen.agent.generator.template.TemplateFillBatch;
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
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRawResponse;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.llm.ToolCallExtractor;
import com.walk_nie.bizgen.agent.md.DesignLevel;
import com.walk_nie.bizgen.agent.md.MdDoc;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.PromptRenderer;
import com.walk_nie.bizgen.agent.pipeline.StageTrace;
import com.walk_nie.bizgen.agent.pipeline.stage.ExtractTermsStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadDesignStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadFeatureStage;
import com.walk_nie.bizgen.agent.pipeline.stage.MatchApiCatalogStage;
import com.walk_nie.bizgen.agent.term.TermDictionary;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** コマンドライン対話インタフェース。 */
public final class Cli {

    private final AgentConfig config;
    private final MdRepository repository;
    private final PipelineContext context;
    private final PrintStream out;

    private AgentRunner agentRunner;
    private AgentResult lastAgentResult;

    public Cli(AgentConfig config, PrintStream out) {
        this.config = config;
        this.out = out;
        this.repository = new MdRepository(config);
        this.context = new PipelineContext(config, repository,
                new VariableTable(), new HttpLlmClient(config));
    }

    private AgentRunner runner() {
        if (agentRunner == null) {
            agentRunner = new AgentRunner(context);
        }
        return agentRunner;
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
        } else if ("llm-probe".equals(command)) {
            llmProbe();
        } else if ("list".equals(command)) {
            list(options);
        } else if ("template".equals(command)) {
            template(options);
        } else if ("template-fill".equals(command)) {
            templateFill(options);
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
        } else if ("tools".equals(command)) {
            tools();
        } else if ("terms".equals(command)) {
            terms();
        } else if ("api-list".equals(command)) {
            apiList();
        } else if ("spec-list".equals(command)) {
            specList();
        } else if ("feature-gen".equals(command)) {
            featureGen(options);
        } else if ("flow-gen".equals(command)) {
            flowGen(options);
        } else if ("agent-gen".equals(command)) {
            agentGen(options);
        } else if ("agent-sql".equals(command)) {
            agentSql(options);
        } else if ("agent-trace".equals(command)) {
            agentTrace();
        } else if ("java-index".equals(command)) {
            javaIndex(options);
        } else if ("java-files".equals(command)) {
            javaFiles(options);
        } else if ("java-search".equals(command)) {
            javaSearch(options);
        } else if ("java-class".equals(command)) {
            javaClass(options);
        } else if ("java-method".equals(command)) {
            javaMethod(options);
        } else if ("java-refs".equals(command)) {
            javaRefs(options);
        } else if ("java-similar".equals(command)) {
            javaSimilar(options);
        } else if ("java-rules".equals(command)) {
            javaRules();
        } else if ("propose-edit".equals(command)) {
            proposeEdit(options);
        } else if ("diff".equals(command)) {
            showDiff(options);
        } else if ("approve-edit".equals(command)) {
            approveEdit(options);
        } else {
            out.println("不明なコマンド: " + command + "（'help' を参照）");
        }
    }

    private void printHelp() {
        out.println("使用可能なコマンド:");
        out.println("  help");
        out.println("  config");
        out.println("  llm-probe                                 実際に tool 付きで 1 回呼び、生レスポンスを表示");
        out.println("  list java|project|usecase|class|method|schema|prompt|template");
        out.println("  template java-class|table-schema [--out <file>]");
        out.println("  template-fill [--feature <md名>] [--var key=value] [--var.key=value]...");
        out.println("               入力ディレクトリ配下の全テンプレートを填充し、相対パスを維持して出力");
        out.println("  pipeline java|sql|feature|flow");
        out.println("  preview java --feature <md名>");
        out.println("  preview sql --type select|update|delete --desc <内容> --tables <日本語表名,...> [--shape <SQL雛形>]");
        out.println("  preview agent [--feature <md名>]                 エージェントのツールと system prompt を表示");
        out.println("  search --feature <md名>                    既存 Java の類似コード検索のみ実行（LLM 不使用）");
        out.println("  java-gen --feature <md名>                 12 ステージの Java 生成フレームワークで生成");
        out.println("           （术语抽出 / API 匹配 / 静的検証 + 1 回自己修正 を含む）");
        out.println("  sql-gen --type select|update|delete --desc <内容> --tables <日本語表名,...>");
        out.println("           [--shape <SQL雛形>] [--prompt <prompt名>] [--var key=value]... [--out <file>]");
        out.println("  tools                                     登録済み function tool 一覧（LLM 不使用）");
        out.println("  terms                                     术语库（日本語 -> 英語）一覧（LLM 不使用）");
        out.println("  api-list                                  API 库の登録済みメソッド一覧（LLM 不使用）");
        out.println("  spec-list                                 spec/api・dict・template・example の資料一覧（LLM 不使用）");
        out.println("  feature-gen --feature <md名>              5 ステップ Pipeline で Java を生成・落盤");
        out.println("  flow-gen --feature <md名>                 6 ステップ: 機能ブロック分解 → API は tool 定位 /");
        out.println("                                            それ以外は新規生成 → ループ → 結合・潤色 → 落盤");
        out.println("  agent-gen --feature <md名>                 マルチターン Agent + tool 呼び出しで Java 生成");
        out.println("  agent-sql --type select|update|delete --desc <内容> --tables <日本語表名,...>");
        out.println("  agent-trace                               直近の Agent ターントレースを表示");
        out.println("");
        out.println("--- Java コード知能（JDT AST + SQLite） ---");
        out.println("  java-index build|refresh|stats            Java 索引の構築・差分更新・統計");
        out.println("  java-files [--prefix <文字列>]            索引済み Java ファイル一覧");
        out.println("  java-search --query <キーワード>          横断キーワード検索");
        out.println("  java-class --class <クラス名>             クラス構造（import/フィールド/メソッド）");
        out.println("  java-method --query <文字列>              メソッド検索");
        out.println("  java-refs --symbol <名前> [--calls]       参照／呼び出し関係");
        out.println("  java-similar --method <名前|ID>           類似メソッド検索");
        out.println("  java-rules                                五级設計 md の規約を表示");
        out.println("  propose-edit --path <Java相対パス> --summary <内容> --rationale <理由>");
        out.println("               [--newContent <本文> | --methodName <名> --newMethod <本文>]");
        out.println("               編集提案を作成（書き込まない）。提案IDと承認トークンを表示");
        out.println("  diff --proposal <ID>                      提案の before/after 差分");
        out.println("  approve-edit --proposal <ID> --token <トークン> [--reject]");
        out.println("               人間が承認して書き込む。モデル単独では承認できない");
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

    private void templateFill(Map<String, String> options) throws IOException {
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

        List<TemplateFillBatch.Result> results = new TemplateFillBatch()
                .run(options.get("feature"), variables, context);
        out.println("テンプレート填充完了: " + results.size() + " ファイル → "
                + config.templateFillOutputRoot().toAbsolutePath().normalize());
        for (TemplateFillBatch.Result result : results) {
            out.println("  " + result.relativePath() + " -> " + result.outputPath());
            if (!result.remaining().isEmpty()) {
                out.println("[WARN] 未解決のプレースホルダ: " + result.relativePath()
                        + " " + result.remaining());
            }
        }
    }

    private void pipeline(Map<String, String> options) {
        String target = options.get("0");
        if ("sql".equalsIgnoreCase(target)) {
            out.println(new SqlGenPipeline().pipeline().stageIds().toString());
        } else if ("feature".equalsIgnoreCase(target)) {
            out.println(new FeatureCodegenPipeline().pipeline().stageIds().toString());
        } else if ("flow".equalsIgnoreCase(target)) {
            out.println(com.walk_nie.bizgen.agent.generator.flow.FlowCodegenPipeline.stageIds().toString());
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
        } else if ("agent".equalsIgnoreCase(target)) {
            previewAgent();
        } else {
            out.println("preview java|sql|agent を指定してください");
        }
    }

    /** LLM を呼ばず、Agent に渡すツール定義と system prompt テンプレートを表示する。 */
    private void previewAgent() throws IOException {
        out.println("----- Java Agent tools -----");
        out.print(runner().javaTools().describe());
        out.println("----- SQL Agent tools -----");
        out.print(runner().sqlTools().describe());
        out.println("----- Java Agent system prompt (variables unresolved) -----");
        out.println(repository.prompt("agent-java"));
        out.println("----- SQL Agent system prompt (variables unresolved) -----");
        out.println(repository.prompt("agent-sql"));
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
        new com.walk_nie.bizgen.agent.pipeline.stage.LoadSpecStage().execute(state, context);
        new ExtractTermsStage().execute(state, context);
        new MatchApiCatalogStage().execute(state, context);
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
        variables.put("termsText", state.termsText());
        variables.put("apisText", state.apisText());
        variables.put("specText", state.specText());
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
        out.println("术语: " + state.terms().size() + " 件 / 既存 API: " + state.apis().size() + " 件");
        if (state.selfCorrected()) {
            out.println("[SELF-CORRECT] 静的検証違反を 1 回自己修正しました。");
        }
        if (!state.violations().isEmpty()) {
            out.println("[WARN] 残存する静的検証違反: " + state.violations());
        }
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

    private void tools() {
        out.println("----- Java Agent tools -----");
        out.print(runner().javaTools().describe());
        out.println("----- SQL Agent tools -----");
        out.print(runner().sqlTools().describe());
        out.println("limits: rounds=" + config.agentMaxRounds()
                + ", toolCalls=" + config.agentMaxToolCalls()
                + ", sameCall=" + config.agentMaxSameCall());
    }

    /**
     * LLM へ最小の tool 付きリクエストを 1 回だけ送り、生レスポンスを表示する。
     * 「tool_calls が空」の原因が接続・モデル選択・モデル出力形式のどれかを切り分けるための診断用。
     */
    private void llmProbe() {
        out.println("----- LLM probe -----");
        out.println("endpoint   : " + config.endpoint());
        out.println("model      : " + config.model());
        out.println("toolChoice : " + config.toolChoice());

        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("message", ToolDefinition.stringProperty("echo する文字列"));
        ToolDefinition echo = new ToolDefinition("echo", "入力をそのまま返すツール",
                ToolDefinition.objectSchema(properties, Collections.singletonList("message")),
                Collections.singletonList("message"));

        List<LlmMessage> messages = new ArrayList<LlmMessage>();
        messages.add(LlmMessage.system("You must call the echo tool. Never answer in plain text."));
        messages.add(LlmMessage.user("Call the echo tool with message=hello."));
        LlmRequest request = new LlmRequest(config.model(), messages, 0.0, 256, null,
                Collections.singletonList(echo), config.toolChoice());

        HttpLlmClient client = new HttpLlmClient(config);
        LlmRawResponse raw = client.chatDetail(request);
        LlmResponse response = raw.response();

        out.println("----- raw response (max 3000 chars) -----");
        out.println(abbreviate(raw.rawBody(), 3000));
        out.println("----- parsed -----");
        out.println("finish_reason       : " + response.finishReason());
        out.println("tool_calls          : " + response.toolCalls().size());
        for (ToolCall call : response.toolCalls()) {
            out.println("  - " + call.describe());
        }
        out.println("content             : " + abbreviate(response.content(), 500));

        ToolCallExtractor.Result recovered = ToolCallExtractor.extract(response.content());
        if (!recovered.calls().isEmpty()) {
            out.println("[OK] content から " + recovered.calls().size()
                    + " 件の tool 呼び出しを復元できます: " + recovered.warning());
            for (ToolCall call : recovered.calls()) {
                out.println("  - " + call.describe());
            }
            out.println("→ agent.toolCallRecovery=true なら Agent モードで自動実行されます。");
        } else if (!recovered.suspicion().isEmpty()) {
            out.println("[WARN] " + recovered.suspicion());
        } else {
            out.println("[INFO] structured な tool_calls も、復元可能なテキストもありません。");
            out.println("       → モデルがツールを使わない／LM Studio の tool 対応設定を確認してください。");
        }
    }

    private static String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    private void terms() throws IOException {
        TermDictionary dictionary = TermDictionary.load(config.termRoot());
        out.println("术语库: " + config.termRoot().toAbsolutePath().normalize());
        for (TermDictionary.TermEntry entry : dictionary.entries()) {
            out.println("  " + entry + "  [" + entry.source() + "]");
        }
    }

    private void apiList() throws IOException {
        ApiCatalog catalog = ApiCatalog.load(config.apiRoot());
        out.println("API 库: " + config.apiRoot().toAbsolutePath().normalize());
        for (ApiCatalog.ApiEntry entry : catalog.entries()) {
            out.println("  " + entry.className() + "." + entry.signature()
                    + " — " + entry.description());
        }
    }

    /** spec/api・dict・template・example の参考資料一覧（LLM 不使用）。 */
    private void specList() throws IOException {
        com.walk_nie.bizgen.agent.spec.SpecCatalog catalog =
                com.walk_nie.bizgen.agent.spec.SpecCatalog.load(
                        config.specRoot(), context.variables(), config.specMaxChars());
        out.println("spec 資料: " + config.specRoot().toAbsolutePath().normalize()
                + " (maxChars=" + config.specMaxChars() + ")");
        out.print(catalog.listText());
    }

    /** 5 ステップ Pipeline を実行し、术语・API・自己修正・出力先を表示する。 */
    private void featureGen(Map<String, String> options) {
        String featureName = options.get("feature");
        if (StrUtil.isBlank(featureName)) {
            throw new IllegalArgumentException("--feature が必要です");
        }
        FeatureGenState state = new FeatureCodegenPipeline().run(featureName, context);
        out.println("Feature Pipeline 完了: " + state.targetPath());
        out.println("フェーズ      : " + state.phase());
        out.println("抽出术语      : " + state.terms().size() + " 件");
        out.println("匹配 API      : " + state.apis().size() + " 件");
        out.println("自己修正      : " + (state.corrected() ? "実施（1 回）" : "不要"));
        if (!state.violations().isEmpty()) {
            out.println("[WARN] 残存違反: " + state.violations());
        }
        out.println("----- 実行トレース -----");
        for (com.walk_nie.bizgen.agent.pipeline.StageTrace trace : context.traces()) {
            out.println("  " + trace);
        }
    }

    private void flowGen(Map<String, String> options) {
        String featureName = options.get("feature");
        if (StrUtil.isBlank(featureName)) {
            throw new IllegalArgumentException("--feature が必要です");
        }
        com.walk_nie.bizgen.agent.generator.flow.FlowCodegenPipeline.Result result =
                new com.walk_nie.bizgen.agent.generator.flow.FlowCodegenPipeline()
                        .run(featureName, context);
        com.walk_nie.bizgen.agent.generator.java.JavaWriteResult written = result.java().writeResult();
        out.println("Flow Pipeline 完了: " + written.classFile());
        if (written.packageInfoFile() != null) {
            out.println("パッケージコメント: " + written.packageInfoFile());
        }
        out.println("コードの流れ: " + result.flow().plan().flow());
        out.println("機能ブロック : " + result.flow().plan().blocks().size() + " 件 / 処理 "
                + result.flow().blockRounds() + " 回");
        for (com.walk_nie.bizgen.agent.generator.flow.CodePlan.Block block
                : result.flow().plan().blocks()) {
            out.println("  [" + block.index() + "] " + block.kind() + " " + block.description()
                    + (block.apiSignature().isEmpty() ? "" : " → " + block.apiSignature()));
        }
        if (!result.flow().toolResolvedApis().isEmpty()) {
            out.println("ツールで確認した API: " + result.flow().toolResolvedApis());
        }
        if (!written.warnings().isEmpty()) {
            out.println("[WARN] 検証警告: " + written.warnings());
        }
        if (config.reviewRequired()) {
            out.println("[REVIEW] 生成コードは人間がレビューしてから取り込んでください。");
        }
        printTraces(context);
    }

    private void agentGen(Map<String, String> options) throws IOException {
        String featureName = options.get("feature");
        if (StrUtil.isBlank(featureName)) {
            throw new IllegalArgumentException("--feature が必要です");
        }
        JavaGenState state = runner().generateJava(featureName);
        lastAgentResult = runner().lastResult();
        JavaWriteResult result = state.writeResult();
        out.println("Agent Java 生成完了: " + result.classFile());
        if (result.packageInfoFile() != null) {
            out.println("パッケージコメント: " + result.packageInfoFile());
        }
        out.println("変更種別: " + result.intent() + "（" + state.intent().source() + "）");
        if (!runner().selectedApis().isEmpty()) {
            out.println("モデルが find_api で確認した API: " + runner().selectedApis());
        }
        if (!result.warnings().isEmpty()) {
            out.println("[WARN] 検証警告: " + result.warnings());
        }
        if (config.reviewRequired()) {
            out.println("[REVIEW] 生成コードは人間がレビューしてから取り込んでください。");
        }
        printAgentTrace();
        printTraces(context);
    }

    private void agentSql(Map<String, String> options) throws IOException {
        SqlGenRequest request = buildSqlRequest(options);
        SqlGenState state = runner().generateSql(request);
        lastAgentResult = runner().lastResult();
        SqlGenResult result = state.result();
        out.println("Agent SQL 生成完了: " + result.file());
        out.println("使用表: " + result.tableNames());
        out.println("----- SQL -----");
        out.println(result.sql());
        printAgentTrace();
        printTraces(context);
    }

    private void agentTrace() {
        if (lastAgentResult == null) {
            out.println("Agent はまだ実行されていません。");
            return;
        }
        printAgentTrace();
    }

    private void printAgentTrace() {
        if (lastAgentResult == null) {
            return;
        }
        out.println("----- Agent トレース (rounds=" + lastAgentResult.rounds()
                + ", toolCalls=" + lastAgentResult.toolCallCount() + ") -----");
        out.print(lastAgentResult.state().describeTraces());
        if (!lastAgentResult.success()) {
            out.println("[ERROR] " + lastAgentResult.error());
        }
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
    // ---------- Java コード知能（JDT AST + SQLite） ----------

    private com.walk_nie.bizgen.agent.codeintel.CodeIntelService codeIntel() {
        return com.walk_nie.bizgen.agent.codeintel.CodeIntelService.forConfig(config);
    }

    private static String require(Map<String, String> options, String key, String usage) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("引数 --" + key + " が必要です。使い方: " + usage);
        }
        return value.trim();
    }

    private static int intOption(Map<String, String> options, String key, int defaultValue) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " は整数で指定してください: " + value);
        }
    }

    private void javaIndex(Map<String, String> options) throws IOException {
        String action = options.get("0") == null ? "refresh" : options.get("0").toLowerCase();
        try {
            if ("build".equals(action)) {
                out.println("索引を再構築しました: " + codeIntel().indexer().rebuild().describe());
            } else if ("refresh".equals(action)) {
                out.println("索引を更新しました: " + codeIntel().indexer().refresh().describe());
            } else if ("stats".equals(action)) {
                out.println(codeIntel().stats());
            } else {
                out.println("使い方: java-index build|refresh|stats");
            }
        } catch (java.sql.SQLException e) {
            out.println("[ERROR] SQLite 索引の操作に失敗: " + e.getMessage());
        }
    }

    private void javaFiles(Map<String, String> options) {
        try {
            out.print(codeIntel().search().listFiles(options.get("prefix") == null
                    ? "" : options.get("prefix"),
                    intOption(options, "limit", config.javaIndexMaxResults())));
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void javaSearch(Map<String, String> options) {
        try {
            out.print(codeIntel().search().keyword(
                    require(options, "query", "java-search --query <キーワード>"),
                    intOption(options, "limit", 20)));
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void javaClass(Map<String, String> options) {
        try {
            out.println(codeIntel().search().classStructure(
                    require(options, "class", "java-class --class <クラス名>")));
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void javaMethod(Map<String, String> options) {
        try {
            out.print(codeIntel().search().findMethod(
                    require(options, "query", "java-method --query <文字列>"),
                    intOption(options, "limit", 20)));
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void javaRefs(Map<String, String> options) {
        try {
            String symbol = require(options, "symbol", "java-refs --symbol <名前> [--calls]");
            int limit = intOption(options, "limit", 20);
            out.print(options.containsKey("calls")
                    ? codeIntel().search().calls(symbol, limit)
                    : codeIntel().search().references(symbol, limit));
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void javaSimilar(Map<String, String> options) {
        try {
            out.print(codeIntel().search().similarMethods(
                    require(options, "method", "java-similar --method <名前|ID>"),
                    intOption(options, "limit", config.javaIndexSimilarTopN())));
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void javaRules() {
        try {
            out.print(codeIntel().search().projectRules());
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void proposeEdit(Map<String, String> options) {
        String usage = "propose-edit --path <Java> --summary <内容> --rationale <理由> "
                + "[--newContent <本文> | --methodName <名> --newMethod <本文>]";
        try {
            com.walk_nie.bizgen.agent.codeintel.JavaEditService.ProposeOutcome outcome =
                    codeIntel().edit().propose(
                            require(options, "path", usage),
                            require(options, "summary", usage),
                            require(options, "rationale", usage),
                            options.get("newContent"),
                            options.get("methodName"),
                            options.get("newMethod"));
            out.println(outcome.forHuman);
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void showDiff(Map<String, String> options) {
        try {
            out.println(codeIntel().edit().diff(
                    require(options, "proposal", "diff --proposal <ID>")));
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

    private void approveEdit(Map<String, String> options) {
        try {
            String usage = "approve-edit --proposal <ID> --token <トークン> [--reject]";
            String id = require(options, "proposal", usage);
            String token = options.containsKey("reject") ? "" : require(options, "token", usage);
            out.println(codeIntel().edit().approve(id, token, !options.containsKey("reject")));
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }
    }

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
