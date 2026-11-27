package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.term.TermDictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日本語 -> 英語 の用語変換を function tool として公開する。
 * 変換は术语库に載っている対訳だけを使い、モデルによる推測翻訳を禁止する。
 */
public final class TranslateTermsTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("text", ToolDefinition.stringProperty(
                "翻訳したい日本語の文章。表名・項目名・機能名なども可。省略可。"));
        properties.put("terms", ToolDefinition.arrayProperty(
                "個別に確認したい用語の配列。省略可。"));
        return new ToolDefinition(
                "translate_terms",
                "术语库の対訳だけを使って日本語を英語へ変換する。"
                        + "返された対訳に無い語は英訳せず、そのまま日本語で残すこと。",
                ToolDefinition.objectSchema(properties, Collections.<String>emptyList()),
                Collections.<String>emptyList());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String text = ToolSupport.str(arguments, "text");
        List<String> terms = ToolSupport.strings(arguments, "terms");
        if (text.isEmpty() && terms.isEmpty()) {
            return ToolResult.fail(null, "text または terms のどちらかを指定してください");
        }
        try {
            TermDictionary dictionary = TermDictionary.load(context.config().termRoot());
            StringBuilder out = new StringBuilder();
            out.append("术语库に登録された対訳のみを使用しています。\n");
            if (!text.isEmpty()) {
                out.append("翻訳結果: ").append(dictionary.translate(text)).append('\n');
            }
            List<TermDictionary.TermEntry> matched = new ArrayList<TermDictionary.TermEntry>();
            if (!text.isEmpty()) {
                matched.addAll(dictionary.match(text));
            }
            for (String term : terms) {
                List<TermDictionary.TermEntry> found = dictionary.match(term);
                if (found.isEmpty()) {
                    out.append("未登録語: ").append(term).append('\n');
                } else {
                    matched.addAll(found);
                }
            }
            out.append("\n用語対訳:\n");
            out.append(dictionary.promptText(dedupe(matched)));
            return ToolResult.ok(null, ToolSupport.truncate(out.toString(), 12000));
        } catch (Exception e) {
            return ToolResult.fail(null, "术语库の読み込みに失敗: " + e.getMessage());
        }
    }

    private static List<TermDictionary.TermEntry> dedupe(List<TermDictionary.TermEntry> entries) {
        List<TermDictionary.TermEntry> result = new ArrayList<TermDictionary.TermEntry>();
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        for (TermDictionary.TermEntry entry : entries) {
            String key = entry.japanese() + "->" + entry.english();
            if (!seen.containsKey(key)) {
                seen.put(key, Boolean.TRUE);
                result.add(entry);
            }
        }
        return result;
    }
}
