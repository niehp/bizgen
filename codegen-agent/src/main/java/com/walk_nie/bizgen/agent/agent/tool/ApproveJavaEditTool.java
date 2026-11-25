package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 編集の承認と書き込み。承認トークンは CLI が人間へ表示する値であり、
 * モデルがツール結果から得ることはできない。
 */
public final class ApproveJavaEditTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("proposalId", ToolDefinition.stringProperty("承認する提案の ID。"));
        props.put("approvalToken", ToolDefinition.stringProperty(
                "CLI が表示した承認トークン。モデルが推測してはいけない。"));
        props.put("approve", ToolDefinition.booleanProperty("true で承認、false で拒否。"));
        return new ToolDefinition("approve_java_edit",
                "差分確認済みの編集提案を承認して Java ファイルへ書き込む。"
                        + "propose → get_diff → 人間の承認 → approve の順が必須。",
                ToolDefinition.objectSchema(props, Arrays.asList("proposalId", "approve")),
                Arrays.asList("proposalId", "approve"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            Object approve = arguments.get("approve");
            boolean approved = Boolean.TRUE.equals(approve)
                    || "true".equalsIgnoreCase(String.valueOf(approve));
            String content = JavaToolSupport.service(context).edit().approve(
                    ToolSupport.str(arguments, "proposalId"),
                    ToolSupport.str(arguments, "approvalToken"), approved);
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (java.io.IOException e) {
            return JavaToolSupport.fail("ファイル書き込みに失敗しました: " + e.getMessage());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
