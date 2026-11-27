package com.walk_nie.bizgen.agent.md;

import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 機能 md を型付きにしたもの。変数定義・設計参照・生成モード・対象ファイルを保持する。
 */
public final class FeatureSpec {

    private static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
            "title", "mode", "targetfile", "targetmethod", "outputfile", "generate",
            "designs", "designrefs", "variables", "sqltype", "tables", "fields",
            "desc", "description", "prompt", "out", "shape"));

    private final MdDoc doc;
    private final Map<String, String> variables;
    private final List<DesignRef> designRefs;
    private final String mode;
    private final String targetFile;
    private final String targetMethod;
    private final String outputFile;

    public FeatureSpec(MdDoc doc) {
        this.doc = doc;
        Map<String, String> vars = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : doc.frontMatter().entrySet()) {
            if (!RESERVED.contains(entry.getKey().toLowerCase())) {
                vars.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        this.variables = vars;
        List<DesignRef> refs = new ArrayList<DesignRef>();
        for (String token : doc.listValue("designs")) {
            refs.add(DesignRef.parse(token));
        }
        for (String token : doc.listValue("designRefs")) {
            refs.add(DesignRef.parse(token));
        }
        this.designRefs = refs;
        this.mode = doc.stringValue("mode", "");
        this.targetFile = doc.stringValue("targetFile", "");
        this.targetMethod = doc.stringValue("targetMethod", "");
        this.outputFile = doc.stringValue("outputFile", "");
    }

    public MdDoc doc() {
        return doc;
    }

    public String title() {
        return doc.title().isEmpty() ? doc.name() : doc.title();
    }

    public String body() {
        return doc.body();
    }

    public Map<String, String> variables() {
        return variables;
    }

    public List<DesignRef> designRefs() {
        return designRefs;
    }

    /** NEW_FILE / ADD_METHOD / MODIFY_METHOD。空なら LLM に分類させる。 */
    public String mode() {
        return mode;
    }

    public String targetFile() {
        return targetFile;
    }

    public String targetMethod() {
        return targetMethod;
    }

    public String outputFile() {
        return outputFile;
    }

    /** java / sql / both。省略時は java とみなす（总体流程の入口）。 */
    public String generate() {
        return doc.stringValue("generate", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** SQL 種別（select / update / delete）。 */
    public String sqlType() {
        return doc.stringValue("sqltype", "").trim();
    }

    /** 日本語の対象表名（カンマ区切り）。 */
    public String tables() {
        return doc.stringValue("tables", "").trim();
    }

    /** SQL の対象フィールド（日本語名 / 表.フィールド名）。list 形式と CSV の両方を受け付ける。 */
    public List<String> fields() {
        List<String> result = new ArrayList<String>();
        for (String entry : doc.listValue("fields")) {
            result.addAll(StrUtil.splitCsv(entry.replace("`", "")));
        }
        return result;
    }

    /** Java のクラス／メソッド／処理フローを本文から型付きで取り出す（変数展開済み）。 */
    public JavaFeatureOutline javaOutline() {
        VariableTable table = new VariableTable();
        table.putAll(variables);
        return JavaFeatureOutline.parse(table.resolveText(body()));
    }

    /** SQL 生成の説明。無ければ md 本文の先頭 400 文字を使う。 */
    public String sqlDescription() {
        String value = doc.stringValue("desc", doc.stringValue("description", ""));
        if (!value.trim().isEmpty()) {
            return value.trim();
        }
        String body = body().trim();
        return body.length() <= 400 ? body : body.substring(0, 400);
    }

    /** SQL 雛形（省略可）。 */
    public String sqlShape() {
        return doc.stringValue("shape", "").trim();
    }
}
