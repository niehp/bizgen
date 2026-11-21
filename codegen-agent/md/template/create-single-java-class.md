---
title: {{classTitle}}
mode: NEW_FILE
targetFile: {{packageName|小蛇形}}/{{className|大驼峰}}
# 保存先は機能 md では指定しない。config/agent-config.properties の path.generatedJavaRoot で指定する。
applicationId: {{applicationId}}
entityName: {{entityName}}
designs:
  - java-common/naming-convention
  - java-common/javadoc-convention
  - project-common/layer-structure
---

# {{classTitle}}

> このファイルは「Java ファイルを 1 つ新規作成する」ための機能 md テンプレートです。
> `{{...}}` は変数です。喂给大模型之前すべて置換されます。
> クラス名・メソッド名・パッケージ名は命名スタイル指定で自動変換できます。

## パッケージ情報

- パッケージ名: `{{packageName}}`
- パッケージ名(大蛇形): `{{packageName|大蛇形}}`
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

## メソッド機能の記述テンプレート（必要なものをコピーして変更する）

- 新規作成: 「{{entityName|大驼峰}}を新規登録する。入力値を検証してから永続化し、生成された識別子を返す。不正な入力は早期に例外とする。」
- 検索: 「条件に一致する{{entityName|大驼峰}}を取得する。該当なしの場合は例外ではなく空の結果を返し、呼び出し側の分岐を簡単にする。」
- 更新: 「{{entityName|大驼峰}}の内容を更新する。更新前に存在確認を行い、楽観ロック版本号を比較して更新ロストを防ぐ。」
- 削除: 「{{entityName|大驼峰}}を論理削除する。物理削除せず履歴を残し、監査要件に対応する。」
- 変換: 「{{entityName|大驼峰}}の日本語項目名を英語物理名へ変換する。SQL 生成側の表構造辞書と同じ対応表を使用する。」
- 検証: 「{{entityName|大驼峰}}の必須項目を検証する。生成コード側で決定論的にチェックし、モデルへ渡す前に失敗させる。」
