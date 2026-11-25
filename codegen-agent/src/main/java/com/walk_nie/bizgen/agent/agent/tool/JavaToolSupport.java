package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.codeintel.CodeIntelService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java コード知能ツール共通のヘルパ（索引取得・引数・結果上限）。 */
final class JavaToolSupport {

    private JavaToolSupport() {
    }

    static CodeIntelService service(AgentContext context) {
        CodeIntelService service = CodeIntelService.forConfig(context.config());
        service.ensureReady();
        return service;
    }

    static ToolResult ok(String content, int maxChars) {
        return ToolResult.ok(null, ToolSupport.truncate(content, maxChars));
    }

    static ToolResult fail(String message) {
        return ToolResult.fail(null, message);
    }

    static int limit(Map<String, Object> arguments, AgentContext context, int defaultValue) {
        Object value = arguments.get("limit");
        if (value instanceof Number) {
            int parsed = ((Number) value).intValue();
            if (parsed > 0) {
                return Math.min(parsed, context.config().javaIndexMaxResults());
            }
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            try {
                int parsed = Integer.parseInt(((String) value).trim());
                if (parsed > 0) {
                    return Math.min(parsed, context.config().javaIndexMaxResults());
                }
            } catch (NumberFormatException ignored) {
                // 既定値にフォールバックする
            }
        }
        return Math.min(defaultValue, context.config().javaIndexMaxResults());
    }

    static Map<String, Object> optionalLimit(String description) {
        Map<String, Object> property = ToolDefinition.integerProperty(description);
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("limit", property);
        return props;
    }

    static List<String> required(String name) {
        return Arrays.asList(name);
    }
}
