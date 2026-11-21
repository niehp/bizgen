package com.walk_nie.bizgen.agent.sqlgen;

import java.util.Locale;
import java.util.Set;

/**
 * SQL バインド変数の「値の型」を表す（列の SQL 型から 100% 確定的に決める）。
 *
 * <p>日系SIのSQLテンプレート慣習に合わせ、変数マーカー（&#47;*変数名*&#47;）の
 * 後ろに置くリテラルの形を型ごとに変える：
 * <ul>
 *   <li>CHAR   … &#47;*変数*&#47;'xx'        （一重引用符で包む文字列）</li>
 *   <li>DATE   … &#47;*変数*&#47;CURRENT_DATE （引用符なしの日付関数／キーワード）</li>
 *   <li>NUMBER … &#47;*変数*&#47;9            （引用符なしの数値）</li>
 * </ul>
 *
 * <p>なぜ型で分けるか：SQL はそのまま実行できる必要がある。日付や数値を
 * 文字列として引用符で包むと暗黙変換やインデックス効率の問題が出る。
 * 「どの形にするか」をモデルに判断させず、表構造（事実）から Java が決める。
 *
 * <p>未知の型は保守的に CHAR とする（引用符で包む側に倒す）。
 */
public enum SqlVariableType {

    CHAR, DATE, NUMBER;

    private static final Set<String> NUMBER_TYPES = Set.of(
            "tinyint", "smallint", "int", "integer", "bigint", "serial", "bigserial",
            "decimal", "numeric", "number", "float", "double", "real", "money",
            "boolean", "bool", "bit");

    private static final Set<String> DATE_TYPES = Set.of(
            "date", "time", "timetz", "timestamp", "timestamptz", "datetime",
            "datetime2", "smalldatetime", "year", "interval");

    /** {@code varchar(32)} / {@code decimal(10,2)} のような引数部を落として判定する。 */
    public static SqlVariableType of(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) return CHAR;
        String base = sqlType.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\(.*$", "").strip();
        if (NUMBER_TYPES.contains(base)) return NUMBER;
        if (DATE_TYPES.contains(base)) return DATE;
        return CHAR;
    }

    /**
     * 変数参照（{@code {{key}}} または解決済み値）を型に応じたリテラルへ包む。
     *
     * @param name 変数名（Java 識別子）
     * @param ref  {{key}} のような参照、または解決済みの値
     */
    public String bind(String name, String ref) {
        return switch (this) {
            case CHAR -> "/*" + name + "*/'" + ref + "'";
            case DATE, NUMBER -> "/*" + name + "*/" + ref;
        };
    }

    /** 型の説明（prompt／ログ用の日本語ラベル）。 */
    public String japanese() {
        return switch (this) {
            case CHAR -> "文字型（/*変数*/'xx'）";
            case DATE -> "日付型（/*変数*/CURRENT_DATE）";
            case NUMBER -> "数値型（/*変数*/9）";
        };
    }
}
