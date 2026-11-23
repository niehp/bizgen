# Validate

引数の必須チェックを行うユーティリティ。

| シグネチャ | 説明 |
| --- | --- |
| `public static void requireNonBlank(String value, String name)` | 必須チェックを行い、未設定なら IllegalArgumentException を投げる |
| `public static boolean isBlank(String value)` | 文字列が null または空か判定する |
