package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.api.ApiCatalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 库（md）を検索する function tool。
 * モデルは「どの API を呼ぶか」を自分で選ぶが、返されるシグネチャは必ず md に書かれた実在のものだけ。
 * 存在しない API を生成させないための白名单として機能する。
 */
public final class FindApiTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("query", ToolDefinition.stringProperty(
                "検索キーワード。日本語の処理内容、クラス名、メソッド名のいずれも可。"
                        + "例: '受注 検索 findById'"));
        properties.put("className", ToolDefinition.stringProperty(
                "クラス名で絞り込む場合のみ指定。省略可。"));
        return new ToolDefinition(
                "find_api",
                "既存 API 库から利用可能なメソッドシグネチャを検索する。コードを書く前に必ず呼び、"
                        + "返されたシグネチャ以外の API を呼ばないこと。",
                ToolDefinition.objectSchema(properties, Collections.singletonList("query")),
                Collections.singletonList("query"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String query = ToolSupport.str(arguments, "query");
        String className = ToolSupport.str(arguments, "className");
        try {
            ApiCatalog catalog = ApiCatalog.load(context.config().apiRoot());
            List<ApiCatalog.ApiEntry> matched = catalog.match(query, context.config().apiTopN());
            if (!className.isEmpty()) {
                List<ApiCatalog.ApiEntry> filtered = new ArrayList<ApiCatalog.ApiEntry>();
                String needle = className.toLowerCase(java.util.Locale.ROOT);
                for (ApiCatalog.ApiEntry entry : matched) {
                    if (entry.className().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                        filtered.add(entry);
                    }
                }
                matched = filtered;
            }
            context.recordApis(matched);
            StringBuilder out = new StringBuilder();
            out.append("検索キーワード: ").append(query).append('\n');
            out.append("API 库に登録された既存 API のみを返しています。\n");
            out.append("命中: ").append(matched.size()).append(" 件\n\n");
            out.append(catalog.promptText(matched));
            return ToolResult.ok(null, ToolSupport.truncate(out.toString(), 12000));
        } catch (IOException e) {
            return ToolResult.fail(null, "API 库の読み込みに失敗: " + e.getMessage());
        }
    }
}
