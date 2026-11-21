package com.walk_nie.bizgen.agent.sqlgen;

import java.util.Locale;

/**
 * 生成する SQL の種別。
 *
 * <p>仕様書の「種別」から確定的に決める。モデルに判断させない——
 * なぜなら UPDATE と DELETE を取り違えると取り返しがつかないから。
 */
public enum SqlKind {
    SELECT, UPDATE, DELETE;

    /** 日本語・英字の揺れを吸収して種別を引く。不明なら拒否（確定的ゲート）。 */
    public static SqlKind from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SQL 種別が未指定（## 対象 に - 種別: SELECT/UPDATE/DELETE）");
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        for (SqlKind kind : values()) {
            if (kind.name().equals(v)) return kind;
        }
        throw new IllegalArgumentException("不明な SQL 種別「" + value + "」（SELECT/UPDATE/DELETE のみ）");
    }
}
