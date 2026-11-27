package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.md.DesignLevel;
import com.walk_nie.bizgen.agent.md.MdDoc;
import com.walk_nie.bizgen.agent.term.TermDictionary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 機能記述 / 設計 md に出てくる日本語用語の対訳表を組み立てて返す function tool。
 * モデルへ渡す用語情報をツール経由に統一するための入口。
 */
public final class InjectTermsTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("text", ToolDefinition.stringProperty("用語を拾い出す日本語テキスト。省略可。"));
        properties.put("level", ToolDefinition.stringProperty(
                "設計 md のレベル（java-common / project-common / usecase / class / method）。"
                        + "name と併用する。"));
        properties.put("name", ToolDefinition.stringProperty("設計 md のファイル名（拡張子不要）。level と併用する。"));
        return new ToolDefinition(
                "inject_terms",
                "機能記述または設計 md 中の日本語用語に対する术语库の対訳表を返す。"
                        + "クラス名・変数名・日本語コメントはこの対訳だけを使うこと。",
                ToolDefinition.objectSchema(properties, Collections.<String>emptyList()),
                Collections.<String>emptyList());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String text = ToolSupport.str(arguments, "text");
        String levelToken = ToolSupport.str(arguments, "level");
        String name = ToolSupport.str(arguments, "name");
        String source;
        if (!text.isEmpty()) {
            source = text;
        } else if (!levelToken.isEmpty() && !name.isEmpty()) {
            DesignLevel level = DesignLevel.fromToken(levelToken);
            if (level == null) {
                return ToolResult.fail(null, "不明な設計レベルです: " + levelToken);
            }
            try {
                MdDoc doc = context.repository().load(level, name);
                source = doc.body();
            } catch (Exception e) {
                return ToolResult.fail(null, "設計 md の読み込みに失敗: " + e.getMessage());
            }
        } else {
            return ToolResult.fail(null, "text または level+name を指定してください");
        }
        try {
            TermDictionary dictionary = TermDictionary.load(context.config().termRoot());
            StringBuilder out = new StringBuilder();
            out.append("用語対訳（术语库に登録されたものだけ）:\n");
            out.append(dictionary.promptText(dictionary.match(source)));
            if (dictionary.match(source).isEmpty()) {
                out.append("（該当なし。未登録語は勝手に英訳しないこと）\n");
            }
            return ToolResult.ok(null, ToolSupport.truncate(out.toString(), 12000));
        } catch (Exception e) {
            return ToolResult.fail(null, "术语库の読み込みに失敗: " + e.getMessage());
        }
    }
}
