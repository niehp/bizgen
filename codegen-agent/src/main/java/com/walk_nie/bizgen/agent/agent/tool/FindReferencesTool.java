package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** クラス / メソッドの参照と呼び出し関係。 */
public final class FindReferencesTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("symbol", ToolDefinition.stringProperty("参照を調べるクラス名またはメソッド名。"));
        props.put("includeCalls", ToolDefinition.booleanProperty(
                "true なら呼び出し関係（呼び出し元 → 被呼び出し）も返す。既定 false。"));
        props.putAll(JavaToolSupport.optionalLimit("返す最大件数。"));
        return new ToolDefinition("find_references",
                "クラス・メソッドがどこで使われているかを索引から返す。改名や削除の影響範囲確認に使う。",
                ToolDefinition.objectSchema(props, Arrays.asList("symbol")), Arrays.asList("symbol"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            int limit = JavaToolSupport.limit(arguments, context, 20);
            Object includeCalls = arguments.get("includeCalls");
            String symbol = ToolSupport.str(arguments, "symbol");
            String content = Boolean.TRUE.equals(includeCalls)
                    ? JavaToolSupport.service(context).search().calls(symbol, limit)
                    : JavaToolSupport.service(context).search().references(symbol, limit);
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
