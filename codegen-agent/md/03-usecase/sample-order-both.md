---
title: 受注検索サービスと検索SQLの同時生成
# java | sql | both。md 一枚が生成の入口
generate: both
# ---- Java 契約 ----
mode: NEW_FILE
targetFile: com/walk_nie/bizgen/sample/OrderBothService.java
applicationId: order-app
entityName: Order
packageName: com.walk_nie.bizgen.sample
className: OrderBothService
designs:
  - java-common/naming-convention
  - java-common/javadoc-convention
  - project-common/layer-structure
# ---- SQL 契約 ----
sqltype: select
tables: 受注
fields:
  - 受注.受注ID
  - 受注.受注金額
  - 受注.受注日
desc: 受注IDを条件に受注を検索し、受注ID・受注金額・受注日を取得する。
---

# 受注検索サービスと検索SQLの同時生成

## パッケージ情報

- パッケージ名: `{{packageName}}`
- パッケージコメント: 受注検索に関するアプリケーションサービスを提供する。
- アプリケーションID: `{{applicationId}}`

## クラス情報

- クラス名: `{{className|大驼峰}}`
- クラスコメント: 受注検索のユースケースを調整する。

## フィールド定義

| フィールド名 | 型 | 日本語コメント |
| --- | --- | --- |
| orderRepository | OrderRepository | 受注の永続化アクセス。検索を永続化層へ委譲する。 |

## メソッド定義

### findByOrderId

- 目的: 受注IDから受注を1件取得する。
- 処理内容: 受注IDの必須チェックを行い、リポジトリを呼び出して結果を返す。
- 理由: 不正なIDでの検索を生成側で防ぐため。
- 引数: `String orderId`
- 戻り値: `Optional<Order>`
- 例外: `IllegalArgumentException` 受注IDが未設定の場合

## 処理フロー

1. 変数を展開し、Java クラスと SQL の対象表・対象フィールドを確定する。
2. Java は API 呼び出しと新規コードに分けて生成する。
3. SQL は日本語の表名・項目名を英語物理名へ変換して生成する。

## SQL 生成

対象は受注表のみ。削除済みの受注は対象外とし、受注日の降順で並べる。
変数は md/schema/*.md に定義された書式に従うこと。
