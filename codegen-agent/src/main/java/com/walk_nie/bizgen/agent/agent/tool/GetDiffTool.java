package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 提案された編集の差分表示。状態を PROPOSED → DIFFED へ進める。 */
public final class GetDiffTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("proposalId", ToolDefinition.stringProperty("propose_java_edit が返した提案 ID。"));
        return new ToolDefinition("get_diff",
                "編集提案の before/after 差分を返す。書き込み前の確認に必ず使う。",
                ToolDefinition.objectSchema(props, Arrays.asList("proposalId")),
                Arrays.asList("proposalId"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            String content = JavaToolSupport.service(context).edit()
                    .diff(ToolSupport.str(arguments, "proposalId"));
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
