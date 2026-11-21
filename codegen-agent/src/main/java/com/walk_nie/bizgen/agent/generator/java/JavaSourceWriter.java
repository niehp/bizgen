package com.walk_nie.bizgen.agent.generator.java;

import java.util.ArrayList;
import java.util.List;

/**
 * Java ソースに対する決定論的な編集を行う。
 * モデルが返したコードの波括弧・コメントを正しく認識し、メソッド単位で追加／置換する。
 * ここでは一切の推論を行わず、見つからない場合は例外として失敗させる。
 */
public final class JavaSourceWriter {

    private JavaSourceWriter() {
    }

    /** メソッド 1 つ分（Javadoc を含む）のソース上の範囲。 */
    public static final class MethodBlock {
        private final String name;
        private final String text;
        private final int start;
        private final int end;

        MethodBlock(String name, String text, int start, int end) {
            this.name = name;
            this.text = text;
            this.start = start;
            this.end = end;
        }

        public String name() {
            return name;
        }

        public String text() {
            return text;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }
    }

    /** クラス直下のメソッド宣言を抽出する。 */
    public static List<MethodBlock> extractMethods(String source) {
        List<MethodBlock> result = new ArrayList<MethodBlock>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        int depth = 0;
        int statementStart = 0;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i);
                continue;
            }
            if (c == '"' || c == '\'') {
                i = skipString(source, i, c);
                continue;
            }
            if (c == '{') {
                if (depth == 1) {
                    String segment = source.substring(statementStart, i);
                    if (isMethodSignature(segment)) {
                        int close = findMatchingBrace(source, i);
                        if (close > i) {
                            int start = includeLeadingJavadoc(source, statementStart);
                            result.add(new MethodBlock(extractMethodName(segment),
                                    source.substring(start, close + 1), start, close + 1));
                            statementStart = close + 1;
                            i = close + 1;
                            continue;
                        }
                    }
                }
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                depth--;
                i++;
                if (depth <= 1) {
                    statementStart = i;
                }
                continue;
            }
            if (c == ';' && depth == 1) {
                statementStart = i + 1;
            }
            i++;
        }
        return result;
    }

    /** 既存クラスの末尾（最後の }）の直前にメソッドを追加する。 */
    public static String insertMethods(String existingSource, String newMethods) {
        int lastBrace = existingSource.lastIndexOf('}');
        if (lastBrace < 0) {
            throw new IllegalArgumentException("既存ソースにクラスの閉じ括弧が見つかりません");
        }
        String before = rstrip(existingSource.substring(0, lastBrace));
        String after = existingSource.substring(lastBrace);
        return before + "\n\n" + newMethods.trim() + "\n" + after;
    }

    /** 既存クラスの指定メソッドを置換する。名前が見つからない場合は失敗させる。 */
    public static String replaceMethod(String existingSource, String methodName, String newMethod) {
        List<MethodBlock> methods = extractMethods(existingSource);
        MethodBlock target = null;
        for (MethodBlock method : methods) {
            if (method.name().equals(methodName)) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("置換対象のメソッドが見つかりません: " + methodName
                    + "（検出: " + methodNames(methods) + "）");
        }
        return existingSource.substring(0, target.start())
                + newMethod.trim()
                + existingSource.substring(target.end());
    }

    /** パッケージコメント（package-info.java）を生成する。 */
    public static String buildPackageInfo(String packageName, String comment) {
        String safeComment = comment == null || comment.trim().isEmpty() ? "生成されたパッケージ。" : comment.trim();
        return "/**\n"
                + " * " + safeComment + "\n"
                + " * 変更内容は手動レビュー後に取り込む。\n"
                + " */\n"
                + "package " + packageName + ";\n";
    }

    /** 生成結果に対する決定論的な検証。警告は人間のレビューへ渡す。 */
    public static List<String> validate(String code) {
        List<String> warnings = new ArrayList<String>();
        String source = code == null ? "" : code;
        if (source.indexOf("/**") < 0) {
            warnings.add("Javadoc コメントが見つかりません");
        }
        if (!source.contains("class ") && !source.contains("interface ") && !source.contains("enum ")) {
            warnings.add("クラス／インタフェース／enum 宣言が見つかりません");
        }
        int balance = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i) - 1;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i) - 1;
            } else if (c == '"' || c == '\'') {
                i = skipString(source, i, c) - 1;
            } else if (c == '{') {
                balance++;
            } else if (c == '}') {
                balance--;
            }
        }
        if (balance != 0) {
            warnings.add("波括弧の対応が不正です（差分 " + balance + "）");
        }
        return warnings;
    }

    private static boolean isMethodSignature(String segment) {
        String value = segment.trim();
        if (value.isEmpty() || value.indexOf('(') < 0 || value.indexOf(')') < 0) {
            return false;
        }
        String[] forbidden = {"if", "for", "while", "switch", "catch", "try", "else", "return", "new "};
        for (String word : forbidden) {
            if (value.startsWith(word)) {
                return false;
            }
        }
        return true;
    }

    private static String extractMethodName(String segment) {
        int paren = segment.indexOf('(');
        if (paren < 0) {
            return "";
        }
        int start = paren - 1;
        while (start >= 0 && (Character.isLetterOrDigit(segment.charAt(start))
                || segment.charAt(start) == '_' || segment.charAt(start) == '$')) {
            start--;
        }
        return segment.substring(start + 1, paren);
    }

    private static int includeLeadingJavadoc(String source, int position) {
        String before = rstrip(source.substring(0, position));
        if (!before.endsWith("*/")) {
            return position;
        }
        int open = before.lastIndexOf("/**");
        return open >= 0 ? open : position;
    }

    private static int findMatchingBrace(String source, int openIndex) {
        int depth = 0;
        int i = openIndex;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i);
                continue;
            }
            if (c == '"' || c == '\'') {
                i = skipString(source, i, c);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static int skipLineComment(String source, int index) {
        int next = source.indexOf('\n', index + 2);
        return next < 0 ? source.length() : next + 1;
    }

    private static int skipBlockComment(String source, int index) {
        int close = source.indexOf("*/", index + 2);
        return close < 0 ? source.length() : close + 2;
    }

    private static int skipString(String source, int index, char quote) {
        int i = index + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return source.length();
    }

    private static String methodNames(List<MethodBlock> methods) {
        List<String> names = new ArrayList<String>();
        for (MethodBlock method : methods) {
            names.add(method.name());
        }
        return names.toString();
    }

    private static String rstrip(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
