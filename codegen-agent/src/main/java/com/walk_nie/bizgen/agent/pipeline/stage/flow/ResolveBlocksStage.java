package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.AgentLoop;
import com.walk_nie.bizgen.agent.agent.AgentResult;
import com.walk_nie.bizgen.agent.agent.ToolRegistry;
import com.walk_nie.bizgen.agent.agent.tool.FindApiTool;
import com.walk_nie.bizgen.agent.agent.tool.SemanticSearchTool;
import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.flow.CodePlan;
import com.walk_nie.bizgen.agent.generator.flow.FlowGenState;
import com.walk_nie.bizgen.agent.generator.java.ExistingCodeSearcher;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeResult;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeSearchLog;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.semantic.SemanticSearchIntent;
import com.walk_nie.bizgen.agent.util.KeywordExtractor;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * ステップ 4・5: 機能ブロックを 1 つずつ処理する。
 * API ブロックは find_api function tool で実在のシグネチャを確定し、
 * それ以外はモデルに新規コードを書かせる。ループの回数と進行はすべてコード側が管理する。
 */
public final class ResolveBlocksStage implements Stage<FlowGenState, FlowGenState> {

    @Override
    public String id() {
        return "flow-02-resolve-blocks";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public FlowGenState execute(FlowGenState state, PipelineContext context) {
        state.assertPhase(FlowGenState.Phase.REUSED);
        CodePlan plan = state.plan();
        if (plan == null) {
            throw new IllegalStateException("機能ブロックが作られていません");
        }

        for (CodePlan.Block block : plan.blocks()) {
            // コード側でテンプレートが確定したブロックは LLM を一切呼ばない。
            if (block.status() == CodePlan.Status.DONE) {
                continue;
            }
            if (block.kind() == CodePlan.Kind.API) {
                BlockResult resolved = resolveWithTool(block, state, context);
                block.markDone(resolved.code, resolved.signature);
                state.recordToolApi(resolved.signature);
            } else {
                block.markDone(generateNewCode(block, state, context), "");
            }
            state.countBlockRound();
        }

        if (!plan.allDone()) {
            throw new IllegalStateException("処理が完了していない機能ブロックがあります");
        }
        state.advance(FlowGenState.Phase.BLOCKS_RESOLVED);
        return state;
    }

    /** API ブロック: モデルに意味解析 → semantic_search / find_api を呼ばせ、返ったシグネチャだけを使わせる。 */
    private BlockResult resolveWithTool(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        AgentConfig config = context.config();
        VariableTable variables = variables(block, state, context);
        boolean semanticEnabled = config.semanticRetrievalEnabled();
        variables.put("retrievalInstruction", semanticEnabled
                ? "1. まず `semantic_search` (target=API) を呼び、このブロックの機能記述を意味解析して"
                        + "導いた検索語で既存 API 库を探す。\n"
                        + "2. 必要なら `find_api` でシグネチャを確定する。返った実在シグネチャ以外は呼ばない。\n"
                        + "3. 無命中の API は存在しないものとして扱い、推測して呼ばない（無命中は自動ログされる）。"
                : "1. まず `find_api` function tool を呼び、このブロックで呼ぶべき既存 API の実在シグネチャを確認する。\n"
                        + "2. `find_api` が返したシグネチャ以外のメソッド・クラスを呼ばない。");
        try {
            String system = variables.resolveText(context.repository().prompt("code-block-api"));
            String user = "機能ブロック [" + block.index() + "]: " + block.description()
                    + "\n検索キーワード: " + block.apiQuery()
                    + (semanticEnabled
                            ? "\nまずこのブロックを意味解析し、semantic_search で既存 API 库を自ら探すこと。"
                            : "");

            ToolRegistry registry = new ToolRegistry().register(new FindApiTool());
            if (semanticEnabled) {
                registry.register(new SemanticSearchTool());
            }
            AgentContext agentContext = new AgentContext(config, context.repository(),
                    toolVariables(variables, state), context.llm());
            AgentResult result = new AgentLoop(registry, agentContext).run(system, user);
            if (!result.success()) {
                throw new IllegalStateException("API ブロック " + block.index()
                        + " の工具呼び出しに失敗: " + result.error());
            }
            recordSemanticSearches(state, agentContext);
            List<ApiCatalog.ApiEntry> apis = agentContext.selectedApis();
            String signature = apis.isEmpty() ? "" : apis.get(apis.size() - 1).signature();
            return new BlockResult(StrUtil.stripCodeFence(result.finalAnswer()), signature);
        } catch (IOException e) {
            throw new IllegalStateException("code-block-api prompt の読み込みに失敗", e);
        }
    }

    /**
     * 新規コードブロック: 静的な {@code {{similarCode}}} 差し込みは行わない。
     * モデルがブロック記述を意味解析し、{@code semantic_search} function tool で
     * 「どんな既存実装を探すか」を自ら宣言する。実際の走査・採点・切断・無命中ログは
     * コード側（SemanticRetrievalExecutor）が決定論的に行う。
     */
    private String generateNewCode(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        AgentConfig config = context.config();
        VariableTable variables = variables(block, state, context);
        if (!config.semanticRetrievalEnabled()) {
            return generateNewCodeFallback(block, state, context, variables);
        }

        variables.put("similarCode", "（事前差し込みなし。semantic_search ツールで自ら探すこと）");
        variables.put("retrievalInstruction",
                "1. まずこのブロックの機能記述を意味解析し、どんな既存実装が必要かを判断する。\n"
                        + "2. `semantic_search` (target=CODE) を呼び、意味解析から導いた purpose / keywords で"
                        + "既存 Java 库を探す。返った本物のコードだけを書き方・命名・Javadoc 粒度の根拠にする。\n"
                        + "3. 無命中なら類似実装を流用せず、機能記述どおりに新規コードを書く"
                        + "（無命中の入力は自動ログされる）。\n"
                        + "4. 存在しない既存 API を推測して呼ばない。");
        try {
            String system = variables.resolveText(context.repository().prompt("code-block-new"));
            String user = "機能ブロック [" + block.index() + "]: " + block.description()
                    + "\nまず意味解析し、semantic_search (target=CODE) で既存 Java 库を自ら探すこと。"
                    + "その結果を踏まえ、このブロックの Java コード片のみを出力すること。";

            ToolRegistry registry = new ToolRegistry().register(new SemanticSearchTool());
            AgentContext agentContext = new AgentContext(config, context.repository(),
                    toolVariables(variables, state), context.llm());
            AgentResult result = new AgentLoop(registry, agentContext).run(system, user);
            if (!result.success()) {
                throw new IllegalStateException("NEW_CODE ブロック " + block.index()
                        + " の意味検索に失敗: " + result.error());
            }
            recordSemanticSearches(state, agentContext);
            return StrUtil.stripCodeFence(result.finalAnswer());
        } catch (IOException e) {
            throw new IllegalStateException("code-block-new prompt の読み込みに失敗", e);
        }
    }

    /** 意味解析が無効な場合の決定論的フォールバック（旧来のキーワード検索）。 */
    private String generateNewCodeFallback(CodePlan.Block block, FlowGenState state,
                                          PipelineContext context, VariableTable variables) {
        AgentConfig config = context.config();
        List<String> keywords = semanticKeywords(state, block);
        if (keywords.isEmpty()) {
            keywords = KeywordExtractor.extract(
                    block.description() + " " + state.plan().className(), 20);
        }
        SimilarCodeResult similar = ExistingCodeSearcher.searchByKeywords(
                config, keywords, state.java().resolved().spec().targetFile());
        if (similar.snippets().isEmpty()) {
            SimilarCodeSearchLog.recordNoHit(config, "block-" + block.index(),
                    state.java().resolved().spec().title(), block.description(), similar);
        }
        variables.put("similarCode", similar.promptText());
        variables.put("retrievalInstruction",
                "以下の類似コード（コード側が決定論的に検索した本物の命中）を参考にすること。");
        try {
            String prompt = variables.resolveText(context.repository().prompt("code-block-new"));
            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);
            return StrUtil.stripCodeFence(response.content());
        } catch (IOException e) {
            throw new IllegalStateException("code-block-new prompt の読み込みに失敗", e);
        }
    }

    /** semantic_search ツールが対象ファイルを除外できるよう、ブロック変数に targetFile を足す。 */
    private VariableTable toolVariables(VariableTable source, FlowGenState state) {
        VariableTable vars = new VariableTable();
        vars.putAll(source.snapshot());
        vars.put("targetFile", state.java().resolved().spec().targetFile());
        return vars;
    }

    private void recordSemanticSearches(FlowGenState state, AgentContext agentContext) {
        for (SemanticSearchIntent intent : agentContext.semanticSearches()) {
            state.recordSemanticSearch(intent);
        }
    }

    /** ブロック記述に意味的に近い検索意図の keywords を選ぶ（コード側の決定論的対応付け）。 */
    private List<String> semanticKeywords(FlowGenState state, CodePlan.Block block) {
        SemanticSearchIntent intent = state.java().semanticIntent();
        if (intent == null || !intent.drivesRetrieval()) {
            return new java.util.ArrayList<String>();
        }
        String description = StrUtil.trimToEmpty(block.description()).toLowerCase(java.util.Locale.ROOT);
        java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<String>();
        for (SemanticSearchIntent.Query query : intent.queriesFor(SemanticSearchIntent.Target.CODE)) {
            String purpose = StrUtil.trimToEmpty(query.purpose()).toLowerCase(java.util.Locale.ROOT);
            boolean matches = !description.isEmpty()
                    && (description.contains(purpose) || purpose.contains(description)
                        || sharesToken(description, purpose));
            if (matches) {
                selected.addAll(query.keywords());
            }
        }
        if (selected.isEmpty()) {
            // 対応する意図が無ければ、CODE 意図全体の検索語を使う（意味解析の成果を必ず活かす）。
            selected.addAll(intent.codeKeywords());
        }
        return new java.util.ArrayList<String>(selected);
    }

    private boolean sharesToken(String a, String b) {
        for (String token : a.split("[\\s、,。/]+")) {
            if (token.length() >= 2 && b.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private VariableTable variables(CodePlan.Block block, FlowGenState state, PipelineContext context) {
        JavaGenState java = state.java();
        VariableTable variables = new VariableTable();
        variables.put("blockIndex", String.valueOf(block.index()));
        variables.put("blockDescription", block.description());
        variables.put("blockKind", block.kind().name());
        variables.put("apiQuery", block.apiQuery());
        variables.put("flow", state.plan().flow());
        variables.put("className", state.plan().className());
        variables.put("featureBody", java.resolved().featureBody());
        variables.put("designDocs", java.resolved().designText());
        variables.put("termsText", java.termsText());
        variables.put("apisText", java.apisText());
        variables.put("similarCode", java.similarCode().promptText());
        variables.put("specText", java.specText());
        return variables;
    }

    private static final class BlockResult {
        final String code;
        final String signature;

        BlockResult(String code, String signature) {
            this.code = code;
            this.signature = signature;
        }
    }
}
