package com.walk_nie.bizgen.agent.mdgen;

import com.walk_nie.bizgen.agent.Json;
import com.walk_nie.bizgen.agent.RecordSchema;
import com.walk_nie.bizgen.agent.VllmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * メソッド本体とコメント文のジェネレータ。LLM は MethodBody（意味／下書き）だけを出し、
 * シグネチャも Javadoc の形も MethodSpec（確定側）が与える。
 * 生成後は「引数コメント数」「API ホワイトリスト」「コンパイル」の確定的ゲートを通す。
 *
 * <p>コメント方針（LLM への指示）：日本語で「何をするか」「なぜそうするか」を簡潔に。
 * タグ名（@param の引数名など）は書かせない——Java 側が実シグネチャから生成するため、
 * モデルが書いたタグを採用すると実引数とズレるおそれがあるから。
 */
public final class BodyGenerator {

    private static final String SYSTEM = """
            あなたは日系SIのコード生成アシスタントです。
            与えられたメソッド仕様（対象・処理概要・受入基準）を満たすメソッド本体とコメント文を生成します。
            必ず指定されたJSONスキーマに従ってください。
            引数・戻り値・使用可能APIは変更せず、その範囲内で実装してください。

            【コード】
            statements にはコンパイル可能なJava文を1行ずつ入れてください。

            【コメント】すべて日本語で「何をするか」「なぜそうするか」を簡潔に書いてください。
            - methodComment : メソッドの目的（1〜2文）。@param や @return などのタグは書かないこと。
            - paramComments: statements ではなく引数と同じ順番で、各引数の意味と必要性。
            - returnComment: 戻り値が何で、なぜその値を返すか（void なら空文字）。
            - classComment  : クラスの役割と、なぜこのクラスが必要か。
            - packageComment: パッケージの役割と、なぜこのまとまりか。
            - lineComments  : 各 statement と同じ順番で「何をするか、なぜそうするか」。

            【重要】@param / @return / @since などの Javadoc タグ文字列は一切書かないでください。
            タグの形と引数名は Java 側が実際のシグネチャから確定的に組み立てます。
            モデルが書いたタグは実引数とズレる可能性があるため採用しません。
            （コメントを残すことで、レビュアーが仕様との対応を判断できるようにするため）

            【階層仕様の優先順位】複数レベルの仕様が渡された場合は次の順に従ってください。
             java共通 ＞ プロジェクト共通 ＞ 機能 ＞ Javaクラス ＞ メソッド。
            上位の共通制約・命名規則・禁止APIは下位より優先し、矛盾したら上位を守ってください。
            {{...}} の変数はすべて解決済みです。残っているプレースホルダはそのまま扱い、
            値を推測して埋めないでください。
            """;

    private final VllmClient llm;

    public BodyGenerator(VllmClient llm) {
        this.llm = llm;
    }

    /** オンライン：ローカル vLLM を呼ぶ。guided_json で構造を強制する。 */
    public MethodBody generateLive(MethodSpec spec) throws Exception {
        String schema = RecordSchema.toJsonSchema(MethodBody.class);
        String prompt = buildPrompt(spec);
        String json = llm.guidedJson(SYSTEM, prompt, schema);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Json.parse(json);
        return RecordSchema.fromMap(map, MethodBody.class);
    }

    /** オフライン：構造が正しくコンパイルも通る最小本体を作る（配線確認用）。 */
    public MethodBody generateMock(MethodSpec spec) {
        List<String> imports = new ArrayList<>();
        List<String> statements = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        String ret = spec.returnType().strip();

        if (ret.startsWith("List<")) {
            imports.add("java.util.List");
            imports.add("java.util.ArrayList");
            statements.add("List<String> result = new ArrayList<>();");
            comments.add("一致項目を格納する（順序を保つため配列リストを使う）");
            statements.add("return result;");
            comments.add("組み立てた結果を返す（受入基準の順序保証を満たすため）");
        } else if (!ret.equals("void")) {
            statements.add("return " + defaultValue(ret) + ";");
            comments.add("既定値を返す（オフライン配線確認用の最小実装）");
        }

        // 引数コメントは parameters と 1:1（ゲートを通すため数を合わせる）
        List<String> paramComments = new ArrayList<>();
        for (String p : spec.parameters()) {
            paramComments.add(p + " を受け取る（処理対象を明示して誤用を防ぐ）");
        }
        String retComment = ret.equals("void")
                ? "" : ret + " を返す（呼び出し側が結果を判定できるようにするため）";
        String clsComment = spec.className() + " は " + spec.methodName()
                + " を中心とした業務処理を提供する（責務を一箇所に集めて変更箇所を限定するため）";
        String pkgComment = hasText(spec.packageName())
                ? spec.packageName() + " 配下の業務ロジックをまとめる（依存方向を保ち再利用しやすくするため）"
                : "";

        return new MethodBody(
                "オフライン確認用の骨格。実実装は --live でモデルが生成する。",
                imports,
                spec.methodName() + " は " + ret + " を返す（仕様: "
                        + spec.summary().lines().findFirst().orElse("") + "）",
                paramComments,
                retComment,
                clsComment,
                pkgComment,
                statements,
                comments);
    }

    /** 戻り値型→妥当な既定値。どんな仕様でも mock がコンパイルできるようにするため。 */
    private static String defaultValue(String type) {
        return switch (type) {
            case "String" -> "\"\"";
            case "int", "Integer", "short", "Short", "byte", "Byte" -> "0";
            case "long", "Long" -> "0L";
            case "double", "Double", "float", "Float" -> "0.0";
            case "boolean", "Boolean" -> "false";
            case "char", "Character" -> "'\\0'";
            default -> "null";
        };
    }

    /** 強型別スペックをそのまま LLM への user prompt に組み立てる。 */
    static String buildPrompt(MethodSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 実装対象メソッド\n")
          .append("- クラス: ").append(spec.className()).append('\n')
          .append("- メソッド: ").append(spec.methodName()).append('\n')
          .append("- 戻り値: ").append(spec.returnType()).append('\n')
          .append("- 引数: ").append(String.join(", ", spec.parameters())).append('\n')
          .append("- モード: ").append(spec.operation()).append('\n')
          .append("\n# 処理概要\n").append(spec.summary()).append('\n')
          .append("\n# 使用してよいAPI\n");
        if (spec.allowedApis().isEmpty()) sb.append("- （なし）\n");
        else for (String api : spec.allowedApis()) sb.append("- ").append(api).append('\n');
        sb.append("\n# 受入基準\n");
        if (spec.acceptance().isEmpty()) sb.append("- （なし）\n");
        else for (String a : spec.acceptance()) sb.append("- ").append(a).append('\n');
        return sb.toString();
    }

    /** オンライン（階層付き）：全レベルの文脈を粗い順に付けて vLLM を呼ぶ。 */
    public MethodBody generateLive(LayeredSpec layered) throws Exception {
        String schema = RecordSchema.toJsonSchema(MethodBody.class);
        String json = llm.guidedJson(SYSTEM, buildPrompt(layered), schema);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Json.parse(json);
        return RecordSchema.fromMap(map, MethodBody.class);
    }

    /** オフライン（階層付き）：文脈は検証のみ、本体は mock（配線確認用）。 */
    public MethodBody generateMock(LayeredSpec layered) {
        return generateMock(layered.method()); // 文脈は prompt 側で効く、mock 本体は共通
    }

    /** 階層 prompt：共通制約（粗い）→ メソッド仕様（細かい）の順。変数表も冒頭に載せる。 */
    static String buildPrompt(LayeredSpec layered) {
        StringBuilder sb = new StringBuilder();
        if (!layered.vars().isEmpty()) {
            sb.append("# 解決済み変数（指定値をそのまま使うこと）\n");
            layered.vars().forEach((k, v) -> sb.append("- ").append(k).append(" = ").append(v).append('\n'));
            sb.append('\n');
        }
        if (!layered.contexts().isEmpty()) {
            sb.append("# 上位階層の仕様（優先順：上 → 下）\n");
            for (LayeredSpec.LayerDoc doc : layered.contexts()) {
                sb.append("\n## 【").append(doc.level().japanese()).append("】").append(doc.source()).append("\n")
                  .append(doc.content().strip()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("# 今回の実装対象（メソッド級）\n\n");
        sb.append(buildPrompt(layered.method()));
        return sb.toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
