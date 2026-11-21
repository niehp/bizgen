package com.walk_nie.bizgen.agent.sqlgen;

import com.walk_nie.bizgen.agent.Json;
import com.walk_nie.bizgen.agent.RecordSchema;
import com.walk_nie.bizgen.agent.VllmClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表構造 md と SQL 要求 md の「下書き」を LLM に作らせるジェネレータ。
 *
 * <p>責務の境界：モデルが担うのは自然言語→md の意味変換だけ。
 * 種別・列の存在・注入断片の判定・最終 SQL は Java 側のゲートが担い、
 * モデルは一切ルートを決めない（{@code guided_json} で構造も強制）。
 *
 * <p>オフライン（mock）では、設定ファイルを触らずに配線確認ができる最小下書きを返す。
 */
public final class SqlDraftGenerator {

    private static final String SYSTEM = """
            あなたは日系SIのSQL仕様アシスタントです。
            与えられた自然言語の概要から、表構造 md と SQL 要求 md の下書きを作ってください。
            最終的な SQL 文は書かないでください（Java 側が確定的に組み立てます）。
            必ず指定された JSON スキーマに従ってください。

            【tableMd の書式】
            ## テーブル
            - テーブル名: xxx
            ## カラム
            | カラム名 | 型 | NOT NULL | PK |
            | --- | --- | --- | --- |
            | id | bigint | YES | YES |

            【requestMd の書式】
            ## 対象
            - テーブル: xxx
            - 種別: SELECT / UPDATE / DELETE のいずれか
            ## 選択列     … SELECT のとき（全列は *）
            - id
            ## 設定値     … UPDATE のとき（"列 = 値"）
            - status = 'PAID'
            ## 条件       … WHERE。DELETE には必須。複数は AND
            - application_id = /*applicationId*/'{{applicationId}}'
            ## 並び順     … 任意
            - id DESC

            【変数とバインド変数】
            - {{key}} は解決済み変数です。値を推測して書き換えないでください。
            - 値をバインド変数にするときは /*変数名*/'{{変数名}}' 形式で書いてください。
            - リテラルは一重引用符で囲み、セミコロンや SQL コメントは書かないでください。

            【優先順位】共通仕様 ＞ 自然言語の概要。矛盾したら共通仕様を守ってください。
            表名・列名は自然言語から推測して構いませんが、正しさの最終判定は Java 側のゲートが行います。
            note には日本語で「何をどう判断したか」「レビューで確認すべき点」を簡潔に書いてください。
            """;

    private final VllmClient llm;
    private final boolean live;

    /** live=false なら llm を呼ばない（mock）。 */
    public SqlDraftGenerator(VllmClient llm, boolean live) {
        this.llm = llm;
        this.live = live;
    }

    /** 設定に従って生成する。設定ファイルが単一真源。 */
    public SqlDraft generate(String description, Map<String, String> vars, List<String> contexts)
            throws Exception {
        return generate(description, vars, contexts, null);
    }

    /**
     * 表構造ライブラリを渡せる版。ライブラリの該当テーブルはプロンプトへ文脈として入り、
     * mock ではその md をそのまま下書きに使う（列名を推測しない）。
     */
    public SqlDraft generate(String description, Map<String, String> vars, List<String> contexts,
                             TableSchemaLibrary library) throws Exception {
        return generate(description, vars, contexts, library, null);
    }

    /**
     * prompt テンプレート（md）を渡せる版。template=null のときは内蔵フォールバック。
     * テンプレートは md ファイルで管理し、{{DESCRIPTION}} 等の大文字プレースホルダだけ
     * Java が差し込む（{@link SqlPromptTemplate}）。
     */
    public SqlDraft generate(String description, Map<String, String> vars, List<String> contexts,
                             TableSchemaLibrary library, String promptTemplate) throws Exception {
        if (!live) return generateMock(description, vars, contexts, library);
        String schema = RecordSchema.toJsonSchema(SqlDraft.class);
        String prompt = buildUserPrompt(description, vars, contexts, library, promptTemplate);
        String json = llm.guidedJson(SYSTEM, prompt, schema);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Json.parse(json);
        SqlDraft draft = RecordSchema.fromMap(map, SqlDraft.class);
        validate(draft);
        return draft;
    }

    /** 下書きが確定側パーサで読める形かを検証する（おわりにゲートで再度検証）。 */
    public static void validate(SqlDraft draft) {
        if (draft == null || draft.tableMd().isBlank() || draft.requestMd().isBlank()) {
            throw new IllegalArgumentException("LLM 下書きが空（tableMd / requestMd が必要）");
        }
        TableSchemaParser.parse(SqlVariables.normalizeRequest(draft.tableMd()));
        SqlRequestParser.parse(SqlVariables.normalizeRequest(draft.requestMd()));
    }

    /** オフライン骨格：配線確認とパイプライン全体の動作確認に使う。 */
    public SqlDraft generateMock(String description, Map<String, String> vars, List<String> contexts) {
        return generateMock(description, vars, contexts, null);
    }

    /** 表構造ライブラリがあればその md を採用し、無ければ最小骨格を作る。 */
    public SqlDraft generateMock(String description, Map<String, String> vars, List<String> contexts,
                                 TableSchemaLibrary library) {
        Map<String, String> v = vars == null ? Map.of() : vars;
        String table = detectTable(description);
        SqlKind kind = detectKind(description);
        TableSchemaLibrary.Entry entry = library == null ? null : library.find(table);

        String skeleton = """
                ## テーブル
                - テーブル名: %s

                ## カラム
                | カラム名 | 型 | NOT NULL | PK |
                | --- | --- | --- | --- |
                | id | bigint | YES | YES |
                | application_id | varchar(32) | YES | NO |
                | status | varchar(16) | NO | NO |
                | updated_at | timestamp | NO | NO |
                """.formatted(table);
        String tableMd = entry != null ? entry.raw() : skeleton;
        TableSchema schema = entry != null ? entry.schema() : TableSchemaParser.parse(skeleton);

        boolean hasAppId = schema.hasColumn("application_id");
        String appBind = hasAppId
                ? (v.containsKey("applicationId") ? "/*applicationId*/'{{applicationId}}'" : "'APP001'")
                : "1";
        String condColumn = hasAppId ? "application_id" : "id";
        StringBuilder request = new StringBuilder();
        request.append("## 対象\n- テーブル: ").append(table)
               .append("\n- 種別: ").append(kind.name()).append("\n\n");
        switch (kind) {
            case SELECT -> request.append("## 選択列\n- id\n- status\n\n")
                    .append("## 条件\n- ").append(condColumn).append(" = ").append(appBind).append("\n\n")
                    .append("## 並び順\n- id DESC\n");
            case UPDATE -> request.append("## 設定値\n- status = 'DONE'\n\n")
                    .append("## 条件\n- ").append(condColumn).append(" = ").append(appBind).append("\n");
            case DELETE -> request.append("## 条件\n- ").append(condColumn).append(" = ").append(appBind).append("\n");
        }

        String source = entry != null ? "表構造ライブラリの " + entry.path() : "最小骨格";
        String note = "オフライン骨格。概要から種別=" + kind.name() + "、テーブル=" + table
                + " を仮定し、表構造は" + source + "を使った。"
                + "実生成は設定ファイルで llm.mode=live にしてローカル vLLM を使う。";
        SqlDraft draft = new SqlDraft(tableMd, request.toString(), note);
        validate(draft);
        return draft;
    }

    /**
     * LLM への user prompt。テンプレートがあれば md のプレースホルダへ差し込み、
     * 無ければ内蔵フォールバック（共通文脈 → 変数 → 表構造 → 概要の順）で組み立てる。
     */
    static String buildUserPrompt(String description, Map<String, String> vars, List<String> contexts,
                                  TableSchemaLibrary library, String promptTemplate) {
        String desc = description == null ? "" : description.strip();
        if (promptTemplate != null) {
            return SqlPromptTemplate.fill(promptTemplate, desc, varsBlock(vars),
                    contextsBlock(contexts), tablesBlock(library));
        }
        return (contextsBlock(contexts) + varsBlock(vars) + tablesBlock(library)
                + "# 今回の概要（自然言語）\n" + desc + "\n");
    }

    static String contextsBlock(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("# 上位仕様（共通 md。優先順：上 → 下）\n");
        for (String c : contexts) sb.append("\n").append(c.strip()).append('\n');
        return sb.append('\n').toString();
    }

    static String varsBlock(Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("# 解決済み変数（この値をそのまま使うこと）\n");
        vars.forEach((k, val) -> sb.append("- ").append(k).append(" = ").append(val).append('\n'));
        return sb.append('\n').toString();
    }

    static String tablesBlock(TableSchemaLibrary library) {
        if (library == null || library.size() == 0) return "";
        return "# 既存表構造（列名・型はここから変更しないこと）\n" + library.promptContext() + "\n";
    }

    private static SqlKind detectKind(String description) {
        String s = description == null ? "" : description.toUpperCase(Locale.ROOT);
        if (s.contains("DELETE") || s.contains("削除")) return SqlKind.DELETE;
        if (s.contains("UPDATE") || s.contains("更新") || s.contains("変更")) return SqlKind.UPDATE;
        return SqlKind.SELECT;
    }

    /** 概要文からテーブル名を取り出す（「テーブル: xxx」行）。見つからなければ既定名。 */
    public static String detectTable(String description) {
        if (description != null) {
            for (String line : description.lines().toList()) {
                String s = line.strip();
                if (!s.contains("テーブル")) continue;
                int idx = s.indexOf(':');
                if (idx < 0) idx = s.indexOf('：');
                if (idx >= 0) {
                    String name = s.substring(idx + 1).replaceAll("[^A-Za-z0-9_]", "");
                    if (!name.isBlank()) return name;
                }
            }
        }
        return "sample_table";
    }
}
