package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.agent.tool.ListDesignDocsTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadDesignDocTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadTableSchemaTool;
import com.walk_nie.bizgen.agent.agent.tool.ListTablesTool;
import com.walk_nie.bizgen.agent.agent.tool.ReadJavaFileTool;
import com.walk_nie.bizgen.agent.agent.tool.SearchSimilarCodeTool;
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
import com.walk_nie.bizgen.agent.pipeline.stage.SearchExistingCodeStage;
import com.walk_nie.bizgen.agent.pipeline.stage.SemanticMapStage;
import com.walk_nie.bizgen.agent.pipeline.stage.ValidateWriteStage;
import com.walk_nie.bizgen.agent.pipeline.stage.WriteSqlStage;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

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
                .register(new SearchSimilarCodeTool())
                .register(new ReadJavaFileTool());
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

    /** Java 生成。Agent の最終回答を草稿として受け取り、Deterministic な検証・書き出しを行う。 */
    public JavaGenState generateJava(String featureName) throws IOException {
        JavaGenState state = new JavaGenState(featureName);
        new LoadFeatureStage().execute(state, pipeline);
        new LoadDesignStage().execute(state, pipeline);
        new ResolveVariablesStage().execute(state, pipeline);
        new ClassifyIntentStage().execute(state, pipeline);
        new SemanticMapStage().execute(state, pipeline);
        new SearchExistingCodeStage().execute(state, pipeline);

        ResolvedFeature resolved = state.resolved();
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
        variables.put("similarCode", state.similarCode().promptText());

        String system = variables.resolveText(pipeline.repository().prompt("agent-java"));
        String user = "ユースケース [" + featureName + "] の Java コードを生成してください。"
                + "まず必要な function tool を呼び、情報が揃ったら规定の 2 セクションで出力してください。";

        AgentLoop loop = new AgentLoop(javaTools, context);
        AgentResult result = loop.run(system, user);
        lastResult = result;
        if (!result.success()) {
            throw new IllegalStateException("Agent 生成に失敗しました: " + result.error());
        }

        String defaultComment = resolved.variable("packageComment", resolved.spec().title());
        JavaDraft draft = GenerateJavaDraftStage.parseDraftText(
                result.finalAnswer(), packageName, defaultComment);
        state.setDraft(draft);
        state.advance(JavaGenState.Phase.DRAFTED);
        new PolishJavaStage().execute(state, pipeline);
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
