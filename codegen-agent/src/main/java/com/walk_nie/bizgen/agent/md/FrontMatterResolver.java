package com.walk_nie.bizgen.agent.md;

import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * front matter 内の {{変数}} を、定義順に沿った複数ラウンドで解決する。
 *
 * ルール:
 * - 変数表は「外部から渡された値」を先に入れ、md 側の定義で上書きする（md を信頼する）。
 * - 予約キー（generate / targetFile など）も値の置換は行うが、変数表には登録しない。
 * - 1 ラウンドで front matter の定義順に全エントリを処理し、値が変わらなくなるまで最大
 *   MAX_ROUNDS 回繰り返す。後ろで定義した変数を前のエントリから参照できる。
 * - 未定義変数や循環参照はプレースホルダを残し、決定論的に可視化する（黙って空にしない）。
 */
public final class FrontMatterResolver {

    public static final int MAX_ROUNDS = 8;

    private static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
            "title", "mode", "targetfile", "targetmethod", "outputfile", "generate",
            "designs", "designrefs", "variables", "sqltype", "tables", "fields",
            "desc", "description", "prompt", "out", "shape"));

    private FrontMatterResolver() {
    }

    public static boolean isReserved(String key) {
        return key != null && RESERVED.contains(key.toLowerCase(Locale.ROOT));
    }

    /** 解決結果。values は置換済み front matter、variables は予約キーを除く変数表。 */
    public static final class Resolution {
        private final Map<String, Object> values;
        private final VariableTable variables;
        private final List<String> unresolved;

        public Resolution(Map<String, Object> values, VariableTable variables, List<String> unresolved) {
            this.values = values;
            this.variables = variables;
            this.unresolved = unresolved;
        }

        public Map<String, Object> values() {
            return values;
        }

        public VariableTable variables() {
            return variables;
        }

        /** 解決しきれなかったプレースホルダ式（初出順・重複なし）。 */
        public List<String> unresolved() {
            return unresolved;
        }
    }

    public static Resolution resolve(Map<String, Object> frontMatter, Map<String, String> baseVariables) {
        VariableTable variables = new VariableTable();
        if (baseVariables != null) {
            variables.putAll(baseVariables);
        }

        Map<String, Object> current = new LinkedHashMap<String, Object>();
        if (frontMatter != null) {
            for (Map.Entry<String, Object> entry : frontMatter.entrySet()) {
                current.put(entry.getKey(), copyValue(entry.getValue()));
            }
            // まず md 側の定義を登録し、外部変数より優先させる。
            // 定義順による解決はこの後のラウンドで行う。
            for (Map.Entry<String, Object> entry : current.entrySet()) {
                if (!isReserved(entry.getKey())) {
                    variables.put(entry.getKey(), asVariableText(entry.getValue()));
                }
            }
        }

        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean changed = false;
            for (Map.Entry<String, Object> entry : current.entrySet()) {
                Object resolved = resolveValue(entry.getValue(), variables);
                if (!sameValue(resolved, entry.getValue())) {
                    entry.setValue(resolved);
                    changed = true;
                }
                if (!isReserved(entry.getKey())) {
                    variables.put(entry.getKey(), asVariableText(resolved));
                }
            }
            if (!changed) {
                break;
            }
        }

        VariableTable custom = new VariableTable();
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            if (!isReserved(entry.getKey())) {
                custom.put(entry.getKey(), asVariableText(entry.getValue()));
            }
        }

        Set<String> seen = new LinkedHashSet<String>();
        for (Object value : current.values()) {
            for (String placeholder : VariableTable.placeholders(asVariableText(value))) {
                seen.add(placeholder);
            }
        }
        return new Resolution(current, custom, new ArrayList<String>(seen));
    }

    /** md 1 枚の front matter を解決した新しい MdDoc を作る（本文は変更しない）。 */
    public static MdDoc resolveDoc(MdDoc doc, Map<String, String> baseVariables) {
        Resolution resolution = resolve(doc.frontMatter(), baseVariables);
        return new MdDoc(doc.path(), doc.level(), resolution.values(), doc.body());
    }

    private static Object resolveValue(Object value, VariableTable variables) {
        if (value instanceof String) {
            return variables.resolveText((String) value);
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(resolveValue(item, variables));
            }
            return result;
        }
        return value;
    }

    private static Object copyValue(Object value) {
        if (value instanceof List) {
            return new ArrayList<Object>((List<?>) value);
        }
        return value;
    }

    private static boolean sameValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static String asVariableText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder();
            for (Object item : (List<?>) value) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(String.valueOf(item));
            }
            return out.toString();
        }
        return String.valueOf(value);
    }
}
