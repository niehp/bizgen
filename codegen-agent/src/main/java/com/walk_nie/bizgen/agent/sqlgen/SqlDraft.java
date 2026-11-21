package com.walk_nie.bizgen.agent.sqlgen;

/**
 * LLM が出す「表構造 md と SQL 要求 md の下書き」。
 *
 * <p>モデルに SQL そのものは書かせない。書かせるのは確定側のパーサが読める
 * 2 つの md だけであり、最終的な SQL は Java のレンダラが決める。
 * 下書きはあくまで「大概の定義」——レビュー前提でファイルへ残す。
 *
 * @param tableMd   表構造 md（## テーブル / ## カラム）
 * @param requestMd SQL 要求 md（## 対象 / ## 選択列 or ## 設定値 / ## 条件）
 * @param note      判断根拠・注意点（日本語、レビュー用）
 */
public record SqlDraft(String tableMd, String requestMd, String note) {
    public SqlDraft {
        tableMd = tableMd == null ? "" : tableMd;
        requestMd = requestMd == null ? "" : requestMd;
        note = note == null ? "" : note;
    }
}
