package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.reuse.ReusableBlock;
import com.walk_nie.bizgen.agent.reuse.ReuseResolver;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 再利用可能なコード片 / API 呼び出しテンプレートを検索する function tool。
 *
 * <p>候補の提示だけを行い、実際の採用・変数展開は apply_block_template が行う。
 * モデルはテンプレートを自分で書き換えてはならない。</p>
 */
public final class FindReusableBlockTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("query", ToolDefinition.stringProperty(
                "検索キーワード。日本語の処理内容 + 英語メソッド名を渡す。例: '受注ID 必須チェック requireNonBlank'"));
        properties.put("kind", ToolDefinition.stringProperty(
                "CODE または API_CALL。省略すれば両方を検索する。"));
        return new ToolDefinition(
                "find_reusable_block",
                "spec/block に登録された再利用テンプレートを決定論的に検索する。"
                        + "該当するテンプレートを使う場合は、次に apply_block_template を呼ぶこと。",
                ToolDefinition.objectSchema(properties, Collections.singletonList("query")),
                Collections.singletonList("query"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String query = ToolSupport.str(arguments, "query");
        if (query.isEmpty()) {
            return ToolResult.fail(null, "query が空です");
        }
        ReusableBlock.Kind kind = ReusableBlock.Kind.fromToken(ToolSupport.str(arguments, "kind"));
        try {
            ReuseResolver resolver = new ReuseResolver(context.config());
            com.walk_nie.bizgen.agent.reuse.ReusableBlockCatalog catalog = resolver.catalog();
            List<com.walk_nie.bizgen.agent.reuse.ReusableBlockCatalog.Scored> matched =
                    catalog.match(query, context.config().reuseTopN(), kind);
            StringBuilder out = new StringBuilder();
            out.append("検索キーワード: ").append(query).append('\n');
            out.append(catalog.promptText(matched));
            out.append("テンプレートは書き換え禁止です。apply_block_template で展開済み本文を取得してください。\n");
            return ToolResult.ok(null, ToolSupport.truncate(out.toString(),
                    context.config().javaIndexMaxResultChars()));
        } catch (IOException e) {
            return ToolResult.fail(null, "再利用テンプレートの読み込みに失敗: " + e.getMessage());
        }
    }
}
