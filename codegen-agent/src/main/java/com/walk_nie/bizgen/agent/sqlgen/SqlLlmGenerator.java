package com.walk_nie.bizgen.agent.sqlgen;

import com.walk_nie.bizgen.agent.Json;
import com.walk_nie.bizgen.agent.RecordSchema;
import com.walk_nie.bizgen.agent.VllmClient;

import java.util.List;
import java.util.Map;

/**
 * 「SQL 雛形（prompt テンプレート）＋ md の表構造」から大模型に SQL 文を生成させるリーフ。
 *
 * <p>責務の境界：<b>種別の選択・prompt の選択・表構造・変数値・落盤判定は Java 側</b>。
 * モデルが決めるのは SQL テキストと判断メモだけ（{@code guided_json} で構造も強制）。
 * 生成結果は必ず {@link SqlTextGate} の確定的ゲートへ通し、人間のレビュー用に
 * 下書きを残してから正式な {@code .sql} を保存する。
 *
 * <p>mock（設定ファイル {@code llm.mode=mock}）ではローカルモデルを呼ばず、
 * 表構造ライブラリの実在する列だけを使った SQL を確定的に組み立てて配線を検証する。
 */
public final class SqlLlmGenerator {

    private static final String SYSTEM = """
            あなたは日系SIのSQL作成アシスタントです。
            与えられた prompt テンプレート（SQL 雛形を含む）と表構造 md だけを根拠に、
            指示された種別の SQL 文を 1 つだけ生成してください。
            必ず指定された JSON スキーマに従い、sql と note の 2 項目だけを返してください。

            【厳守】
            - テーブル名・カラム名は表構造にある英語の物理名だけを使う。日本語名は使わない。
            - 存在しない列・テーブルを捏造しない。SQL は 1 文、末尾はセミコロン 1 個。
            - 変数は /*変数名*/ の 3 形式（文字型は 'xx'、日付型は CURRENT_DATE、数値型は 9）を守る。
            - 種別はユーザー prompt で指定されたものだけ。別種別の文を書かない。
            - note には日本語で「何をどう判断したか」「レビューで確認すべき点」を簡潔に書く。
            """;

    private final VllmClient llm;
    private final boolean live;

    public SqlLlmGenerator(VllmClient llm, boolean live) {
        this.llm = llm;
        this.live = live;
    }

    /**
     * 設定に従って生成する。{@code promptTemplate} は種別に対応する md テンプレート
     * （{@link SqlPromptTemplate#defaultKindPath(SqlKind)}）を読み込んだ本文。
     */
    public SqlTextDraft generate(SqlKind kind, String description, Map<String, String> vars,
                                 List<String> contexts, TableSchemaLibrary library,
                                 NameMapping mapping, String promptTemplate) throws Exception {
        if (promptTemplate == null || promptTemplate.isBlank()) {
            throw new IllegalArgumentException("prompt テンプレートが空（種別=" + kind + "）");
        }
        if (!live) return generateMock(kind, vars, library);

        String schemaJson = RecordSchema.toJsonSchema(SqlTextDraft.class);
        String prompt = SqlPromptTemplate.fill(promptTemplate, description,
                varsBlock(vars), contextsBlock(contexts), tablesBlock(library, mapping));
        String json = llm.guidedJson(SYSTEM, prompt, schemaJson);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Json.parse(json);
        SqlTextDraft draft = RecordSchema.fromMap(map, SqlTextDraft.class);
        if (draft == null || draft.sql().isBlank()) {
            throw new IllegalArgumentException("LLM が空の SQL を返した");
        }
        return draft;
    }

    /** prompt へ差し込む blocks（public static で CLI のログ・テストからも参照可）。 */
    public static String contextsBlock(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("# 上位仕様（共通 md。日本語名は英語物理名へ変換済み）\n");
        for (String c : contexts) sb.append('\n').append(c.strip()).append('\n');
        return sb.append('\n').toString();
    }

    public static String varsBlock(Map<String, String> vars) {
        Map<String, String> v = vars == null ? Map.of() : vars;
        StringBuilder sb = new StringBuilder("# 解決済み変数（値を推測して書き換えないこと）\n");
        if (v.isEmpty()) sb.append("（変数定義なし）\n");
        v.forEach((k, val) -> sb.append("- ").append(k).append(" = ").append(val).append('\n'));
        sb.append("\n# バインド変数の 3 形式（列の SQL 型で決まる）\n")
          .append("- 文字型: /*変数*/'xx'   日付型: /*変数*/CURRENT_DATE   数値型: /*変数*/9\n");
        return sb.append('\n').toString();
    }

    /** 表構造（英語物理名）＋日本語名→英語名の対応表をモデルへ渡す。 */
    public static String tablesBlock(TableSchemaLibrary library, NameMapping mapping) {
        StringBuilder sb = new StringBuilder(
                "# 表構造（SQL に書いてよいのは「カラム名」列の英語物理名だけ）\n"
                        + "「日本語名」列は仕様書側の名前です。SQL には書かないでください。\n\n");
        if (library != null && library.size() > 0) sb.append(library.promptContext());
        sb.append("\n# 日本語名 → 英語物理名の対応（SQL には右側を使う）\n");
        sb.append(mapping == null ? "（対応表なし）\n" : mapping.mappingTable());
        return sb.append('\n').toString();
    }

    /**
     * オフライン骨格：表構造ライブラリの<b>実在する列だけ</b>を使い、
     * SQL 雛形と同じ形の SQL を確定的に組み立てる（列を推測しない）。
     * SELECT で 2 テーブルが揃う場合は多表結合（JOIN）の雛形を出す。
     */
    public SqlTextDraft generateMock(SqlKind kind, Map<String, String> vars,
                                     TableSchemaLibrary library) {
        Map<String, String> v = vars == null ? Map.of() : vars;
        TableSchemaLibrary lib = library == null ? TableSchemaLibrary.empty() : library;
        TableSchemaLibrary.Entry d = lib.find("receipt_detail");
        TableSchemaLibrary.Entry h = lib.find("receipt_header");
        String appBind = SqlVariables.render("applicationId", "APP001", SqlVariableType.CHAR, v);
        String numBind = SqlVariables.render("minAmount", "9", SqlVariableType.NUMBER, v);
        String dateBind = SqlVariables.render("paymentDate", "CURRENT_DATE", SqlVariableType.DATE, v);
        String updatedBind = SqlVariables.render("updatedAt", "CURRENT_DATE", SqlVariableType.DATE, v);

        String sql;
        String note;
        if (kind == SqlKind.SELECT && d != null && h != null
                && d.schema().hasColumn("application_id")
                && h.schema().hasColumn("application_id")) {
            // 多表結合（JOIN）の SQL 雛形
            String dFirst = pick(d.schema(), "id", "amount");
            String dSecond = pick(d.schema(), "amount", "id");
            String hCol = pick(h.schema(), "status", "id");
            String receiptDateBind = SqlVariables.render("receiptDate", "CURRENT_DATE",
                    SqlVariableType.DATE, v);
            sql = """
                    SELECT d.%s, d.%s, h.%s
                    FROM %s AS d
                    INNER JOIN %s AS h ON d.application_id = h.application_id
                    WHERE d.application_id = %s
                      AND h.receipt_date = %s
                      AND d.amount >= %s
                    ORDER BY d.%s DESC;
                    """.formatted(dFirst, dSecond, hCol, d.tableName(), h.tableName(),
                    appBind, receiptDateBind, numBind, dFirst);
            note = "オフライン骨格（多表結合）。入金明細と入金ヘッダを application_id で INNER JOIN し、"
                    + "文字型／日付型／数値型の 3 形式のバインド変数を含めた。列名は表構造ライブラリから取得。";
        } else if (d != null && d.schema().hasColumn("application_id")) {
            // 意味のある列で SQL 雛形を再現（3 形式すべてを出す）
            String table = d.tableName();
            sql = switch (kind) {
                case SELECT -> """
                        SELECT id, amount FROM %s
                        WHERE application_id = %s
                          AND payment_date = %s
                          AND amount >= %s
                        ORDER BY id DESC;
                        """.formatted(table, appBind, dateBind, numBind);
                case UPDATE -> """
                        UPDATE %s
                        SET status = 'PAID', updated_at = %s
                        WHERE application_id = %s
                          AND amount >= %s;
                        """.formatted(table, updatedBind, appBind, numBind);
                case DELETE -> """
                        DELETE FROM %s
                        WHERE application_id = %s
                          AND payment_date = %s
                          AND amount >= %s;
                        """.formatted(table, appBind, dateBind, numBind);
            };
            note = "オフライン骨格。表「" + table + "」の実在列だけを使い、"
                    + "列型から文字型／日付型／数値型のバインド変数を組み立てた（列名は推測していない）。";
        } else {
            // 表が無い／想定外のときの汎用単表（列型から形式を決める）
            TableSchemaLibrary.Entry primary = lib.all().isEmpty() ? null : lib.all().get(0);
            String table = primary == null ? "sample_table" : primary.tableName();
            List<String> cols = primary == null ? List.of("id") : primary.schema().columnNames();
            String c0 = cols.isEmpty() ? "id" : cols.get(0);
            String c1 = cols.size() > 1 ? cols.get(1) : c0;
            SqlVariableType t0 = primary == null ? SqlVariableType.NUMBER : primary.schema().typeOf(c0);
            SqlVariableType t1 = primary == null ? SqlVariableType.CHAR : primary.schema().typeOf(c1);
            String bind0 = SqlVariables.render("condVar", defaultValue(t0), t0, v);
            String setBind = SqlVariables.render("setVar", defaultValue(t1), t1, v);
            sql = switch (kind) {
                case SELECT -> "SELECT %s, %s FROM %s WHERE %s = %s ORDER BY %s DESC;"
                        .formatted(c0, c1, table, c0, bind0, c0);
                case UPDATE -> "UPDATE %s SET %s = %s WHERE %s = %s;"
                        .formatted(table, c1, setBind, c0, bind0);
                case DELETE -> "DELETE FROM %s WHERE %s = %s;"
                        .formatted(table, c0, bind0);
            };
            note = "オフライン骨格（汎用単表）。表「" + table + "」の実在列 " + c0 + " / " + c1
                    + " を使い、列型からバインド変数の形式（" + t0.japanese() + "）を決めた。";
        }
        return new SqlTextDraft(sql.strip(), note);
    }

    private static String defaultValue(SqlVariableType type) {
        return switch (type) {
            case CHAR -> "APP001";
            case DATE -> "CURRENT_DATE";
            case NUMBER -> "9";
        };
    }

    private static String pick(TableSchema schema, String... preferred) {
        for (String p : preferred) if (schema.hasColumn(p)) return p;
        return schema.columnNames().isEmpty() ? "id" : schema.columnNames().get(0);
    }
}
