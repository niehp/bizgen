package com.walk_nie.bizgen.agent.mdgen;

import java.util.List;

/**
 * LLM が出すメソッド本体＋コメントの下書き。「意味／コード」部分だけを持ち、
 * シグネチャは持たない（シグネチャは確定側 MethodSpec から来る）。
 * RecordSchema → guided_json を通し、構造はデコーダが保証する。
 *
 * <p>コメントの分工（重要）：
 * <ul>
 *   <li>コメントの「文章」は LLM が書く（何をするか／なぜそうするか）。</li>
 *   <li>Javadoc の「形」は Java 側が決める——{@code @param} の引数名は MethodSpec から取る。
 *       なぜなら JavaParser は @param 名と実引数の不一致を検査しないため、
 *       モデルにタグ名を書かせるとレビューをすり抜けるから。</li>
 * </ul>
 *
 * @param reasoning      推論説明（審査用、フローには参加しない）
 * @param imports        追加する import
 * @param methodComment  メソッドの説明（何をするか／なぜ）。タグは書かないこと
 * @param paramComments  parameters と 1:1 対応する引数説明（数が違えばゲートが拒否）
 * @param returnComment  戻り値の説明（void のときは空で可）
 * @param classComment   クラス Javadoc の説明（何をするか／なぜ）
 * @param packageComment パッケージ Javadoc の説明（package-info.java に出る）
 * @param statements     メソッド本体の文（各要素は Java 文として妥当であること）
 * @param lineComments   statements と 1:1 対応する行コメント（数が足りなければ付与しない）
 */
public record MethodBody(
        String reasoning,
        List<String> imports,
        String methodComment,
        List<String> paramComments,
        String returnComment,
        String classComment,
        String packageComment,
        List<String> statements,
        List<String> lineComments
) {
    /** 旧フォーマット（5 引数）との互換。既存 demo を壊さないための橋渡し。 */
    public MethodBody(String reasoning, List<String> imports, String methodComment,
                      List<String> statements, List<String> lineComments) {
        this(reasoning, imports, methodComment, List.of(), "", "", "", statements, lineComments);
    }

    /** 対応する行コメントを返す。数が足りなければ空文字（付与しない）。 */
    public String commentAt(int index) {
        return lineComments != null && index < lineComments.size()
                ? lineComments.get(index) : "";
    }

    /** 対応する引数コメントを返す。数が足りなければ空文字。 */
    public String paramCommentAt(int index) {
        return paramComments != null && index < paramComments.size()
                ? paramComments.get(index) : "";
    }
}
