package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.reuse.ReusableBlock;
import com.walk_nie.bizgen.agent.reuse.ReuseResolver;
import com.walk_nie.bizgen.agent.reuse.ReuseSelection;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 再利用テンプレートを「コード側で」変数展開して返す function tool。
 *
 * <p>返された Java コードは確定値。モデルが書き換えると最終検証で必ず検出される。
 * API_CALL は API 库に実在するシグネチャでなければ展開しない。</p>
 */
public final class ApplyBlockTemplateTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("blockId", ToolDefinition.stringProperty(
                "find_reusable_block が返したテンプレートの id。"));
        Map<String, Object> variableSchema = new LinkedHashMap<String, Object>();
        variableSchema.put("type", "object");
        variableSchema.put("description", "テンプレート内の {{変数}} に渡す値。キーは変数名。");
        variableSchema.put("additionalProperties", ToolDefinition.stringProperty("変数の値"));
        properties.put("variables", variableSchema);
        return new ToolDefinition(
                "apply_block_template",
                "指定テンプレートの変数をコード側で展開し、そのまま使う Java コード片を返す。"
                        + "返ったコードは変更せず、必ず最終回答に含めること。",
                ToolDefinition.objectSchema(properties, Collections.singletonList("blockId")),
                Collections.singletonList("blockId"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String blockId = ToolSupport.str(arguments, "blockId");
        if (blockId.isEmpty()) {
            return ToolResult.fail(null, "blockId が空です");
        }
        ReuseResolver resolver = new ReuseResolver(context.config());
        try {
            ReusableBlock block = resolver.catalog().byId(blockId);
            if (block == null) {
                return ToolResult.fail(null, "テンプレートが見つかりません: " + blockId);
            }
            Map<String, String> variables = mergeVariables(arguments, context);
            ReuseSelection selection = resolver.expand(block, 0, variables);
            if (selection == null) {
                return ToolResult.fail(null, diagnose(block, variables, resolver));
            }
            context.recordReuse(selection);
            if (block.kind() == ReusableBlock.Kind.API_CALL) {
                context.recordApis(Collections.singletonList(new ApiCatalog.ApiEntry(
                        "", block.apiSignature(), block.description(), block.source())));
            }
            StringBuilder out = new StringBuilder();
            out.append("テンプレート: ").append(selection.blockId())
                    .append(" [").append(selection.kind().name()).append("]\n");
            if (!selection.apiSignature().isEmpty()) {
                out.append("確認済み API: `").append(selection.apiSignature()).append("`\n");
            }
            out.append("以下のコードを一字も変更せずに使ってください。\n");
            out.append("```java\n").append(selection.code()).append("\n```\n");
            return ToolResult.ok(null, ToolSupport.truncate(out.toString(),
                    context.config().javaIndexMaxResultChars()));
        } catch (IOException e) {
            return ToolResult.fail(null, "テンプレートの展開に失敗: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> mergeVariables(Map<String, Object> arguments,
                                                      AgentContext context) {
        Map<String, String> variables = new LinkedHashMap<String, String>();
        variables.putAll(context.variables().snapshot());
        Object raw = arguments.get("variables");
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    variables.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return variables;
    }

    private static String diagnose(ReusableBlock block, Map<String, String> variables,
                                   ReuseResolver resolver) throws IOException {
        String expanded = block.resolveCode(variables);
        List<String> unresolved = VariableTable.placeholders(expanded);
        if (!unresolved.isEmpty()) {
            return "テンプレート " + block.id() + " の変数が未解決です: " + unresolved
                    + ". variables に値を指定してください。";
        }
        if (block.kind() == ReusableBlock.Kind.API_CALL
                && !resolver.signatureExists(block.apiSignature())) {
            return "テンプレート " + block.id() + " の API が API 库に存在しません: "
                    + block.apiSignature();
        }
        return "テンプレート " + block.id() + " を採用できませんでした";
    }
}
