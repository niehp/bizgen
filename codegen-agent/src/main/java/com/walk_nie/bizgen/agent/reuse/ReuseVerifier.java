package com.walk_nie.bizgen.agent.reuse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 再利用テンプレートが最終コードにそのまま入っていることを決定論的に検証する。 */
public final class ReuseVerifier {

    private ReuseVerifier() {
    }

    /** 空白・改行・タブだけを除く。コードのトークン自体は一切変更しない。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * すべての再利用片が生成コードに含まれるか検証する。
     * 含まれないものがあれば blockId を返す（空なら OK）。
     */
    public static List<String> missing(String generatedCode, List<ReuseSelection> selections) {
        List<String> result = new ArrayList<String>();
        if (selections == null || selections.isEmpty()) {
            return result;
        }
        String haystack = normalize(generatedCode).toLowerCase(Locale.ROOT);
        for (ReuseSelection selection : selections) {
            String needle = selection.normalizedCode().toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || !haystack.contains(needle)) {
                result.add(selection.blockId());
            }
        }
        return result;
    }
}
