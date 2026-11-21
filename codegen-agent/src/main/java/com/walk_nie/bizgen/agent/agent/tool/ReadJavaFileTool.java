package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 既存 Java ファイルを読み込むツール。設定されたルート配下しか読めない。 */
public final class ReadJavaFileTool implements ToolHandler {

    private static final int MAX_CHARS = 20000;

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("path", ToolDefinition.stringProperty(
                "既存Javaルートからの相対パス。例: com/walk_nie/sample/OrderService.java"));
        return new ToolDefinition(
                "read_java_file",
                "既存Javaファイルの全文を読む。追加/修正対象のメソッドを確認するときに使う。"
                        + "設定された検索ルート配下の .java ファイルのみ読み取り可能。",
                ToolDefinition.objectSchema(props, Arrays.asList("path")),
                Arrays.asList("path"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String relative = ToolSupport.str(arguments, "path").replace('\\', '/');
        if (relative.isEmpty()) {
            return ToolResult.fail(null, "path が空です");
        }
        Path root = context.config().searchJavaRoot() != null
                ? context.config().searchJavaRoot() : context.config().existingJavaRoot();
        if (root == null) {
            return ToolResult.fail(null, "既存Javaルートが設定されていません");
        }
        root = root.toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize().toAbsolutePath();
        if (!target.startsWith(root)) {
            return ToolResult.fail(null, "設定されたルート外のファイルは読めません: " + relative);
        }
        if (!target.getFileName().toString().toLowerCase().endsWith(".java")) {
            return ToolResult.fail(null, "Java ファイルのみ読み取り可能です: " + relative);
        }
        if (!Files.isRegularFile(target)) {
            return ToolResult.fail(null, "ファイルが見つかりません: " + relative);
        }
        try {
            return ToolResult.ok(null, ToolSupport.truncate(
                    new String(Files.readAllBytes(target), java.nio.charset.StandardCharsets.UTF_8),
                    MAX_CHARS));
        } catch (IOException e) {
            return ToolResult.fail(null, "読み込みに失敗: " + e.getMessage());
        }
    }
}
