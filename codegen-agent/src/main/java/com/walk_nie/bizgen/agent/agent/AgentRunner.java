package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.agent.tool.ApproveJavaEditTool;
import com.walk_nie.bizgen.agent.agent.tool.ApplyBlockTemplateTool;
import com.walk_nie.bizgen.agent.agent.tool.FindApiTool;
import com.walk_nie.bizgen.agent.agent.tool.FindReusableBlockTool;
import com.walk_nie.bizgen.agent.agent.tool.FindClassTool;
import com.walk_nie.bizgen.agent.agent.tool.FindMethodTool;
import com.walk_nie.bizgen.agent.agent.tool.FindReferencesTool;
import com.walk_nie.bizgen.agent.agent.tool.FindSimilarMethodTool;
import com.walk_nie.bizgen.agent.agent.tool.GetClassStructureTool;
import com.walk_nie.bizgen.agent.agent.tool.GetDiffTool;
import com.walk_nie.bizgen.agent.agent.tool.GetProjectRulesTool;
import com.walk_nie.bizgen.agent.agent.tool.ListDesignDocsTool;
import com.walk_nie.bizgen.agent.agent.tool.ListJavaFilesTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadDesignDocTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadTableSchemaTool;
import com.walk_nie.bizgen.agent.agent.tool.ListTablesTool;
import com.walk_nie.bizgen.agent.agent.tool.ReadJavaFileTool;
import com.walk_nie.bizgen.agent.agent.tool.ProposeJavaEditTool;
import com.walk_nie.bizgen.agent.agent.tool.SearchJavaCodeTool;
import com.walk_nie.bizgen.agent.agent.tool.SearchSimilarCodeTool;
import com.walk_nie.bizgen.agent.agent.tool.SemanticSearchTool;
import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.java.JavaDraft;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenRequest;
import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.stage.ClassifyIntentStage;
import com.walk_nie.bizgen.agent.pipeline.stage.GenerateJavaDraftStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadDesignStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadFeatureStage;
import com.walk_nie.bizgen.agent.pipeline.stage.LoadTableSchemaStage;
import com.walk_nie.bizgen.agent.pipeline.stage.PolishJavaStage;
import com.walk_nie.bizgen.agent.pipeline.stage.RenderSqlPromptStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ResolveVariablesStage;
import com.walk_nie.bizgen.agent.pipeline.stage.SemanticMapStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ValidateCorrectDraftStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ValidateWriteStage;
import com.walk_nie.bizgen.agent.pipeline.stage.WriteSqlStage;
import com.walk_nie.bizgen.agent.reuse.ReuseResolver;
import com.walk_nie.bizgen.agent.reuse.ReuseSelection;
import com.walk_nie.bizgen.agent.reuse.ReuseVerifier;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.JavaCodeExtractor;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Agent モードの生成ランナー。
 * 決定論的な前置ステージ → マルチターン Agent（tool で情報収集）→ 決定論的な後始末、という順を固定する。
 * モデルがツールを呼んでも、ディスクへの書き込みは最後のコード側ステージだけが行う。
 */
public final class AgentRunner {

    private final PipelineContext pipeline;
    private final AgentContext context;
    private final ToolRegistry javaTools;
    private final ToolRegistry sqlTools;
    private AgentResult lastResult;

    public AgentRunner(PipelineContext pipeline) {
        this.pipeline = pipeline;
        this.context = new AgentContext(pipeline.config(), pipeline.repository(),
                pipeline.variables(), pipeline.llm());
        this.javaTools = new ToolRegistry()
                .register(new ListDesignDocsTool())
                .register(new LoadDesignDocTool())
                // 意味解析 → 既存库の検索はモデルが自ら申し出る（静的注入の代わり）
                .register(new SemanticSearchTool())
                .register(new SearchSimilarCodeTool())
                .register(new ReadJavaFileTool())
                .register(new FindApiTool())
                // 再利用テンプレート: 検索 → 確定展開の 2 段構え（採用はコード側）
                .register(new FindReusableBlockTool())
                .register(new ApplyBlockTemplateTool())
                // Java コード知能（JDT AST + SQLite）: 12 ツール
                .register(new ListJavaFilesTool())
                .register(new SearchJavaCodeTool())
                .register(new FindClassTool())
                .register(new FindMethodTool())
                .register(new FindReferencesTool())
                .register(new GetClassStructureTool())
                .register(new FindSimilarMethodTool())
                .register(new GetProjectRulesTool())
                .register(new ProposeJavaEditTool())
                .register(new GetDiffTool())
                .register(new ApproveJavaEditTool());
        this.sqlTools = new ToolRegistry()
                .register(new ListTablesTool())
                .register(new LoadTableSchemaTool())
                .register(new LoadDesignDocTool());
    }

    public AgentResult lastResult() {
        return lastResult;
    }

    public ToolRegistry javaTools() {
        return javaTools;
    }

    public ToolRegistry sqlTools() {
        return sqlTools;
    }

    /** モデルが find_api で参照した API（人工レビュー用）。 */
    public List<com.walk_nie.bizgen.agent.api.ApiCatalog.ApiEntry> selectedApis() {
        return context.selectedApis();
    }

    /** モデルが apply_block_template で確定した再利用テンプレート（人工レビュー用）。 */
    public List<ReuseSelection> selectedReuses() {
        return context.selectedReuses();
    }

    /** Java 生成。Agent の最終回答を草稿として受け取り、Deterministic な検証・書き出しを行う。 */
    public JavaGenState generateJava(String featureName) throws IOException {
        JavaGenState state = new JavaGenState(featureName);
        new LoadFeatureStage().execute(state, pipeline);
        new LoadDesignStage().execute(state, pipeline);
        new ResolveVariablesStage().execute(state, pipeline);
        // Agent 路径は「静的な事前検索」を行わない。java-gen の 04/04b/05/08 を実行せず、
        // モデルが semantic_search / find_api function tool で既存库を自ら検索する。
        // 位相の遷移はコード側で 100% 明示的に進める（assertPhase を満たすための決定論的ジャンプ）。
        state.advance(JavaGenState.Phase.TERMS_EXTRACTED);
        state.advance(JavaGenState.Phase.APIS_MATCHED);
        new ClassifyIntentStage().execute(state, pipeline);
        new SemanticMapStage().execute(state, pipeline);
        // 08-search-existing-code は廃止（静的 {{similarCode}} 注入の代わりに semantic_search ツール）。
        state.advance(JavaGenState.Phase.SIMILAR_CODE_FOUND);

        ResolvedFeature resolved = state.resolved();
        // 機能 md の変数は tool 側でも展開できるよう AgentContext へ渡す。
        // putAll だと別機能の実行時に古い変数が残るため、ここでは丸ごと差し替える。
        context.variables().replaceAll(resolved.variables().snapshot());
        // semantic_search ツールが「生成対象ファイルを検索結果から除外」するために必要。
        context.variables().put("targetFile", resolved.spec().targetFile());
        String packageName = resolvePackageName(resolved);
        VariableTable variables = new VariableTable();
        variables.put("featureName", featureName);
        variables.put("intent", state.intent().intent().name());
        variables.put("targetFile", resolved.spec().targetFile());
        variables.put("packageName", packageName);
        variables.put("className", state.semanticMap().className());
        variables.put("semanticMap", state.semanticMap().toJson());
        variables.put("existingCode", readExistingCode(resolved));
        variables.put("designDocs", resolved.designText());
        variables.put("featureBody", resolved.featureBody());
        CodegenSupport.TermAnalysis termAnalysis =
                CodegenSupport.analyzeTerms(resolved.featureBody(), pipeline.config());
        variables.put("termsText", termAnalysis.text());
        variables.put("reusableHints", ReuseResolver.hintsText(
                new ReuseResolver(pipeline.config()).catalog()));
        // 類似コード / API の静的予注入は行わない。モデルが意味解析 → semantic_search ツールで探す。
        // 変数は後方互換のため空で埋めるだけにし、prompt 側で「差し込まない」ことを明示する。
        variables.put("similarCode", "（事前差し込みなし。semantic_search ツールで検索すること）");
        variables.put("apisText", "（事前差し込みなし。semantic_search / find_api ツールで検索すること）");

        String system = variables.resolveText(pipeline.repository().prompt("agent-java"));
        String user = "ユースケース [" + featureName + "] の Java コードを生成してください。"
                + "まず【機能 md 本文】を意味解析して、どの処理に既存実装・既存 API が必要かを判断してください。"
                + "そのうえで semantic_search（必要なら find_api）function tool を呼び、"
                + "既存 Java 库・API 库から本物のコードとシグネチャを自ら探してください。"
                + "情報が揃ったら规定の 2 セクションで出力してください。"
                + "API を使う場合はツールで返った実在のシグネチャだけを使い、"
                + "再利用テンプレートが使える場合は find_reusable_block → apply_block_template の順で呼び、"
                + "返ったコードを一字も変更せずに使ってください。"
                + "API 呼び出し・再利用テンプレート・新規コードを組み合わせて 1 つのクラスを作ってください。";

        AgentLoop loop = new AgentLoop(javaTools, context);
        AgentResult result = loop.run(system, user);
        lastResult = result;
        if (!result.success()) {
            throw new IllegalStateException("Agent 生成に失敗しました: " + result.error());
        }

        String defaultComment = resolved.variable("packageComment", resolved.spec().title());
        JavaDraft draft = GenerateJavaDraftStage.parseDraftText(
                result.finalAnswer(), packageName, defaultComment);
        if (!context.selectedReuses().isEmpty()) {
            String extracted = JavaCodeExtractor.extract(draft.classCode());
            List<String> missing = ReuseVerifier.missing(extracted, context.selectedReuses());
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "apply_block_template で確定したテンプレートが最終回答に含まれていません: "
                                + missing + "。テンプレートは変更・省略禁止です。");
            }
        }
        state.setDraft(draft);
        state.advance(JavaGenState.Phase.DRAFTED);
        new PolishJavaStage().execute(state, pipeline);
        // Agent の最終回答も java-gen と同じ決定論的検証ゲート（自己修正 1 回）を通す。
        new ValidateCorrectDraftStage().execute(state, pipeline);
        new ValidateWriteStage().execute(state, pipeline);
        return state;
    }

    /** SQL 生成。Agent が選んだ表構造のみを根拠にし、書き出しはコード側で行う。 */
    public SqlGenState generateSql(SqlGenRequest request) throws IOException {
        SqlGenState state = new SqlGenState(request);
        new LoadTableSchemaStage().execute(state, pipeline);
        new RenderSqlPromptStage().execute(state, pipeline);

        VariableTable variables = new VariableTable();
        variables.put("sqlType", request.type().name());
        variables.put("description", request.description());
        variables.put("tables", request.tables().toString());
        variables.put("sqlShape", request.sqlShape());
        variables.put("renderedPrompt", state.renderedPrompt());

        String system = variables.resolveText(pipeline.repository().prompt("agent-sql"));
        String user = request.type().name() + " SQL を生成してください。要求: " + request.description();

        AgentLoop loop = new AgentLoop(sqlTools, context);
        AgentResult result = loop.run(system, user);
        lastResult = result;
        if (!result.success()) {
            throw new IllegalStateException("Agent SQL 生成に失敗しました: " + result.error());
        }

        state.setSql(StrUtil.stripCodeFence(result.finalAnswer()).trim());
        state.advance(SqlGenState.Phase.GENERATED);
        new WriteSqlStage().execute(state, pipeline);
        return state;
    }

    private String resolvePackageName(ResolvedFeature resolved) {
        String declared = resolved.variable("packageName", "");
        if (!StrUtil.isBlank(declared)) {
            return declared;
        }
        String target = resolved.spec().targetFile();
        if (!StrUtil.isBlank(target) && target.contains("/") && target.endsWith(".java")) {
            return target.substring(0, target.lastIndexOf('/')).replace('/', '.');
        }
        return "com.walk_nie.bizgen.generated";
    }

    /** 既存 Java を読み取る。パスは設定ルート配下に限定し、脱出は許可しない（決定論的）。 */
    private String readExistingCode(ResolvedFeature resolved) throws IOException {
        if (context.config().existingJavaRoot() == null || StrUtil.isBlank(resolved.spec().targetFile())) {
            return "";
        }
        Path root = context.config().existingJavaRoot().toAbsolutePath().normalize();
        Path target = root.resolve(resolved.spec().targetFile()).normalize().toAbsolutePath();
        if (!target.startsWith(root) || !FileUtil.exists(target)) {
            return "（既存ファイルなし）";
        }
        return FileUtil.read(target);
    }
}
