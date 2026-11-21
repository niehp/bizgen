package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.md.DesignLevel;
import com.walk_nie.bizgen.agent.md.MdDoc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 5 段階の設計 md 一覧を返すツール（LLM は必要な文書を後で load_design_doc で読む）。 */
public final class ListDesignDocsTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "list_design_docs",
                "設計mdの一覧を返す。レベルは java-common / project-common / usecase / class / method の5段階。"
                        + "必要な文書を特定したら load_design_doc で本文を読むこと。",
                ToolDefinition.objectSchema(Collections.<String, Object>emptyMap(),
                        new ArrayList<String>()),
                new ArrayList<String>());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        StringBuilder out = new StringBuilder();
        try {
            for (DesignLevel level : DesignLevel.values()) {
                out.append("[").append(level.label()).append("] ").append(level.folder()).append('\n');
                List<MdDoc> docs = context.repository().list(level);
                for (MdDoc doc : docs) {
                    out.append("  - ").append(doc.name());
                    if (!doc.title().isEmpty()) {
                        out.append(" : ").append(doc.title());
                    }
                    out.append('\n');
                }
            }
            return ToolResult.ok(null, out.toString());
        } catch (IOException e) {
            return ToolResult.fail(null, "設計md一覧の読み込みに失敗: " + e.getMessage());
        }
    }
}
