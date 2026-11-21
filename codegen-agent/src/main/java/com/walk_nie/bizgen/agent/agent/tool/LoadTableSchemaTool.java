package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指定された日本語表名の表構造 md を読み込むツール。
 * 渡す表の数は設定上限（gen.maxTables）で必ず制限し、14B のコンテキストを守る。
 */
public final class LoadTableSchemaTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("tables", ToolDefinition.arrayProperty(
                "必要な日本語表名の配列。SQL に実際に登場する表だけを指定すること。"));
        return new ToolDefinition(
                "load_table_schema",
                "表構造（日本語名→英語物理名・型・備考）を読む。SQLに必要な表だけを指定すること。"
                        + "上限を超える数は返さない。",
                ToolDefinition.objectSchema(props, Arrays.asList("tables")),
                Arrays.asList("tables"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        List<String> tables = ToolSupport.strings(arguments, "tables");
        if (tables.isEmpty()) {
            return ToolResult.fail(null, "tables が空です");
        }
        int max = context.config().maxTables();
        if (tables.size() > max) {
            return ToolResult.fail(null, "指定表数 " + tables.size() + " が上限 " + max + " を超えています。"
                    + "必要な表だけを選んで再呼び出ししてください（暗黙の切り捨てはしません）。");
        }
        StringBuilder out = new StringBuilder();
        for (String table : tables) {
            try {
                out.append("===== ").append(table).append(" =====\n")
                        .append(context.repository().schema(table)).append("\n\n");
            } catch (IOException e) {
                return ToolResult.fail(null, "表構造の読み込みに失敗 (" + table + "): " + e.getMessage());
            }
        }
        return ToolResult.ok(null, ToolSupport.truncate(out.toString(), 16000));
    }
}
