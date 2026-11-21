package com.walk_nie.bizgen.agent.sqlgen;

import com.walk_nie.bizgen.agent.mdgen.TemplateResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SQL 生成リンクのセルフチェック。
 *
 * <p>正向：SELECT / UPDATE / DELETE が仕様どおりにレンダされることを確認。
 * 負向：種別ごとの必須欠落、未提供列、表名不一致、注入断片がすべて確定的に拒否されることを証明する
 * （「飾りゲート」でないことを示す）。
 */
public final class SqlgenDemo {

    public static void main(String[] args) throws Exception {
        String tableMd = """
                # 表構造
                ## テーブル
                - テーブル名: receipt_detail
                ## カラム
                | カラム名 | 型 | NOT NULL | PK |
                | --- | --- | --- | --- |
                | id | bigint | YES | YES |
                | application_id | varchar(32) | YES | NO |
                | amount | bigint | NO | NO |
                | payment_date | date | NO | NO |
                | status | varchar(16) | YES | NO |
                """;
        TableSchema schema = TableSchemaParser.parse(tableMd);
        check(schema.columns().size() == 5, "表構造 5 列を解析");
        check(schema.hasColumn("application_id") && !schema.hasColumn("no_such"), "列存在チェックが働く");

        SqlRenderer renderer = new SqlRenderer();

        // ---- 正向：SELECT ----
        SqlRequest select = SqlRequestParser.parse("""
                # SQL 仕様
                ## 対象
                - テーブル: receipt_detail
                - 種別: SELECT
                ## 選択列
                - id
                - amount
                ## 条件
                - application_id = APP001
                ## 並び順
                - payment_date DESC
                """);
        String selectSql = renderer.render(select, schema);
        check(selectSql.equals("SELECT id, amount FROM receipt_detail WHERE application_id = APP001 ORDER BY payment_date DESC;"),
                "SELECT が仕様順にレンダされる: " + selectSql);

        // ---- 正向：UPDATE ----
        SqlRequest update = SqlRequestParser.parse("""
                # SQL 仕様
                ## 対象
                - テーブル: receipt_detail
                - 種別: UPDATE
                ## 設定値
                - status = 'PAID'
                - amount = 1200
                ## 条件
                - id = 7
                """);
        String updateSql = renderer.render(update, schema);
        check(updateSql.equals("UPDATE receipt_detail SET status = 'PAID', amount = 1200 WHERE id = 7;"),
                "UPDATE が SET/WHERE を正しくレンダ: " + updateSql);

        // ---- 正向：DELETE（条件あり）----
        SqlRequest delete = SqlRequestParser.parse("""
                # SQL 仕様
                ## 対象
                - テーブル: receipt_detail
                - 種別: DELETE
                ## 条件
                - application_id = APP001
                """);
        String deleteSql = renderer.render(delete, schema);
        check(deleteSql.equals("DELETE FROM receipt_detail WHERE application_id = APP001;"),
                "DELETE が条件付きでレンダ: " + deleteSql);

        // ---- 正向：バインド変数 /*変数名*/'値' ----
        check(SqlVariables.isBind("/*applicationId*/'APP001'"), "バインド変数形式を認識");
        check(!SqlVariables.isBind("/* oops */'APP001'"), "不正なコメントはバインド変数ではない");
        check(SqlVariables.nameOf("/*applicationId*/'APP001'").equals("applicationId"), "変数名を取り出せる");

        String bindMd = """
                ## 対象
                - テーブル: receipt_detail
                - 種別: SELECT
                ## 選択列
                - id
                - amount
                ## 条件
                - application_id = {{applicationId}}
                - status = 'PAID'
                ## 並び順
                - payment_date DESC
                """;
        String normalized = SqlVariables.normalizeRequest(bindMd);
        check(normalized.contains("/*applicationId*/'{{applicationId}}'"),
                "{{key}} がバインド変数へ正規化される");
        String resolved = new TemplateResolver(Map.of("applicationId", "APP001")).resolve(normalized);
        SqlRequest bindReq = SqlRequestParser.parse(resolved);
        String bindSql = renderer.render(bindReq, schema);
        check(bindSql.equals("SELECT id, amount FROM receipt_detail"
                        + " WHERE application_id = /*applicationId*/'APP001' AND status = 'PAID'"
                        + " ORDER BY payment_date DESC;"),
                "バインド変数入り SELECT がレンダされる: " + bindSql);
        check(SqlVariables.bindNames(bindReq.conditions()).equals(List.of("applicationId")),
                "バインド変数名をログ用に取得できる");

        SqlRequest explicitBind = SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: DELETE
                ## 条件
                - application_id = /*applicationId*/'APP001'
                """);
        check(renderer.render(explicitBind, schema).equals(
                "DELETE FROM receipt_detail WHERE application_id = /*applicationId*/'APP001';"),
                "md に直接書いた /*変数*/'値' も通る");

        // ---- LLM 下書き（オフライン骨格）→ 確定パイプライン ----
        SqlDraft draft = new SqlDraftGenerator(null, false).generateMock(
                "テーブル: receipt_detail\n対象アプリの明細を削除する。",
                Map.of("applicationId", "APP001"), List.of());
        SqlDraftGenerator.validate(draft);
        check(draft.requestMd().contains("DELETE") && draft.tableMd().contains("receipt_detail"),
                "LLM 下書き（mock）に表構造と種別が含まれる");
        String draftSql = renderer.render(
                SqlRequestParser.parse(SqlVariables.normalizeRequest(
                        new TemplateResolver(Map.of("applicationId", "APP001")).resolve(draft.requestMd()))),
                TableSchemaParser.parse(draft.tableMd()));
        check(draftSql.contains("DELETE FROM receipt_detail")
                        && draftSql.contains("/*applicationId*/'APP001'"),
                "下書き → ゲート → SQL まで通る: " + draftSql);

        // ---- 表構造ライブラリ（1 ファイル 1 テーブル）----
        Path tableDir = Files.createTempDirectory("sql-table-lib");
        Files.writeString(tableDir.resolve("_template.md"), "## テーブル\n- テーブル名: <table_name>\n");
        Files.writeString(tableDir.resolve("receipt_detail.md"), tableMd);
        Files.writeString(tableDir.resolve("receipt_header.md"), """
                ## テーブル
                - テーブル名: receipt_header
                ## カラム
                | カラム名 | 型 | NOT NULL | PK |
                | --- | --- | --- | --- |
                | id | bigint | YES | YES |
                | application_id | varchar(32) | YES | NO |
                """);
        TableSchemaLibrary library = TableSchemaLibrary.load(tableDir);
        check(library.size() == 2, "表構造ライブラリは 2 テーブル（_template.md は除外）");
        check(library.find("RECEIPT_DETAIL") != null
                        && library.find("receipt_detail").path().getFileName().toString().equals("receipt_detail.md"),
                "表名（大小文字無視）で 1 ファイルを引き当てる");
        check(library.find("no_such") == null, "未登録テーブルは null");
        check(library.subset(List.of("receipt_header", "ghost", "RECEIPT_HEADER")).size() == 1,
                "部分ライブラリは実在表だけを大小文字無視で残す");

        // ---- 使用表だけを LLM へ渡す（全表ダンプ禁止）----
        Path scopeDir = Files.createTempDirectory("sql-scope-lib");
        Files.writeString(scopeDir.resolve("receipt_detail.md"), """
                ## テーブル
                - テーブル名: receipt_detail
                - テーブル日本語名: 入金明細
                ## カラム
                | カラム名 | 日本語名 | 型 | NOT NULL | PK |
                | --- | --- | --- | --- | --- | --- |
                | id | 明細ID | bigint | YES | YES |
                | application_id | アプリケーションID | varchar(32) | YES | NO |
                """);
        Files.writeString(scopeDir.resolve("receipt_header.md"), """
                ## テーブル
                - テーブル名: receipt_header
                - テーブル日本語名: 入金ヘッダ
                ## カラム
                | カラム名 | 日本語名 | 型 | NOT NULL | PK |
                | --- | --- | --- | --- | --- | --- |
                | id | ヘッダID | bigint | YES | YES |
                | receipt_date | 入金日 | date | NO | NO |
                """);
        TableSchemaLibrary scopeLib = TableSchemaLibrary.load(scopeDir);
        NameMapping scopeMap = NameMapping.from(scopeLib);
        TableSelector.Selection both = TableSelector.select(
                "入金明細と入金ヘッダをアプリケーションIDで結合する",
                "receipt_detail と receipt_header を application_id で結合する", scopeLib, scopeMap);
        check(both.used().size() == 2 && !both.fallbackAll(), "日本語／英語の両方から使用表 2 件を選ぶ");
        TableSelector.Selection one = TableSelector.select(
                "## 対象\n- テーブル: 入金明細\n", "## 対象\n- テーブル: receipt_detail\n",
                scopeLib, scopeMap);
        check(one.used().equals(List.of("receipt_detail")) && one.scopedSize() == 1,
                "明示された 1 表だけに絞られる: " + one.used());
        check(one.scopedMapping().size() < scopeMap.size(), "対応表も使用表の分だけに絞られる");
        String scopedPrompt = SqlLlmGenerator.tablesBlock(one.scoped(), one.scopedMapping());
        check(scopedPrompt.contains("receipt_detail") && !scopedPrompt.contains("receipt_header"),
                "LLM へ渡す表構造文字列に無関係な表が無い");
        TableSelector.Selection empty = TableSelector.select("何かを取得したい", "何かを取得したい",
                scopeLib, scopeMap);
        check(empty.fallbackAll() && empty.scopedSize() == 2, "使用表不明時は全表へフォールバックして根拠を失わない");
        expectReject("表名が重複", () -> {
            try {
                Files.writeString(tableDir.resolve("dup.md"), tableMd);
                TableSchemaLibrary.load(tableDir);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
        Files.deleteIfExists(tableDir.resolve("dup.md"));
        expectReject("テンプレートのプレースホルダ残り", () -> {
            try {
                Path bad = Files.createTempDirectory("sql-table-bad");
                Files.writeString(bad.resolve("bad.md"), "## テーブル\n- テーブル名: <table_name>\n"
                        + "## カラム\n| カラム名 | 型 | NOT NULL | PK |\n| --- | --- | --- | --- |\n| id | bigint | YES | YES |\n");
                TableSchemaLibrary.load(bad);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });

        // ---- prompt テンプレート（md ファイル管理）----
        String loaded = SqlPromptTemplate.load(Path.of("specs/prompts/sql-draft.md"));
        check(loaded.contains("{{DESCRIPTION}}") && !loaded.contains("<!--"),
                "既定 prompt テンプレートを読み込み、コメントは除去済み");
        String tpl = SqlPromptTemplate.stripComments(
                "<!-- memo -->D={{DESCRIPTION}} V={{VARS}} C={{CONTEXTS}} T={{TABLES}} note={{key}}");
        check(tpl.startsWith("D=") && tpl.contains("note={{key}}"),
                "HTML コメントは除去され小文字の説明文は残る");
        String filled = SqlPromptTemplate.fill(tpl, "DESC", "VARS", "CTX", "TBL");
        check(filled.contains("DESC") && filled.contains("CTX") && !filled.contains("{{DESCRIPTION}}"),
                "大文字プレースホルダだけが差し込まれる");
        expectReject("必須プレースホルダ欠け", () ->
                SqlPromptTemplate.fill("x {{DESCRIPTION}}", "d", "v", "c", "t"));
        expectReject("未知のプレースホルダ", () ->
                SqlPromptTemplate.fill("{{DESCRIPTION}} {{VARS}} {{CONTEXTS}} {{TABLES}} {{TYPO}}", "d", "v", "c", "t"));

        // ---- バインド変数 3 形式（列型から Java が確定）----
        check(SqlVariableType.of("varchar(32)") == SqlVariableType.CHAR, "varchar は文字型");
        check(SqlVariableType.of("date") == SqlVariableType.DATE
                && SqlVariableType.of("timestamp") == SqlVariableType.DATE, "date/timestamp は日付型");
        check(SqlVariableType.of("bigint") == SqlVariableType.NUMBER
                && SqlVariableType.of("decimal(10,2)") == SqlVariableType.NUMBER, "bigint/decimal は数値型");
        check(SqlVariables.isBind("/*x*/'APP001'") && SqlVariables.isBind("/*x*/CURRENT_DATE")
                && SqlVariables.isBind("/*x*/9"), "3 形式すべてを認識");
        check(SqlVariables.typeOf("/*x*/'APP001'") == SqlVariableType.CHAR
                && SqlVariables.typeOf("/*x*/CURRENT_DATE") == SqlVariableType.DATE
                && SqlVariables.typeOf("/*x*/9") == SqlVariableType.NUMBER, "3 形式の型を判定");

        String typedMd = """
                ## 対象
                - テーブル: receipt_detail
                - 種別: SELECT
                ## 選択列
                - id
                ## 条件
                - application_id = {{applicationId}}
                - payment_date = {{paymentDate}}
                - amount >= {{minAmount}}
                ## 並び順
                - id DESC
                """;
        String typedNorm = SqlVariables.normalizeRequest(typedMd, schema);
        check(typedNorm.contains("/*applicationId*/'{{applicationId}}'"), "文字型は引用符付きへ正規化");
        check(typedNorm.contains("/*paymentDate*/{{paymentDate}}"), "日付型は引用符なしへ正規化");
        check(typedNorm.contains("/*minAmount*/{{minAmount}}"), "数値型は引用符なしへ正規化");
        String typedSql = renderer.render(SqlRequestParser.parse(
                        new TemplateResolver(Map.of("applicationId", "APP001",
                                "paymentDate", "CURRENT_DATE", "minAmount", "9")).resolve(typedNorm)),
                schema);
        check(typedSql.contains("/*applicationId*/'APP001'")
                        && typedSql.contains("/*paymentDate*/CURRENT_DATE")
                        && typedSql.contains("/*minAmount*/9"),
                "列型に応じた 3 形式が SQL に出る: " + typedSql);

        // ---- バインド変数名の小驼峰自動正規化（/*変数的小驼峰*/）----
        check(SqlVariables.bindName("application_id").equals("applicationId")
                        && SqlVariables.bindName("applicationId").equals("applicationId"),
                "参照名は常に小驼峰へ正規化される");
        String snakeMd = """
                ## 対象
                - テーブル: receipt_detail
                - 種別: SELECT
                ## 選択列
                - id
                ## 条件
                - application_id = {{application_id}}
                """;
        String snakeNorm = SqlVariables.normalizeRequest(snakeMd, schema);
        check(snakeNorm.contains("/*applicationId*/'{{application_id}}'"),
                "スネーク参照が小驼峰マーカーへ正規化される: " + snakeNorm.strip());
        String snakeSql = renderer.render(SqlRequestParser.parse(
                        new TemplateResolver(Map.of("application_id", "APP001")).resolve(snakeNorm)),
                schema);
        check(snakeSql.equals("SELECT id FROM receipt_detail WHERE application_id = /*applicationId*/'APP001';"),
                "値は元の参照名で解決され、マーカーだけ小驼峰: " + snakeSql);

        // ---- 日本語名 → 英語物理名（LLM へ渡す前に変換）----
        String jpTable = """
                ## テーブル
                - テーブル名: receipt_detail
                - テーブル日本語名: 入金明細
                ## カラム
                | カラム名 | 日本語名 | 型 | NOT NULL | PK |
                | --- | --- | --- | --- | --- |
                | id | 明細ID | bigint | YES | YES |
                | application_id | アプリケーションID | varchar(32) | YES | NO |
                | amount | 入金額 | bigint | NO | NO |
                """;
        TableSchema jpSchema = TableSchemaParser.parse(jpTable);
        check(jpSchema.tableJapaneseName().equals("入金明細"), "テーブル日本語名を解析");
        check(jpSchema.column("amount").japaneseName().equals("入金額"), "カラム日本語名を解析");
        check(jpSchema.typeOf("amount") == SqlVariableType.NUMBER, "日本語名列付きでも列型を判定");

        Path jpDir = Files.createTempDirectory("sql-jp-lib");
        Files.writeString(jpDir.resolve("receipt_detail.md"), jpTable);
        TableSchemaLibrary jpLib = TableSchemaLibrary.load(jpDir);
        NameMapping nm = NameMapping.from(jpLib);
        check(nm.size() == 4, "名称対応 4 件（テーブル 1 ＋ カラム 3）");
        String translated = nm.translate("入金明細の入金額とアプリケーションIDを、明細IDで並べる");
        check(translated.contains("receipt_detail") && translated.contains("amount")
                        && translated.contains("application_id") && translated.contains("id"),
                "日本語名が英語物理名へ置換された: " + translated);
        check(nm.englishTable("入金明細").equals("receipt_detail"), "日本語表名 → 英語表名");
        expectReject("日本語名の対応が曖昧", () -> {
            try {
                Path bad = Files.createTempDirectory("sql-jp-amb");
                Files.writeString(bad.resolve("a.md"), """
                        ## テーブル
                        - テーブル名: table_a
                        ## カラム
                        | カラム名 | 日本語名 | 型 | NOT NULL | PK |
                        | --- | --- | --- | --- | --- |
                        | id | 識別子 | bigint | YES | YES |
                        """);
                Files.writeString(bad.resolve("b.md"), """
                        ## テーブル
                        - テーブル名: table_b
                        ## カラム
                        | カラム名 | 日本語名 | 型 | NOT NULL | PK |
                        | --- | --- | --- | --- | --- |
                        | code | 識別子 | varchar(8) | YES | YES |
                        """);
                NameMapping.from(TableSchemaLibrary.load(bad));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });

        // ---- 種別別 prompt テンプレート（SQL 雛形入り md）----
        for (SqlKind k : SqlKind.values()) {
            String t = SqlPromptTemplate.load(SqlPromptTemplate.defaultKindPath(k));
            check(t.contains("{{DESCRIPTION}}") && !t.contains("<!--"),
                    k + " 用 prompt テンプレートを読込（コメント除去済み）");
        }
        String selectTpl = SqlPromptTemplate.load(SqlPromptTemplate.defaultKindPath(SqlKind.SELECT));
        check(selectTpl.contains("INNER JOIN") && selectTpl.contains("/*minAmount*/9"),
                "SELECT テンプレートは多表結合と変数 3 形式の SQL 雛形を含む");

        // ---- SqlTextGate（大模型が書いた SQL の確定的ゲート）----
        TableSchemaLibrary realLib = TableSchemaLibrary.load(Path.of("specs/tables"));
        String joinSql = """
                SELECT d.id, d.amount, h.status
                FROM receipt_detail AS d
                INNER JOIN receipt_header AS h ON d.application_id = h.application_id
                WHERE d.application_id = /*applicationId*/'APP001'
                  AND h.receipt_date = /*receiptDate*/CURRENT_DATE
                  AND d.amount >= /*minAmount*/9
                ORDER BY d.id DESC;
                """;
        SqlTextGate.Result gateResult = SqlTextGate.verify(joinSql, SqlKind.SELECT, realLib);
        check(gateResult.primaryTable().equals("receipt_detail") && gateResult.tables().size() == 2,
                "多表結合 SELECT がゲートを通過（2 テーブル）");
        check(gateResult.binds().size() == 3, "3 形式のバインド変数を検出: " + gateResult.binds());
        check(SqlTextGate.verify(
                        "UPDATE receipt_detail SET status = 'PAID' WHERE application_id = /*applicationId*/'APP001';",
                        SqlKind.UPDATE, realLib).primaryTable().equals("receipt_detail"),
                "UPDATE がゲートを通過");
        check(SqlTextGate.verify(
                        "DELETE FROM receipt_detail WHERE application_id = /*applicationId*/'APP001';",
                        SqlKind.DELETE, realLib).primaryTable().equals("receipt_detail"),
                "DELETE がゲートを通過");
        for (SqlKind k : SqlKind.values()) {
            SqlTextDraft mock = new SqlLlmGenerator(null, false).generateMock(k, Map.of(), realLib);
            SqlTextGate.Result r = SqlTextGate.verify(mock.sql(), k, realLib);
            check(!r.tables().isEmpty(), "mock " + k + " がゲートを通過: " + r.tables());
        }

        // ---- 負向：確定的ゲート ----
        expectReject("不正なコメント断片", () -> renderer.render(SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: DELETE
                ## 条件
                - id = /* oops */'1'
                """), schema));
        expectReject("並び順のバインド変数", () -> renderer.render(SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: SELECT
                ## 選択列
                - id
                ## 並び順
                - id /*x*/'1' DESC
                """), schema));
        expectReject("バインド変数名が不正", () -> renderer.render(SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: DELETE
                ## 条件
                - id = /*1bad*/'1'
                """), schema));
        expectReject("不明な種別", () -> SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: INSERT
                ## 条件
                - id = 1
                """));
        expectReject("SELECT に選択列なし", () -> SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: SELECT
                """));
        expectReject("UPDATE に設定値なし", () -> SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: UPDATE
                """));
        expectReject("DELETE に条件なし（全件削除）", () -> SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: DELETE
                """));
        expectReject("テーブル名不一致", () -> renderer.render(SqlRequestParser.parse("""
                ## 対象
                - テーブル: other_table
                - 種別: SELECT
                ## 選択列
                - id
                """), schema));
        expectReject("存在しない選択列", () -> renderer.render(SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: SELECT
                ## 選択列
                - id
                - ghost_column
                """), schema));
        expectReject("存在しない条件列", () -> renderer.render(SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: DELETE
                ## 条件
                - ghost = 1
                """), schema));
        expectReject("セミコロン注入", () -> renderer.render(SqlRequestParser.parse("""
                ## 対象
                - テーブル: receipt_detail
                - 種別: DELETE
                ## 条件
                - id = 1; DROP TABLE receipt_detail
                """), schema));
        expectReject("表構造のカラム行が不正", () -> TableSchemaParser.parse("""
                ## テーブル
                - テーブル名: t
                ## カラム
                カラム名は表形式で書くこと
                """));

        // ---- 負向：大模型が書いた SQL のゲート ----
        expectReject("LLM SQL: 存在しないテーブル", () ->
                SqlTextGate.verify("SELECT id FROM ghost_table WHERE id = 1;", SqlKind.SELECT, realLib));
        expectReject("LLM SQL: 存在しない列（JOIN 別名付き）", () ->
                SqlTextGate.verify("SELECT d.id, d.ghost FROM receipt_detail AS d WHERE d.id = 1;",
                        SqlKind.SELECT, realLib));
        expectReject("LLM SQL: 未知の別名", () ->
                SqlTextGate.verify("SELECT z.id FROM receipt_detail AS d WHERE d.id = 1;",
                        SqlKind.SELECT, realLib));
        expectReject("LLM SQL: DELETE に WHERE なし", () ->
                SqlTextGate.verify("DELETE FROM receipt_detail;", SqlKind.DELETE, realLib));
        expectReject("LLM SQL: UPDATE に WHERE なし", () ->
                SqlTextGate.verify("UPDATE receipt_detail SET status = 'PAID';", SqlKind.UPDATE, realLib));
        expectReject("LLM SQL: 種別の取り違え", () ->
                SqlTextGate.verify("SELECT id FROM receipt_detail WHERE id = 1;", SqlKind.DELETE, realLib));
        expectReject("LLM SQL: 複文／注入", () ->
                SqlTextGate.verify("SELECT id FROM receipt_detail WHERE id = 1; DROP TABLE receipt_detail;",
                        SqlKind.SELECT, realLib));
        expectReject("LLM SQL: 不正なコメント断片", () ->
                SqlTextGate.verify("SELECT id FROM receipt_detail WHERE id = /* oops */'1';",
                        SqlKind.SELECT, realLib));

        System.out.println("\n全ゲート有効。SQL 生成リンクのセルフチェック完了。");
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError("正向チェック失敗: " + label);
        System.out.println("  [OK] " + label);
    }

    private static void expectReject(String label, Runnable action) {
        try {
            action.run();
            throw new AssertionError("ゲート失效: " + label + " が受け入れられた");
        } catch (IllegalArgumentException expected) {
            System.out.println("  [拒否] " + label + " -> " + expected.getMessage().split("\n")[0]);
        }
    }
}
