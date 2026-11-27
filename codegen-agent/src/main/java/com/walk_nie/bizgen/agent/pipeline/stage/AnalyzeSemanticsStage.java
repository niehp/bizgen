package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.md.JavaFeatureOutline;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.semantic.SemanticRetrievalExecutor;
import com.walk_nie.bizgen.agent.semantic.SemanticRetrievalResult;
import com.walk_nie.bizgen.agent.semantic.SemanticSearchIntent;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 機能記述をモデルに意味解析させ、「どの既存実装 / どの既存 API を探すべきか」という
 * 検索意図（{@link SemanticSearchIntent}）を立てさせる段階。
 *
 * <p>ここが旧方式との決定的な違いである。旧方式はコード側が機能 md から機械的に
 * キーワードを抽出し、類似コード片を {@code {{similarCode}}} へ静的に差し込んでいた。
 * 本段階では<b>モデルが機能の意味を読み解いて検索語を決め</b>、実際に既存 Java 库・
 * API 库を走査・採点・切断するのは {@link SemanticRetrievalExecutor} の決定論的処理である。</p>
 *
 * <p>phase は進めない（TERMS_EXTRACTED のまま）。05-match-apis と 08-search-existing-code
 * が本段階の結果を消費し、無い場合は旧来の決定論的検索へフォールバックする。</p>
 */
public final class AnalyzeSemanticsStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "04b-semantic-retrieval";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.TERMS_EXTRACTED);
        AgentConfig config = context.config();

        if (!config.semanticRetrievalEnabled()) {
            SemanticSearchIntent fallback = SemanticSearchIntent.fallback("設定で無効");
            state.setSemanticIntent(fallback);
            state.setSemanticRetrieval(SemanticRetrievalExecutor.execute(
                    config, fallback, "", "", null));
            return state;
        }

        ResolvedFeature resolved = state.resolved();
        try {
            VariableTable variables = new VariableTable();
            JavaFeatureOutline outline = JavaFeatureOutline.parse(resolved.featureBody());
            variables.put("featureOutline", outline.isEmpty()
                    ? "（md に Java 契約の明示なし）" : outline.toPromptText());
            variables.put("featureBody", resolved.featureBody());
            variables.put("designDocs", resolved.designText());
            variables.put("termsText", state.termsText());
            variables.put("maxQueries", String.valueOf(config.semanticMaxQueries()));
            variables.put("apiClassIndex", apiClassIndex(config));
            String prompt = variables.resolveText(
                    context.repository().prompt(config.semanticRetrievalPrompt()));

            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);

            String json = StrUtil.extractJsonObject(response.content());
            if (json.isEmpty()) {
                throw new IllegalArgumentException("意味解析の JSON が取得できませんでした");
            }
            SemanticSearchIntent intent = SemanticSearchIntent.fromJson(json, config.semanticMaxQueries());
            state.setSemanticIntent(intent);
            state.setSemanticRetrieval(SemanticRetrievalExecutor.execute(config, intent,
                    resolved.spec().title(), resolved.featureBody(), resolved.spec().targetFile()));
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("semantic-retrieval prompt の読み込みに失敗", e);
        } catch (RuntimeException e) {
            // モデル出力のブレで生成全体を止めない。警告を残し、旧来の決定論的検索へ戻す。
            System.err.println("[semantic-retrieval] 意味解析を採用できませんでした: "
                    + e.getMessage() + " / 決定論的検索へフォールバックします");
            SemanticSearchIntent fallback = SemanticSearchIntent.fallback(e.getMessage());
            state.setSemanticIntent(fallback);
            state.setSemanticRetrieval(SemanticRetrievalExecutor.execute(
                    config, fallback, resolved.spec().title(), resolved.featureBody(),
                    resolved.spec().targetFile()));
            return state;
        }
    }

    /** API 库のクラス名だけを検索語の手がかりとして渡す（全シグネチャは渡さない）。 */
    private String apiClassIndex(AgentConfig config) {
        try {
            ApiCatalog catalog = ApiCatalog.load(config.apiRoot());
            Set<String> names = new LinkedHashSet<String>();
            for (ApiCatalog.ApiEntry entry : catalog.entries()) {
                if (!StrUtil.isBlank(entry.className()) && names.size() < 40) {
                    names.add(entry.className());
                }
            }
            List<String> list = new ArrayList<String>(names);
            return list.isEmpty() ? "（API 库にクラス登録なし）" : list.toString();
        } catch (IOException e) {
            return "（API 库を読み込めませんでした）";
        }
    }
}
