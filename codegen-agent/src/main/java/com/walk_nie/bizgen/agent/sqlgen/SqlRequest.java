package com.walk_nie.bizgen.agent.sqlgen;

import java.util.List;

/**
 * SQL 生成要求 md から確定的に解析したスペック。
 *
 * <p>「何を出すか」は人間の仕様書から来る。モデルは介在しない——
 * 種別・テーブル・列・条件のいずれも契約であり、推論の対象ではない。
 *
 * @param kind           SELECT / UPDATE / DELETE
 * @param tableName      対象テーブル名
 * @param selectColumns  SELECT で選ぶ列（空なら全列 *）
 * @param assignments    UPDATE の SET 代入（"列 = 値" 形式）
 * @param conditions     WHERE 条件（AND で結合）
 * @param orderBy        ORDER BY 句（おわりに DESC/ASC を添えてもよい）
 */
public record SqlRequest(
        SqlKind kind,
        String tableName,
        List<String> selectColumns,
        List<String> assignments,
        List<String> conditions,
        List<String> orderBy
) {
    public SqlRequest {
        selectColumns = selectColumns == null ? List.of() : List.copyOf(selectColumns);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
    }
}
