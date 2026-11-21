package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.md.DesignLevel;
import com.walk_nie.bizgen.agent.md.MdDoc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 指定レベルの設計 md 本文を読み込むツール。本文は文字数上限を付けて返す。 */
public final class LoadDesignDocTool implements ToolHandler {

    private static final int MAX_CHARS = 12000;

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("level", ToolDefinition.stringProperty(
                "設計レベル: java-common / project-common / usecase / class / method"));
        props.put("name", ToolDefinition.stringProperty("md ファイル名（拡張子 .md は不要）"));
        return new ToolDefinition(
                "load_design_doc",
                "設計mdの本文を読む。まず list_design_docs で名前を確認するとよい。",
                ToolDefinition.objectSchema(props, Arrays.asList("level", "name")),
                Arrays.asList("level", "name"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String levelToken = ToolSupport.str(arguments, "level");
        String name = ToolSupport.str(arguments, "name");
        DesignLevel level = DesignLevel.fromToken(levelToken);
        if (level == null) {
            return ToolResult.fail(null, "不明な設計レベルです: " + levelToken);
        }
        try {
            MdDoc doc = context.repository().load(level, name);
            String text = "[" + level.label() + "] " + doc.name() + "\n\n" + doc.body();
            return ToolResult.ok(null, ToolSupport.truncate(text, MAX_CHARS));
        } catch (IOException e) {
            return ToolResult.fail(null, "設計mdの読み込みに失敗: " + e.getMessage());
        }
    }
}
