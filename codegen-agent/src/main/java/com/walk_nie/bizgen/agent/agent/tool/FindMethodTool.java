package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** メソッドの検索。 */
public final class FindMethodTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("query", ToolDefinition.stringProperty("メソッド名、シグネチャ、説明の一部。"));
        props.putAll(JavaToolSupport.optionalLimit("返す最大件数。"));
        return new ToolDefinition("find_method",
                "メソッドを名前・シグネチャ・Javadoc から探す。既存 API の流用可否を確認するときに使う。",
                ToolDefinition.objectSchema(props, Arrays.asList("query")), Arrays.asList("query"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            int limit = JavaToolSupport.limit(arguments, context, 20);
            String content = JavaToolSupport.service(context).search()
                    .findMethod(ToolSupport.str(arguments, "query"), limit);
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
