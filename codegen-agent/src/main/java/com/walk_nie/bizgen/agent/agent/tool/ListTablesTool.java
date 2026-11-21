package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 利用可能な表構造 md の一覧を返すツール。 */
public final class ListTablesTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "list_tables",
                "SQL 生成に使える表構造 md の名前（日本語表名）を返す。",
                ToolDefinition.objectSchema(new LinkedHashMap<String, Object>(),
                        new ArrayList<String>()),
                new ArrayList<String>());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            List<String> names = context.repository().schemaNames();
            Collections.sort(names);
            return ToolResult.ok(null, "利用可能な表: " + names);
        } catch (IOException e) {
            return ToolResult.fail(null, "表一覧の読み込みに失敗: " + e.getMessage());
        }
    }
}
