package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.codeintel.JavaEditService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java 編集の提案。ファイルは書き換えない。承認トークンはツール結果へ出さない。
 */
public final class ProposeJavaEditTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("path", ToolDefinition.stringProperty("修正対象の Java 相対パス。"));
        props.put("summary", ToolDefinition.stringProperty("変更内容の短い説明（日本語）。"));
        props.put("rationale", ToolDefinition.stringProperty("なぜ変更が必要かの説明（日本語）。"));
        props.put("newContent", ToolDefinition.stringProperty(
                "ファイル全体を置換する場合の新しい Java 本文。methodName/newMethod とは排他。"));
        props.put("methodName", ToolDefinition.stringProperty("メソッド単位で修正する場合のメソッド名。"));
        props.put("newMethod", ToolDefinition.stringProperty("追加または置換するメソッド本文。"));
        return new ToolDefinition("propose_java_edit",
                "Java の変更案を作成する。ディスクは書き換えず、差分確認と人間の承認を要求する。"
                        + "newMethod / newContent には「何をするか・なぜそうするか」を書いた日本語 Javadoc を含めること。",
                ToolDefinition.objectSchema(props, Arrays.asList("path", "summary", "rationale")),
                Arrays.asList("path", "summary", "rationale"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            JavaEditService.ProposeOutcome outcome = JavaToolSupport.service(context).edit().propose(
                    ToolSupport.str(arguments, "path"),
                    ToolSupport.str(arguments, "summary"),
                    ToolSupport.str(arguments, "rationale"),
                    ToolSupport.str(arguments, "newContent"),
                    ToolSupport.str(arguments, "methodName"),
                    ToolSupport.str(arguments, "newMethod"));
            // forHuman（トークン入り）はモデルへ渡さない。CLI 側で別途表示する。
            context.recordProposalForHuman(outcome.forHuman);
            return JavaToolSupport.ok(outcome.forModel, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return JavaToolSupport.fail(e.getMessage());
        }
    }
}
