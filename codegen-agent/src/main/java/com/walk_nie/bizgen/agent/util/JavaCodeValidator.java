package com.walk_nie.bizgen.agent.util;

import com.walk_nie.bizgen.agent.term.TermDictionary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成 Java の静的検証（決定論的）。
 * 非法文字・术语違反・最低限の構造をチェックし、違反があれば修正依頼の根拠として返す。
 * コメントと文字列リテラルはマスクしてから检查するため、日本語 Javadoc 自体は違反にならない。
 */
public final class JavaCodeValidator {

    private static final String FULLWIDTH_PUNCT = "，。；：？！（）［］｛｝、“”‘’、・";
    private static final Pattern PACKAGE = Pattern.compile("package\\s+[\\w.]+\\s*;");
    private static final Pattern TYPE = Pattern.compile("(?:public\\s+)?(?:final\\s+)?(?:class|interface|enum)\\s+\\w+");
    private static final Pattern CJK_IDENTIFIER = Pattern.compile("[\\u3040-\\u30ff\\u4e00-\\u9fff]+");

    private JavaCodeValidator() {
    }

    public static List<String> validate(String code, List<TermDictionary.TermEntry> terms) {
        return validate(code, terms, true);
    }

    /**
     * requireStructure=false のときは package / class 宣言の有無を検査しない。
     * java-gen の ADD_METHOD / MODIFY_METHOD はメソッド片を検証するため、構造検査を外す。
     */
    public static List<String> validate(String code, List<TermDictionary.TermEntry> terms,
                                        boolean requireStructure) {
        List<String> problems = new ArrayList<String>();
        if (StrUtil.isBlank(code)) {
            problems.add("Java コードが空です");
            return problems;
        }

        // 日本語 Javadoc / 文字列リテラルは合法として扱うため、コード部分だけを検査する。
        String codeOnly = maskCommentsAndStrings(code);
        for (int i = 0; i < codeOnly.length(); i++) {
            char c = codeOnly.charAt(i);
            if (c == '\u3000') {
                addOnce(problems, "全角スペース (U+3000) が含まれています");
            } else if (c == '\uFEFF') {
                addOnce(problems, "BOM (U+FEFF) が含まれています");
            } else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                addOnce(problems, "制御文字 (U+" + Integer.toHexString(c) + ") が含まれています");
            } else if (FULLWIDTH_PUNCT.indexOf(c) >= 0) {
                addOnce(problems, "全角記号 '" + c + "' が含まれています");
            }
        }

        if (requireStructure) {
            if (!PACKAGE.matcher(code).find()) {
                problems.add("package 宣言がありません");
            }
            if (!TYPE.matcher(code).find()) {
                problems.add("class / interface / enum 宣言がありません");
            }
        }

        Matcher cjk = CJK_IDENTIFIER.matcher(codeOnly);
        while (cjk.find()) {
            String word = cjk.group();
            String suggestion = "";
            if (terms != null) {
                for (TermDictionary.TermEntry term : terms) {
                    if (word.contains(term.japanese()) || term.japanese().contains(word)) {
                        suggestion = "（术语库では " + term.english() + "）";
                        break;
                    }
                }
            }
            problems.add("識別子に日本語 '" + word + "' が使用されています" + suggestion);
        }
        return problems;
    }

    /** コメントと文字列リテラルを空白に置き換え、コード部分だけを残す。 */
    public static String maskCommentsAndStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int state = 0; // 0: code, 1: line comment, 2: block comment, 3: string, 4: char
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : 0;
            switch (state) {
                case 0:
                    if (c == '/' && next == '/') {
                        out.append("  ");
                        i++;
                        state = 1;
                    } else if (c == '/' && next == '*') {
                        out.append("  ");
                        i++;
                        state = 2;
                    } else if (c == '"') {
                        out.append(' ');
                        state = 3;
                    } else if (c == '\'') {
                        out.append(' ');
                        state = 4;
                    } else {
                        out.append(c);
                    }
                    break;
                case 1:
                    if (c == '\n') {
                        out.append('\n');
                        state = 0;
                    } else {
                        out.append(' ');
                    }
                    break;
                case 2:
                    if (c == '*' && next == '/') {
                        out.append("  ");
                        i++;
                        state = 0;
                    } else {
                        out.append(c == '\n' ? '\n' : ' ');
                    }
                    break;
                case 3:
                    if (c == '\\') {
                        out.append("  ");
                        if (i + 1 < code.length()) {
                            i++;
                        }
                    } else if (c == '"') {
                        out.append(' ');
                        state = 0;
                    } else {
                        out.append(c == '\n' ? '\n' : ' ');
                    }
                    break;
                case 4:
                    if (c == '\\') {
                        out.append("  ");
                        if (i + 1 < code.length()) {
                            i++;
                        }
                    } else if (c == '\'') {
                        out.append(' ');
                        state = 0;
                    } else {
                        out.append(c == '\n' ? '\n' : ' ');
                    }
                    break;
                default:
                    break;
            }
        }
        return out.toString();
    }

    private static void addOnce(List<String> problems, String message) {
        if (!problems.contains(message)) {
            problems.add(message);
        }
    }
}
