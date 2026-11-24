# StringUtils

文字列判定のユーティリティ。入力チェックでは新規実装せずこれを利用する。

| シグネチャ | 説明 |
| --- | --- |
| `public static boolean isBlank(String value)` | 文字列が null または空白のみか判定する |
| `public static String trimToEmpty(String value)` | 前後の空白を除去し null を空文字として扱う |
