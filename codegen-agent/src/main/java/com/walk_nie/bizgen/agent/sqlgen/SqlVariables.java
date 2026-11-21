package com.walk_nie.bizgen.agent.sqlgen;

import com.walk_nie.bizgen.agent.mdgen.CaseVars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL バインド変数の確定的サポート（3 形式）。
 *
 * <p>日系SIで広く使われる「変数マーカー＋型に応じたリテラル」形式。
 * マーカーは SQL コメントなので SQL はそのまま実行でき、ツール側は
 * 変数名を読み取ってバインドパラメータへ差し替えられる。
 *
 * <p>許可する 3 形式（値の型は表構造の列型から Java が決める）：
 * <ol>
 *   <li>文字型 … &#47;*name*&#47;'xx'          （一重引用符で包む）</li>
 *   <li>日付型 … &#47;*name*&#47;CURRENT_DATE  （引用符なしの日付関数／キーワード）</li>
 *   <li>数値型 … &#47;*name*&#47;9             （引用符なしの数値）</li>
 * </ol>
 *
 * <p>2 つの入口：
 * <ol>
 *   <li>{@link #normalizeRequest(String, TableSchema)} … md の「{{key}}」を
 *       列型に応じたバインド変数へ書き換える（変数解決<b>前</b>に呼ぶこと）。</li>
 *   <li>{@link #gate(List, String)} … 正しい 3 形式だけを注入ゲートから通す。</li>
 * </ol>
 *
 * <p>変数名は Java 識別子に限定。違反はすべて例外（落盤前に止める）。
 * バインド変数名は md の参照名を<b>小驼峰（camelCase）へ自動正規化</b>する。
 * つまり {@code {{application_id}}} と書いても &#47;*applicationId*&#47; になる
 * （値の解決には元の参照名を使うので値は変わらない）。
 */
public final class SqlVariables {

    /**
     * 3 形式をまとめて認識する。
     * group(1)=変数名 / group(2)=文字値 / group(3)=日付トークン / group(4)=数値。
     */
    private static final Pattern BIND = Pattern.compile(
            "/\\*([A-Za-z_][A-Za-z0-9_]*)\\*/\\s*"
                    + "(?:'([^']*)'"
                    + "|(DATE\\s+'[^']*'|[A-Za-z_][A-Za-z0-9_]*)"
                    + "|(-?\\d+(?:\\.\\d+)?))");

    /** md 内の変数参照（解決前の {@code {{key}}} / {@code {{key:default}}}）。 */
    private static final Pattern REF =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?::([^{}]*))?\\s*}}");

    /** 「- col = ...」の先頭列名を拾う（列型を引くため）。 */
    private static final Pattern LEADING_COL = Pattern.compile(
            "^\\s*-\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|<|>|!|LIKE|IS|IN|BETWEEN)");

    private static final List<String> FORBIDDEN_IN_VALUE =
            List.of(";", "--", "/*", "*/", "DROP ", "TRUNCATE ", " UNION ");

    private SqlVariables() {}

    /**
     * 旧互換：表構造を渡さない場合は全変数を文字型として扱う。
     * 列型に応じた 3 形式を使う場合は {@link #normalizeRequest(String, TableSchema)} を呼ぶ。
     */
    public static String normalizeRequest(String text) {
        return normalizeRequest(text, null);
    }

    /**
     * 条件／設定値セクションの {@code {{key}}」を、<b>その行の列型</b>に応じた
     * バインド変数へ正規化する。既に {@code /*} を含む行はそのまま通す（二重変換しない）。
     *
     * <p>呼び出し順：<b>正規化 → TemplateResolver.resolve → Parser → Renderer</b>。
     * 解決後に呼ぶと変数名が失われるので必ずこの順を守ること。
     */
    public static String normalizeRequest(String text, TableSchema schema) {
        if (text == null || text.isEmpty()) return text;
        String section = null;
        StringBuilder out = new StringBuilder();
        for (String line : text.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("## ")) section = trimmed.substring(3).strip();
            boolean valueSection = "条件".equals(section) || "設定値".equals(section);
            if (valueSection && trimmed.startsWith("-") && !trimmed.contains("/*")) {
                out.append(normalizeLine(line, schema)).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String normalizeLine(String line, TableSchema schema) {
        String column = leadingColumn(line);
        SqlVariableType type = schema == null ? SqlVariableType.CHAR : schema.typeOf(column);
        Matcher m = REF.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String ref = m.group(0);
            // 変数名（マーカー）は常に小驼峰。値の参照名は元のまま残し解決に使う。
            String bindName = bindName(name);
            m.appendReplacement(out,
                    Matcher.quoteReplacement(type.bind(bindName, ref)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** md の参照名を SQL バインド変数名（小驼峰）へ確定的に正規化する。 */
    public static String bindName(String referenceName) {
        return CaseVars.camel(referenceName);
    }

    private static String leadingColumn(String line) {
        Matcher m = LEADING_COL.matcher(line);
        return m.find() ? m.group(1) : "";
    }

    /** 3 形式のいずれか完全に一致するか。 */
    public static boolean isBind(String part) {
        return part != null && BIND.matcher(part.strip()).matches();
    }

    /** バインド変数名（合法なものだけ）。合法でなければ例外。 */
    public static String nameOf(String part) {
        Matcher m = BIND.matcher(part == null ? "" : part.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("不正なバインド変数: " + part
                    + "（/*変数名*/'値' または /*変数名*/TOKEN または /*変数名*/9 の形式で書くこと）");
        }
        return m.group(1);
    }

    /** どの型のバインド変数か（CHAR / DATE / NUMBER）。 */
    public static SqlVariableType typeOf(String part) {
        Matcher m = BIND.matcher(part == null ? "" : part.strip());
        if (!m.matches()) throw new IllegalArgumentException("不正なバインド変数: " + part);
        if (m.group(2) != null) return SqlVariableType.CHAR;
        if (m.group(3) != null) return SqlVariableType.DATE;
        return SqlVariableType.NUMBER;
    }

    /** 正しい形のバインド変数だけを除去した文字列（注入チェック用）。 */
    public static String stripBinds(String part) {
        if (part == null) return "";
        return BIND.matcher(part).replaceAll("__BIND__");
    }

    /** 注入ゲート：正しい 3 形式だけを通し、それ以外のコメント断片は拒否する。 */
    public static void gate(List<String> parts, String label) {
        if (parts == null) return;
        for (String part : parts) {
            String stripped = stripBinds(part);
            String up = stripped.toUpperCase(Locale.ROOT);
            for (String bad : FORBIDDEN_IN_VALUE) {
                if (up.contains(bad)) {
                    throw new IllegalArgumentException("SQL ゲート拒否: " + label + "「"
                            + part + "」に禁止断片「" + bad.strip() + "」が含まれる");
                }
            }
            // 正規形に一致しないコメント断片が残っていれば不正。
            if (stripped.contains("/*") || stripped.contains("*/")) {
                throw new IllegalArgumentException("SQL ゲート拒否: " + label + "「" + part
                        + "」に不正なコメント断片がある（許可は 3 形式のバインド変数のみ）");
            }
            validateBindValues(part, label);
        }
    }

    /** ORDER BY のようにバインド変数を許さない箇所のチェック。 */
    public static void rejectBinds(List<String> parts, String label) {
        if (parts == null) return;
        for (String part : parts) {
            if (part != null && part.contains("/*")) {
                throw new IllegalArgumentException("SQL ゲート拒否: " + label + "「" + part
                        + "」にはバインド変数を使えない");
            }
        }
    }

    private static void validateBindValues(String part, String label) {
        Matcher m = BIND.matcher(part == null ? "" : part);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2)
                    : m.group(3) != null ? m.group(3) : m.group(4);
            if (value == null) continue;
            String up = value.toUpperCase(Locale.ROOT);
            for (String bad : FORBIDDEN_IN_VALUE) {
                if (up.contains(bad)) {
                    throw new IllegalArgumentException("SQL ゲート拒否: " + label + "のバインド変数「"
                            + m.group(1) + "」の値に禁止断片「" + bad.strip() + "」が含まれる");
                }
            }
        }
    }

    /** 変換後の変数名を重複なし・出現順で返す（ログ／レビュー用）。 */
    public static List<String> bindNames(List<String> parts) {
        List<String> out = new ArrayList<>();
        if (parts == null) return out;
        for (String part : parts) {
            Matcher m = BIND.matcher(part == null ? "" : part);
            while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        }
        return out;
    }

    /** テキスト中のバインド変数（3 形式）を出現順に全て返す。 */
    public static List<String> bindsIn(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = BIND.matcher(text);
        while (m.find()) out.add(m.group(0));
        return out;
    }

    /** 変数名 → 型の対応を返す（レビュー用）。 */
    public static Map<String, SqlVariableType> bindTypes(List<String> parts) {
        Map<String, SqlVariableType> out = new LinkedHashMap<>();
        if (parts == null) return out;
        for (String part : parts) {
            Matcher m = BIND.matcher(part == null ? "" : part);
            while (m.find()) {
                SqlVariableType t = m.group(2) != null ? SqlVariableType.CHAR
                        : m.group(3) != null ? SqlVariableType.DATE : SqlVariableType.NUMBER;
                out.putIfAbsent(m.group(1), t);
            }
        }
        return out;
    }

    /** 旧互換：文字型としてバインド変数を組み立てる。 */
    public static String render(String name, String value, Map<String, String> vars) {
        return render(name, value, SqlVariableType.CHAR, vars);
    }

    /** 変数表と列型から 3 形式のいずれかを組み立てる（型に合わない値は拒否）。 */
    public static String render(String name, String value, SqlVariableType type,
                                Map<String, String> vars) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("不正な変数名: " + name);
        }
        String actual = vars != null && vars.containsKey(name) ? vars.get(name) : value;
        if (actual == null || actual.contains("'")) {
            throw new IllegalArgumentException("変数値に引用符は使えない: " + name);
        }
        SqlVariableType t = type == null ? SqlVariableType.CHAR : type;
        if (t == SqlVariableType.CHAR) return "/*" + name + "*/'" + actual + "'";
        if (t == SqlVariableType.NUMBER && !actual.matches("-?\\d+(?:\\.\\d+)?")) {
            throw new IllegalArgumentException("数値型変数「" + name + "」の値が数値でない: " + actual);
        }
        if (t == SqlVariableType.DATE
                && !actual.matches("[A-Za-z_][A-Za-z0-9_]*|DATE\\s+'[^']*'")) {
            throw new IllegalArgumentException("日付型変数「" + name + "」の値が日付トークンでない: " + actual);
        }
        return "/*" + name + "*/" + actual;
    }
}
