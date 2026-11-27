package com.walk_nie.bizgen.agent.reuse;

import com.walk_nie.bizgen.agent.json.Json;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 確定した再利用の結果。監査・人工レビュー・最終コード検証に使う。 */
public final class ReuseSelection {

    private final String blockId;
    private final ReusableBlock.Kind kind;
    private final String code;
    private final String apiSignature;
    private final int score;
    private final String source;
    private final List<String> unresolved;

    public ReuseSelection(String blockId, ReusableBlock.Kind kind, String code,
                          String apiSignature, int score, String source,
                          List<String> unresolved) {
        this.blockId = StrUtil.trimToEmpty(blockId);
        this.kind = kind;
        this.code = code == null ? "" : code;
        this.apiSignature = StrUtil.trimToEmpty(apiSignature);
        this.score = score;
        this.source = StrUtil.trimToEmpty(source);
        this.unresolved = Collections.unmodifiableList(
                unresolved == null ? new ArrayList<String>() : new ArrayList<String>(unresolved));
    }

    public String blockId() {
        return blockId;
    }

    public ReusableBlock.Kind kind() {
        return kind;
    }

    /** 変数展開済みの、そのまま使うべき Java コード片。 */
    public String code() {
        return code;
    }

    public String apiSignature() {
        return apiSignature;
    }

    public int score() {
        return score;
    }

    public String source() {
        return source;
    }

    public List<String> unresolved() {
        return unresolved;
    }

    public boolean hasUnresolved() {
        return !unresolved.isEmpty();
    }

    /** 字面の改行・インデント差を無視した比較用。ロジック文字列はそのまま見る。 */
    public String normalizedCode() {
        return ReuseVerifier.normalize(code);
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("blockId", blockId);
        item.put("kind", kind == null ? "" : kind.name());
        item.put("score", score);
        item.put("apiSignature", apiSignature);
        item.put("source", source);
        item.put("code", code);
        if (!unresolved.isEmpty()) {
            item.put("unresolved", unresolved);
        }
        return item;
    }

    @Override
    public String toString() {
        return Json.stringify(toJsonObject());
    }

    /** 残存プレースホルダを检测する。 */
    public static List<String> placeholders(String text) {
        return VariableTable.placeholders(text);
    }
}
