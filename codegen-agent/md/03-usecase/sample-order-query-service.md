---
title: 受注検索サービスの新規作成
# java | sql | both。md 一枚が生成の入口（省略時は java）
generate: java
mode: NEW_FILE
targetFile: com/walk_nie/bizgen/sample/OrderQueryService.java
# 保存先は config/agent-config.properties の path.generatedJavaRoot で指定する
applicationId: order-app
entityName: Order
designs:
  - java-common/naming-convention
  - java-common/javadoc-convention
  - project-common/layer-structure
  - project-common/db-access-convention
---

# 受注検索サービスの新規作成

## パッケージ情報

- パッケージ名: `com.walk_nie.bizgen.sample`
- パッケージコメント: 受注検索に関するアプリケーションサービスを提供する。
- アプリケーションID: `{{applicationId}}`

## クラス情報

- クラス名: `{{applicationId}}{{entityName|大驼峰}}QueryService`
- クラスコメント: 受注の検索ユースケースを調整する。検索条件の検証と結果の整形を担当する。

## フィールド定義

| フィールド名 | 型 | 日本語コメント |
| --- | --- | --- |
| orderRepository | OrderRepository | 受注の永続化アクセス。検索処理を永続化層へ委譲する。 |

## メソッド定義

### findByOrderId

- 目的: 受注IDから受注を1件取得する。
- 処理内容: 受注IDの必須チェックを行い、リポジトリを呼び出して結果を返す。
- 理由: 不正なIDでの検索を生成側で防ぎ、不要な DB アクセスを削減する。
- 引数: `String orderId`
- 戻り値: `Optional<Order>`
- 例外: `IllegalArgumentException` 受注IDが未設定の場合

### findActiveOrders

- 目的: 有効な受注一覧を取得する。
- 処理内容: 顧客IDを条件に、削除フラグが立っていない受注を検索する。
- 理由: 論理削除済みデータを画面に表示しないため。
- 引数: `String customerId`
- 戻り値: `List<Order>`
- 例外: なし
