package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeResult;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeSnippet;
import com.walk_nie.bizgen.agent.semantic.SemanticRetrievalResult;
import com.walk_nie.bizgen.agent.semantic.SemanticSearchIntent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * モデルが<b>機能記述を意味解析したうえで</b>「この実装 / この API を探したい」と
 * 自ら検索を申し出るための function tool。
 *
 * <p>モデルが決めるのは「何を探すか（purpose + keywords + target）」だけ。
 * 既存 Java 库・API 库の走査・採点・切断・無命中ログはすべてコード側が決定論的に行い、
 * 返るのは库から実際に取り出した<b>本物のコード / 実在シグネチャ</b>だけである。
 * 静的な {@code {{similarCode}}} への差し込みに頼らない。</p>
 */
public final class SemanticSearchTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("target", ToolDefinition.stringProperty(
                "CODE=既存 Java 実装を探す / API=既存 API シグネチャを探す"));
        properties.put("purpose", ToolDefinition.stringProperty(
                "機能記述の意味解析から導いた『何を探すか』を日本語で。例: 受注IDで1件検索する既存メソッド"));
        properties.put("keywords", ToolDefinition.arrayProperty(
                "意味解析から導いた検索語。日本語名・英語物理名・メソッド名を混在可。丸写しは禁止"));
        return new ToolDefinition(
                "semantic_search",
                "機能記述を意味解析して得た検索意図で、既存 Java 库 / API 库から本物の実装・シグネチャを探す。"
                        + "コードを書く前に必要な分だけ呼ぶ。返らなかったものは存在しないものとして扱い、"
                        + "無命中は自動的にログされる。",
                ToolDefinition.objectSchema(properties,
                        Arrays.asList("target", "purpose", "keywords")),
                Arrays.asList("target", "purpose", "keywords"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String targetToken = ToolSupport.str(arguments, "target");
        String purpose = ToolSupport.str(arguments, "purpose");
        List<String> keywords = ToolSupport.strings(arguments, "keywords");
        if (keywords.isEmpty()) {
            return ToolResult.fail(null, "keywords が空です。意味解析で得た検索語を指定してください");
        }
        SemanticSearchIntent.Target target = SemanticSearchIntent.Target.fromToken(targetToken);
        if (target == null) {
            return ToolResult.fail(null, "target は CODE または API のみ: " + targetToken);
        }

        SemanticSearchIntent.Query query = new SemanticSearchIntent.Query(target, purpose, keywords);
        SemanticSearchIntent intent = new SemanticSearchIntent(
                "function tool による意味解析検索: " + purpose,
                Arrays.asList(query), true);
        SemanticRetrievalResult result = SemanticRetrievalExecutorHolder.execute(context, intent);
        context.recordSemanticSearch(intent);
        // API 検索で得た実在シグネチャも人工レビュー用の監査記録へ残す（find_api と同じ扱い）。
        if (target == SemanticSearchIntent.Target.API && result.hasApiHits()) {
            context.recordApis(result.apis());
        }

        StringBuilder out = new StringBuilder();
        out.append("検索意図: [").append(target.name()).append("] ").append(purpose).append('\n');
        out.append("検索語: ").append(keywords).append('\n');
        if (target == SemanticSearchIntent.Target.API) {
            out.append(apisText(context, result, purpose));
        } else {
            out.append(codeText(context, result, purpose));
        }
        return ToolResult.ok(null, ToolSupport.truncate(out.toString(),
                context.config().maxSimilarCodeChars() + 2000));
    }

    private String apisText(AgentContext context, SemanticRetrievalResult result, String purpose) {
        if (!result.hasApiHits()) {
            return "命中: 0 件（この意図に合う既存 API は見つかりませんでした。"
                    + "存在しない API を生成せず、新規コードで実装すること。入力はログ済み）\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("命中: ").append(result.apis().size()).append(" 件（API 库の実在シグネチャのみ）\n\n");
        String currentClass = "";
        for (ApiCatalog.ApiEntry api : result.apis()) {
            if (!currentClass.equals(api.className())) {
                currentClass = api.className();
                out.append("### ").append(currentClass).append('\n');
            }
            out.append("- `").append(api.signature()).append("` — ")
                    .append(api.description()).append('\n');
        }
        return out.toString();
    }

    private String codeText(AgentContext context, SemanticRetrievalResult result, String purpose) {
        SimilarCodeResult code = result.codeResult();
        if (code == null || code.snippets().isEmpty()) {
            return "命中: 0 件（この意図に合う既存実装は見つかりませんでした。"
                    + "類似コードを流用せず、機能記述どおりに新規コードを書くこと。入力はログ済み）\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("命中: ").append(code.snippets().size())
                .append(" 件（設定された既存 Java 库から取り出した本物のコード）\n\n");
        for (int i = 0; i < code.snippets().size(); i++) {
            SimilarCodeSnippet snippet = code.snippets().get(i);
            out.append("===== 既存コード ").append(i + 1).append(": ")
                    .append(snippet.relativePath()).append(" =====\n")
                    .append(snippet.content()).append("\n\n");
        }
        return out.toString();
    }

    /** 実行器を 1 箇所に集約（テスト容易性と決定論の担保）。 */
    private static final class SemanticRetrievalExecutorHolder {
        static SemanticRetrievalResult execute(AgentContext context, SemanticSearchIntent intent) {
            return com.walk_nie.bizgen.agent.semantic.SemanticRetrievalExecutor.execute(
                    context.config(), intent, "function-tool", intent.toPromptText(),
                    context.variables().get("targetFile"));
        }
    }
}
