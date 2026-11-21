package com.walk_nie.bizgen.agent.sqlgen;

/**
 * 表構造 md の 1 カラム（事実）。
 *
 * <p>日本語名は「仕様書側の名前」、name は「DB の物理名（英語）」。
 * {@link NameMapping} が日本語名 → 英語名への変換に使う。
 * SQL に書いてよいのは常に name（英語物理名）だけ。
 *
 * @param name         カラム名（英語の物理名）
 * @param type         SQL 型（例: bigint, varchar(32)）
 * @param nullable     NULL 許容か
 * @param primaryKey   主キーか
 * @param japaneseName カラムの日本語名（任意。無い場合は空文字）
 */
public record TableColumn(
        String name,
        String type,
        boolean nullable,
        boolean primaryKey,
        String japaneseName
) {
    /** 旧 4 引数形式（日本語名なし）との互換コンストラクタ。 */
    public TableColumn(String name, String type, boolean nullable, boolean primaryKey) {
        this(name, type, nullable, primaryKey, "");
    }

    public TableColumn {
        japaneseName = japaneseName == null ? "" : japaneseName.strip();
    }

    /** この列の SQL 型から決まるバインド変数の型。 */
    public SqlVariableType variableType() {
        return SqlVariableType.of(type);
    }
}
