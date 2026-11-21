package com.walk_nie.bizgen.agent.md;

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
            "title", "mode", "targetfile", "targetmethod", "outputfile",
            "designs", "designrefs", "variables", "sqltype", "tables", "prompt", "out"));

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
}
