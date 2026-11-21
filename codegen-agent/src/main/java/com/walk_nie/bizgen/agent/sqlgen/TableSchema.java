package com.walk_nie.bizgen.agent.sqlgen;

import java.util.List;

/**
 * 表構造 md から確定的に解析したテーブル定義（「事実」の単一真源）。
 * 列名の存在確認はすべてこのレコードに対して行い、SQL の拼写ミスを落とす。
 *
 * @param tableName         テーブル名（英語の物理名。md の ## テーブル から）
 * @param columns           カラム定義（md の ## カラム から）
 * @param tableJapaneseName テーブルの日本語名（任意。NameMapping で使用）
 */
public record TableSchema(String tableName, List<TableColumn> columns, String tableJapaneseName) {

    /** 旧 2 引数形式（日本語名なし）との互換コンストラクタ。 */
    public TableSchema(String tableName, List<TableColumn> columns) {
        this(tableName, columns, "");
    }

    public TableSchema {
        columns = columns == null ? List.of() : List.copyOf(columns);
        tableJapaneseName = tableJapaneseName == null ? "" : tableJapaneseName.strip();
    }

    /** 指定名のカラムが定義にあるか（大文字小文字を無視、英語物理名のみ）。 */
    public boolean hasColumn(String name) {
        return column(name) != null;
    }

    /** 英語物理名でカラムを引く（大文字小文字を無視）。無ければ null。 */
    public TableColumn column(String name) {
        if (name == null) return null;
        for (TableColumn c : columns) {
            if (c.name().equalsIgnoreCase(name.strip())) return c;
        }
        return null;
    }

    /** 列のバインド変数型。未定義列は保守的に CHAR を返す。 */
    public SqlVariableType typeOf(String columnName) {
        TableColumn c = column(columnName);
        return c == null ? SqlVariableType.CHAR : c.variableType();
    }

    public List<String> columnNames() {
        return columns.stream().map(TableColumn::name).toList();
    }
}
