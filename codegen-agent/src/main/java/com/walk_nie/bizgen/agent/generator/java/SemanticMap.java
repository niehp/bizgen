package com.walk_nie.bizgen.agent.generator.java;

import com.walk_nie.bizgen.agent.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 日本語名称と英語識別子の対応表。LLM の出力を型に落としてから利用する。 */
public final class SemanticMap {

    public static final class NamePair {
        private final String japanese;
        private final String english;

        public NamePair(String japanese, String english) {
            this.japanese = japanese == null ? "" : japanese;
            this.english = english == null ? "" : english;
        }

        public String japanese() {
            return japanese;
        }

        public String english() {
            return english;
        }

        static NamePair fromJson(Object object) {
            Map<String, Object> map = Json.asObject(object);
            return new NamePair(Json.asString(map.get("japanese")), Json.asString(map.get("english")));
        }
    }

    private final String className;
    private final List<NamePair> fields;
    private final List<NamePair> methods;
    private final List<NamePair> tables;
    private final String notes;

    public SemanticMap(String className, List<NamePair> fields, List<NamePair> methods,
                       List<NamePair> tables, String notes) {
        this.className = className == null ? "" : className;
        this.fields = fields == null ? new ArrayList<NamePair>() : fields;
        this.methods = methods == null ? new ArrayList<NamePair>() : methods;
        this.tables = tables == null ? new ArrayList<NamePair>() : tables;
        this.notes = notes == null ? "" : notes;
    }

    public String className() {
        return className;
    }

    public List<NamePair> fields() {
        return fields;
    }

    public List<NamePair> methods() {
        return methods;
    }

    public List<NamePair> tables() {
        return tables;
    }

    public String notes() {
        return notes;
    }

    public static SemanticMap fromJson(String text) {
        Map<String, Object> root = Json.asObject(Json.parse(text));
        return new SemanticMap(
                Json.asString(root.get("className")),
                parsePairs(root.get("fields")),
                parsePairs(root.get("methods")),
                parsePairs(root.get("tables")),
                Json.asString(root.get("notes")));
    }

    private static List<NamePair> parsePairs(Object value) {
        List<NamePair> result = new ArrayList<NamePair>();
        for (Object item : Json.asArray(value)) {
            result.add(NamePair.fromJson(item));
        }
        return result;
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("className", className);
        root.put("fields", pairsToJson(fields));
        root.put("methods", pairsToJson(methods));
        root.put("tables", pairsToJson(tables));
        root.put("notes", notes);
        return Json.stringify(root);
    }

    private static List<Object> pairsToJson(List<NamePair> pairs) {
        List<Object> result = new ArrayList<Object>();
        for (NamePair pair : pairs) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("japanese", pair.japanese());
            item.put("english", pair.english());
            result.add(item);
        }
        return result;
    }
}
