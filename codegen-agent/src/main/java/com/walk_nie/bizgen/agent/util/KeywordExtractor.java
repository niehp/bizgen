package com.walk_nie.bizgen.agent.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日本語/英語が混在する md 本文から、決定論的にキーワードを抽出する。
 * 埋め込みモデルや外部 API は使わず、完全一致可能な英数字語と日本語の連続語だけを対象にする。
 * 設計 md の按需ロードと、機能ブロック単位の類似コード検索で共通利用する。
 */
public final class KeywordExtractor {

    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}");
    private static final Pattern CJK_WORD =
            Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]{2,}");

    private static final Set<String> STOPWORDS = new HashSet<String>(Arrays.asList(
            "java", "class", "package", "import", "public", "private", "protected",
            "static", "final", "return", "void", "string", "integer", "boolean",
            "true", "false", "this", "new", "api", "flow", "code", "block",
            "機能", "処理", "理由", "目的", "引数", "戻り値", "例外", "概要", "項目", "内容"));

    private KeywordExtractor() {
    }

    /** 出現順を保ったまま最大 limit 件返す（同じ語は 1 回だけ）。 */
    public static List<String> extract(String text, int limit) {
        LinkedHashSet<String> words = new LinkedHashSet<String>();
        if (text == null || limit <= 0) {
            return new ArrayList<String>(words);
        }
        Matcher ascii = ASCII_WORD.matcher(text);
        while (ascii.find()) {
            String word = ascii.group().toLowerCase(Locale.ROOT);
            if (!STOPWORDS.contains(word)) {
                words.add(word);
            }
            if (words.size() >= limit) {
                return new ArrayList<String>(words);
            }
        }
        Matcher cjk = CJK_WORD.matcher(text);
        while (cjk.find()) {
            String word = cjk.group();
            if (!STOPWORDS.contains(word)) {
                words.add(word);
            }
            if (words.size() >= limit) {
                break;
            }
        }
        return new ArrayList<String>(words);
    }

    /** 文字列にキーワードが含まれるか（大小文字を無視）。 */
    public static boolean contains(String text, String keyword) {
        if (text == null || keyword == null || text.trim().isEmpty() || keyword.trim().isEmpty()) {
            return false;
        }
        return text.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT));
    }
}
