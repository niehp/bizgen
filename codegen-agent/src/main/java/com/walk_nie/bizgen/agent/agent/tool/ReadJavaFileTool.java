package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 既存 Java ファイルを読むツール。索引を優先し、未索引なら設定ルート配下の実ファイルを読む。
 * いずれにせよ設定されたルート外へのアクセスは拒否する。
 */
public final class ReadJavaFileTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("path", ToolDefinition.stringProperty(
                "索引ルートからの相対パス。例: com/walk_nie/sample/OrderService.java"));
        return new ToolDefinition("read_java_file",
                "既存Javaファイルの全文を読む。追加/修正対象のメソッドを確認するときに使う。"
                        + "設定されたルート配下の .java ファイルのみ読み取り可能。",
                ToolDefinition.objectSchema(props, Arrays.asList("path")), Arrays.asList("path"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        try {
            String relative = ToolSupport.str(arguments, "path").replace('\\', '/');
            if (relative.isEmpty()) {
                return ToolResult.fail(null, "path が空です");
            }
            if (relative.contains("..")) {
                return ToolResult.fail(null, "設定されたルート外のファイルは読めません: " + relative);
            }
            String content = JavaToolSupport.service(context).search().readFile(relative);
            return JavaToolSupport.ok(content, context.config().javaIndexMaxResultChars());
        } catch (Exception e) {
            return ToolResult.fail(null, e.getMessage());
        }
    }
}
