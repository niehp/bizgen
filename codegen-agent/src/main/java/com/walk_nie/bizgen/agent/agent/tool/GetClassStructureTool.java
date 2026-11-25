package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** クラス構造（import / フィールド / メソッド）の取得。 */
public final class GetClassStructureTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("className", ToolDefinition.stringProperty("FQCN または単純クラス名。"));
        return new ToolDefinition("get_class_structure",
                "クラスの import・フィールド・メソッド一式を返す。新規メソッドを追加する前に構造を把握する。",
                ToolDefinition.objectSchema(props, Arrays.asList("className")),
                Arrays.asList("className"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            String content = JavaToolSupport.service(context).search()
                    .classStructure(ToolSupport.str(arguments, "className"));
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
