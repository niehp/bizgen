package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 類似メソッド検索（決定論的トークン類似度）。 */
public final class FindSimilarMethodTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("method", ToolDefinition.stringProperty("メソッド名、または find_method が返したメソッド ID。"));
        props.putAll(JavaToolSupport.optionalLimit("返す最大件数。"));
        return new ToolDefinition("find_similar_method",
                "似た実装を持つ既存メソッドをトークン類似度で探す。新規コードの書き方の参考にする。",
                ToolDefinition.objectSchema(props, Arrays.asList("method")), Arrays.asList("method"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            int limit = JavaToolSupport.limit(arguments, context,
                    context.config().javaIndexSimilarTopN());
            String content = JavaToolSupport.service(context).search()
                    .similarMethods(ToolSupport.str(arguments, "method"), limit);
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
