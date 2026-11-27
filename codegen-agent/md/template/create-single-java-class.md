---
title: {{classTitle}}
# java | sql | both。md 一枚が生成の入口
generate: java
mode: NEW_FILE
targetFile: {{packagePath}}/{{className}}
# 保存先は機能 md では指定しない。config/agent-config.properties の path.generatedJavaRoot で指定する。

# ↓ ここで定義した変数が front matter 自身と本文の両方で使える（定義順に多ラウンドで解決）
classTitle: 受注検索サービスの新規作成
applicationId: order
entityName: Order
classKind: Service
packageName: com.walk_nie.bizgen.sample
packagePath: com/walk_nie/bizgen/sample
packageComment: 受注検索に関するアプリケーションサービスを提供する。
className: {{entityName|大驼峰}}QueryService
classComment: 受注の検索ユースケースを調整する。検索条件の検証と結果の整形を担当する。

fieldName1: orderRepository
fieldType1: OrderRepository
fieldComment1: 受注の永続化アクセス。検索処理を永続化層へ委譲する。
fieldName2: orderMapper
fieldType2: OrderMapper
fieldComment2: 受注と SQL の変換を担当する。SQL 生成結果との対応を保つ。

methodName1: findByOrderId
methodPurpose1: 受注IDから受注を1件取得する。
methodBehavior1: 受注IDの必須チェックを行い、リポジトリを呼び出して結果を返す。
methodReason1: 不正なIDでの検索を生成側で防ぎ、不要な DB アクセスを削減する。
methodArgs1: String orderId
methodReturn1: Optional<Order>
methodThrows1: IllegalArgumentException 受注IDが未設定の場合
methodStep1: 受注IDが空でないことを検証する。
methodStep2: リポジトリを呼び出し、結果をそのまま返す。

designs:
  - java-common/naming-convention
  - java-common/javadoc-convention
  - project-common/layer-structure
---

# {{classTitle}}

> このファイルは「Java ファイルを 1 つ新規作成する」ための機能 md テンプレートです。
> 波括弧 2 つで囲んだ記法は変数です。front matter の定義順に解決され、喂给大模型之前すべて置換されます。
> クラス名・メソッド名・パッケージ名は命名スタイル指定で自動変換できます。

## パッケージ情報

- パッケージ名: `{{packageName}}`
- パッケージパス: `{{packagePath}}`
- パッケージコメント: {{packageComment}}

## クラス情報

- クラス名: `{{className|大驼峰}}`
- クラスコメント: {{classComment}}
- 種別: {{classKind}}  ※ Service / Repository / Dto / Util など

## フィールド定義

| フィールド名 | 型 | 日本語コメント |
| --- | --- | --- |
| {{fieldName1|小驼峰}} | {{fieldType1}} | {{fieldComment1}} |
| {{fieldName2|小驼峰}} | {{fieldType2}} | {{fieldComment2}} |

## メソッド定義

### {{methodName1|小驼峰}}

- 目的: {{methodPurpose1}}
- 処理内容: {{methodBehavior1}}
- 理由: {{methodReason1}}
- 引数: {{methodArgs1}}
- 戻り値: {{methodReturn1}}
- 例外: {{methodThrows1}}
1. {{methodStep1}}
2. {{methodStep2}}

## 処理フロー

1. 入力値を決定論的に検証する。
2. 既存 API を function tool で特定し、呼び出す。
3. 新規コードと API 呼び出しを結合し、日本語コメントを付与する。

## メソッド機能の記述テンプレート（必要なものをコピーして変更する）

- 新規作成: 「{{entityName|大驼峰}}を新規登録する。入力値を検証してから永続化し、生成された識別子を返す。不正な入力は早期に例外とする。」
- 検索: 「条件に一致する{{entityName|大驼峰}}を取得する。該当なしの場合は例外ではなく空の結果を返し、呼び出し側の分岐を簡単にする。」
- 更新: 「{{entityName|大驼峰}}の内容を更新する。更新前に存在確認を行い、楽観ロック版本号を比較して更新ロストを防ぐ。」
- 削除: 「{{entityName|大驼峰}}を論理削除する。物理削除せず履歴を残し、監査要件に対応する。」
- 変換: 「{{entityName|大驼峰}}の日本語項目名を英語物理名へ変換する。SQL 生成側の表構造辞書と同じ対応表を使用する。」
- 検証: 「{{entityName|大驼峰}}の必須項目を検証する。生成コード側で決定論的にチェックし、モデルへ渡す前に失敗させる。」
