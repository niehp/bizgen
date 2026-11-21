package com.walk_nie.bizgen.agent.agent;

import java.util.Map;

/**
 * 1 つの function tool の実装。LLM は呼び出しを選ぶだけであり、
 * 引数検証・実行・結果の文字列化はすべてこの実装（コード側）が行う。
 */
public interface ToolHandler {

    ToolDefinition definition();

    ToolResult execute(Map<String, Object> arguments, AgentContext context);
}
