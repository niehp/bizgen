package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.generator.java.ExistingCodeSearcher;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 既存 Java プロジェクトから類似コードを決定論的に検索して返すツール。 */
public final class SearchSimilarCodeTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("keywords", ToolDefinition.arrayProperty(
                "検索キーワード。クラス名・メソッド名・日本語名・英物理名を複数指定する。"));
        props.put("target_file", ToolDefinition.stringProperty(
                "修正対象の既存ファイル（相対パス）。検索結果からは除外される。省略可。"));
        return new ToolDefinition(
                "search_similar_code",
                "既存Javaから類似実装を検索する。Embedding を使わずキーワード一致と固定スコアで決定的に並べる。"
                        + "結果は設定の上限までしか返さない。",
                ToolDefinition.objectSchema(props, Arrays.asList("keywords")),
                Arrays.asList("keywords"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        List<String> keywords = ToolSupport.strings(arguments, "keywords");
        if (keywords.isEmpty()) {
            return ToolResult.fail(null, "keywords が空です");
        }
        String target = ToolSupport.str(arguments, "target_file");
        SimilarCodeResult result = ExistingCodeSearcher.searchByKeywords(
                context.config(), keywords, target.isEmpty() ? null : target);
        return ToolResult.ok(null, ToolSupport.truncate(result.promptText(),
                context.config().maxSimilarCodeChars() + 2000));
    }
}
