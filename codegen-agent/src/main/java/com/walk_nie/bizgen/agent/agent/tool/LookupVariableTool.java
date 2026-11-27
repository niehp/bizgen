package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.variable.NamingStyle;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 機能 md の変数表を参照する function tool。
 * 命名スタイル変換はコード側の {@link VariableTable} が行い、モデルに推測させない。
 */
public final class LookupVariableTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("name", ToolDefinition.stringProperty(
                "変数名。{{applicationId}} のような波括弧付き、または applicationId|大驼峰 のようなスタイル指定も可。"));
        properties.put("style", ToolDefinition.stringProperty(
                "省略可。首字母大写 / 首字母小写 / 大驼峰 / 小驼峰 / 大蛇形 / 小蛇形 のいずれか。"
                        + "name に |スタイル が含まれる場合はそちらを優先する。"));
        return new ToolDefinition(
                "lookup_variable",
                "機能 md で定義された変数の確定値を取得し、指定スタイルへ変換して返す。"
                        + "未定義の変数はエラーになるので、勝手に値を推測しないこと。",
                ToolDefinition.objectSchema(properties, Collections.singletonList("name")),
                Collections.singletonList("name"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String expression = ToolSupport.str(arguments, "name").replace("{", "").replace("}", "").trim();
        String styleToken = ToolSupport.str(arguments, "style");
        int pipe = expression.indexOf('|');
        if (pipe >= 0) {
            if (styleToken.isEmpty()) {
                styleToken = expression.substring(pipe + 1).trim();
            }
            expression = expression.substring(0, pipe).trim();
        }
        if (expression.isEmpty()) {
            return ToolResult.fail(null, "変数名が空です");
        }

        VariableTable variables = context.variables();
        String raw = variables.get(expression);
        if (raw == null) {
            return ToolResult.fail(null, "未定義の変数です: " + expression
                    + "（定義済み: " + variables.snapshot().keySet() + "）");
        }

        StringBuilder out = new StringBuilder();
        out.append("変数: ").append(expression).append(" = ").append(raw).append('\n');
        if (!styleToken.isEmpty()) {
            NamingStyle style = parseStyle(styleToken);
            if (style == null) {
                return ToolResult.fail(null, "不明な命名スタイルです: " + styleToken);
            }
            out.append("指定スタイル(").append(style.japaneseLabel()).append("): ")
                    .append(style.convert(raw)).append('\n');
        }
        out.append("全スタイル:\n");
        for (NamingStyle style : NamingStyle.values()) {
            out.append("- ").append(style.japaneseLabel()).append(": ")
                    .append(style.convert(raw)).append('\n');
        }
        return ToolResult.ok(null, ToolSupport.truncate(out.toString(), 8000));
    }

    /** 未知のスタイルは黙って既定値にせず、必ずエラーにする（決定論的な引数検証）。 */
    private static NamingStyle parseStyle(String token) {
        String normalized = token.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\u4e00-\u9fff]", "");
        for (NamingStyle style : NamingStyle.values()) {
            if (normalized.equals(style.englishLabel().toLowerCase(Locale.ROOT))
                    || normalized.equals(style.name().toLowerCase(Locale.ROOT))
                    || normalized.equals(style.japaneseLabel())) {
                return style;
            }
        }
        if ("uc".equals(normalized)) {
            return NamingStyle.UPPER_CAMEL;
        }
        if ("lc".equals(normalized)) {
            return NamingStyle.LOWER_CAMEL;
        }
        if ("us".equals(normalized)) {
            return NamingStyle.UPPER_SNAKE;
        }
        if ("ls".equals(normalized)) {
            return NamingStyle.LOWER_SNAKE;
        }
        return null;
    }
}
