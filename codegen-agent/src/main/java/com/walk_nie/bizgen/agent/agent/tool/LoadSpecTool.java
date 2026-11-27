package com.walk_nie.bizgen.agent.agent.tool;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolDefinition;
import com.walk_nie.bizgen.agent.agent.ToolHandler;
import com.walk_nie.bizgen.agent.agent.ToolResult;
import com.walk_nie.bizgen.agent.spec.SpecCatalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * spec/{api,dict,template,example}/*.md を function tool として公開する。
 * ファイル名はディレクトリ走査ではなくカタログ照合で確定し、パス走査は一切許可しない。
 * 本文の {{変数}} はコード側で事前に展開済み。
 */
public final class LoadSpecTool implements ToolHandler {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("kind", ToolDefinition.stringProperty(
                "資料種別。api / dict / template / example のいずれか（必須）。"));
        properties.put("name", ToolDefinition.stringProperty(
                "ファイル名（拡張子不要）。省略するとその種別の資料一覧を返す。"));
        return new ToolDefinition(
                "load_spec",
                "spec/api（API シグネチャ）、spec/dict（用語）、spec/template（テンプレート）、"
                        + "spec/example（機能インスタンス）の md を一覧または名前指定で読む。"
                        + "コードを書く前に必要な資料だけを取得すること。",
                ToolDefinition.objectSchema(properties, Collections.singletonList("kind")),
                Collections.singletonList("kind"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, AgentContext context) {
        String kindToken = ToolSupport.str(arguments, "kind").toLowerCase(Locale.ROOT);
        String name = ToolSupport.str(arguments, "name");
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return ToolResult.fail(null, "ファイル名にパス指定は使えません: " + name);
        }
        SpecCatalog.Kind kind;
        try {
            kind = SpecCatalog.Kind.fromDir(kindToken);
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(null, "不明な資料種別です: " + kindToken
                    + "（api / dict / template / example）");
        }
        try {
            SpecCatalog catalog = SpecCatalog.load(context.config().specRoot(),
                    context.variables(), 0);
            StringBuilder out = new StringBuilder();
            out.append("資料種別: ").append(kind.dir()).append("（").append(kind.label()).append("）\n");
            if (name.isEmpty()) {
                int count = 0;
                for (SpecCatalog.SpecDoc doc : catalog.docs()) {
                    if (doc.kind() != kind) {
                        continue;
                    }
                    out.append("- ").append(doc.name()).append("  (")
                            .append(doc.body().length()).append(" 文字) ").append(doc.relativePath()).append('\n');
                    count++;
                }
                if (count == 0) {
                    out.append("（該当資料なし）\n");
                }
                return ToolResult.ok(null, ToolSupport.truncate(out.toString(), 12000));
            }
            String needle = name.toLowerCase(Locale.ROOT);
            if (needle.endsWith(".md")) {
                needle = needle.substring(0, needle.length() - 3);
            }
            for (SpecCatalog.SpecDoc doc : catalog.docs()) {
                if (doc.kind() != kind || !doc.name().toLowerCase(Locale.ROOT).equals(needle)) {
                    continue;
                }
                out.append("----- ").append(doc.relativePath()).append(" -----\n")
                        .append(doc.body());
                return ToolResult.ok(null, ToolSupport.truncate(out.toString(), 16000));
            }
            return ToolResult.fail(null, "資料が見つかりません: spec/" + kind.dir() + "/" + name + ".md");
        } catch (Exception e) {
            return ToolResult.fail(null, "spec 資料の読み込みに失敗: " + e.getMessage());
        }
    }
}
