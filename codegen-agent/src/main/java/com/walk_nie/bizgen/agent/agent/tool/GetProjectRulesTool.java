package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 五级設計 md からプロジェクト規約を取得する。 */
public final class GetProjectRulesTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("level", ToolDefinition.stringProperty(
                "省略可。java-common / project-common / usecase / class / method のいずれか。"
                        + "省略時は全レベルを返す。"));
        return new ToolDefinition("get_project_rules",
                "Java 共通・プロジェクト共通・ユースケース・クラス・メソッドの設計規約 md を返す。",
                ToolDefinition.objectSchema(props, new ArrayList<String>()), new ArrayList<String>());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            String content = JavaToolSupport.service(context).search().projectRules();
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
