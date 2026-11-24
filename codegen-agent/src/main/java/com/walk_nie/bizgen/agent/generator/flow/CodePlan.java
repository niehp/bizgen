package com.walk_nie.bizgen.agent.generator.flow;

import com.walk_nie.bizgen.agent.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 意味解析の結果である「コードの流れと機能ブロック」。
 * モデルが JSON を出しても、型・並び・状態はすべて Java 側で検証する。
 */
public final class CodePlan {

    public enum Kind {
        API, NEW_CODE;

        public static Kind fromToken(String token) {
            String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT)
                    .replace("-", "_").replace(" ", "_");
            if ("api".equals(value) || "api_call".equals(value)) {
                return API;
            }
            if ("new_code".equals(value) || "new".equals(value) || "code".equals(value)
                    || "newcode".equals(value)) {
                return NEW_CODE;
            }
            return null;
        }
    }

    public enum Status {
        PENDING, DONE
    }

    /** 1 機能ブロック。API ブロックは find_api で実在のシグネチャを確定する。 */
    public static final class Block {
        private final int index;
        private final Kind kind;
        private final String description;
        private final String apiQuery;
        private Status status = Status.PENDING;
        private String code = "";
        private String apiSignature = "";

        public Block(int index, Kind kind, String description, String apiQuery) {
            this.index = index;
            this.kind = kind;
            this.description = description == null ? "" : description;
            this.apiQuery = apiQuery == null ? "" : apiQuery;
        }

        public int index() {
            return index;
        }

        public Kind kind() {
            return kind;
        }

        public String description() {
            return description;
        }

        public String apiQuery() {
            return apiQuery;
        }

        public Status status() {
            return status;
        }

        public String code() {
            return code;
        }

        public String apiSignature() {
            return apiSignature;
        }

        public void markDone(String code, String apiSignature) {
            this.code = code == null ? "" : code;
            this.apiSignature = apiSignature == null ? "" : apiSignature;
            this.status = Status.DONE;
        }

        public Map<String, Object> toJsonObject() {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("index", index);
            item.put("kind", kind.name());
            item.put("description", description);
            item.put("apiQuery", apiQuery);
            item.put("status", status.name());
            item.put("code", code);
            item.put("apiSignature", apiSignature);
            return item;
        }
    }

    private final String className;
    private final String flow;
    private final List<Block> blocks;

    public CodePlan(String className, String flow, List<Block> blocks) {
        this.className = className == null ? "" : className;
        this.flow = flow == null ? "" : flow;
        this.blocks = Collections.unmodifiableList(new ArrayList<Block>(blocks));
    }

    public String className() {
        return className;
    }

    public String flow() {
        return flow;
    }

    public List<Block> blocks() {
        return blocks;
    }

    public boolean allDone() {
        for (Block block : blocks) {
            if (block.status() != Status.DONE) {
                return false;
            }
        }
        return true;
    }

    /** モデル出力を型に落とす。不正な kind / 欠落項目は即エラー（勝手に補完しない）。 */
    public static CodePlan fromJson(String text) {
        Map<String, Object> root = Json.asObject(Json.parse(text));
        String className = Json.asString(root.get("className"));
        String flow = Json.asString(root.get("flow"));
        if (className.trim().isEmpty()) {
            throw new IllegalArgumentException("機能ブロックに className がありません");
        }
        List<Block> blocks = new ArrayList<Block>();
        int expected = 1;
        for (Object value : Json.asArray(root.get("blocks"))) {
            Map<String, Object> item = Json.asObject(value);
            Kind kind = Kind.fromToken(Json.asString(item.get("kind")));
            String description = Json.asString(item.get("description"));
            if (kind == null) {
                throw new IllegalArgumentException("不正な機能ブロック種別: "
                        + Json.asString(item.get("kind")));
            }
            if (description.trim().isEmpty()) {
                throw new IllegalArgumentException("機能ブロック " + expected + " に説明がありません");
            }
            String query = Json.asString(item.get("apiQuery"));
            if (kind == Kind.API && query.trim().isEmpty()) {
                query = description;
            }
            blocks.add(new Block(expected++, kind, description, query));
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("機能ブロックが 1 つもありません");
        }
        return new CodePlan(className, flow, blocks);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("className", className);
        root.put("flow", flow);
        List<Object> items = new ArrayList<Object>();
        for (Block block : blocks) {
            items.add(block.toJsonObject());
        }
        root.put("blocks", items);
        return Json.stringify(root);
    }
}
