package com.walk_nie.bizgen.agent.sqlgen;

/**
 * 大模型が出力する「SQL 文そのもの」の下書き（型付きリーフ）。
 *
 * <p>ここでモデルが決めてよいのは SQL テキストと判断メモだけ。
 * <b>種別・使う prompt・表構造・変数値・落盤の可否は Java が決める</b>
 * （ルーティングをモデルに渡さないという全体方針）。
 *
 * <p>生成された SQL はまず {@code _draft.sql} として人間のレビュー用に残し、
 * その後 {@link SqlTextGate} の確定的ゲートを通ったものだけを正式な {@code .sql} へ保存する。
 * record は 2 層以内・全フィールド String（JSON Schema / guided_json で構造を強制）。
 *
 * @param sql  生成された SELECT / UPDATE / DELETE 文（1 文、セミコロン終わり）
 * @param note 判断根拠・レビュー観点（日本語）
 */
public record SqlTextDraft(String sql, String note) {
    public SqlTextDraft {
        sql = sql == null ? "" : sql.strip();
        note = note == null ? "" : note.strip();
    }
}
