# Java 命名規約（Java共通）

## 基本方針

- クラス名は大駝峰（UpperCamel）。例: `OrderService`
- メソッド名・フィールド名は小駝峰（lowerCamel）。例: `orderId`
- 定数は大蛇形（UPPER_SNAKE）。例: `MAX_RETRY_COUNT`
- パッケージは小蛇形（lower_snake）または小文字ドット区切り。例: `com.walk_nie.bizgen.order`
- 略語は 2 文字まで大文字を許容する（例: `orderId`, `customerName`）。

## 変数との対応

md 内の `{{...}}` は以下の命名スタイルへ自動変換できる。

| 表記 | スタイル | 例（元: applicationId） |
| --- | --- | --- |
| 首字母大写 | UPPER_FIRST | `ApplicationId` |
| 首字母小写 | LOWER_FIRST | `applicationId` |
| 大驼峰 | UPPER_CAMEL | `ApplicationId` |
| 小驼峰 | LOWER_CAMEL | `applicationId` |
| 大蛇形 | UPPER_SNAKE | `APPLICATION_ID` |
| 小蛇形 | LOWER_SNAKE | `application_id` |

## メソッド名の語順

`動詞 + 目的語 + 修飾` の順にする。例: `findActiveOrders`, `updateOrderStatus`。
