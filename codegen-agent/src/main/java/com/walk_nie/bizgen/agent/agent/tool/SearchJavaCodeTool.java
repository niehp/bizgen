package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** クラス・メソッド・フィールド・本文を横断するキーワード検索。 */
public final class SearchJavaCodeTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("query", ToolDefinition.stringProperty("検索キーワード。日本語コメントにも一致する。"));
        props.putAll(JavaToolSupport.optionalLimit("返す最大件数。"));
        return new ToolDefinition("search_java_code",
                "Java コードをキーワードで横断検索する。クラス名・メソッド名・Javadoc・本文が対象。",
                ToolDefinition.objectSchema(props, Arrays.asList("query")), Arrays.asList("query"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            int limit = JavaToolSupport.limit(arguments, context, 20);
            String content = JavaToolSupport.service(context).search()
                    .keyword(ToolSupport.str(arguments, "query"), limit);
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
