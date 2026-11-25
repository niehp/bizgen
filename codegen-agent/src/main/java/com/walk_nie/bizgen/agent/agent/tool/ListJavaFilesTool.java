package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 索引済み Java ファイルの一覧。 */
public final class ListJavaFilesTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("prefix", ToolDefinition.stringProperty("パスの部分一致文字列。空なら全件。"));
        props.putAll(JavaToolSupport.optionalLimit("返す最大件数。省略時は設定の既定値。"));
        return new ToolDefinition("list_java_files",
                "索引済みの Java ファイル一覧を返す。まず対象ファイルを特定するときに使う。",
                ToolDefinition.objectSchema(props, new ArrayList<String>()),
                new ArrayList<String>());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            int limit = JavaToolSupport.limit(arguments, context, context.config().javaIndexMaxResults());
            String content = JavaToolSupport.service(context).search()
                    .listFiles(ToolSupport.str(arguments, "prefix"), limit);
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
