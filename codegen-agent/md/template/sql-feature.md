---
title: {{sqlTitle}}
# java | sql | both。md 一枚が生成の入口
generate: sql
# select | update | delete
sqltype: {{sqlKind}}
# 日本語の対象表名（カンマ区切り）。省略時は本文から自動検出する。
tables: {{tableNames}}
# 対象フィールド。表.フィールド名 の形式で書くと英語物理名へ変換される。
fields:
  - {{tableName}}.{{fieldName1}}
  - {{tableName}}.{{fieldName2}}
prompt: {{sqlPromptName}}
# 保存先は config/agent-config.properties の path.outputRoot 配下。指定が無ければ自動命名。
out: {{sqlOutFile}}

# ↓ ここで定義した変数が front matter 自身と本文の両方で使える（定義順に多ラウンドで解決）
sqlTitle: 受注検索SQLの生成
sqlKind: select
tableNames: 受注,顧客
tableName: 受注
fieldName1: 受注ID
fieldName2: 顧客名
applicationId: order
sqlPromptName: select-sql
sqlOutFile: order-select.sql
sqlDescription: 受注IDと顧客名を取得する。削除済みの受注は対象外とする。
sqlShape: SELECT 受注ID, 顧客名 FROM 受注 WHERE 受注ID = /*orderId*/9;
sqlCondition: 受注IDが指定されていること。
sqlReason: 不要な全件検索を避けるため。
---

# {{sqlTitle}}

{{sqlDescription}}

## 期待する SQL の形

{{sqlShape}}

## 条件・理由

- 条件: {{sqlCondition}}
- 理由: {{sqlReason}}
- 変数は md/schema/*.md に定義された書式に従うこと。

> `generate: both` にすると、この md に Java のクラス／メソッド／処理フロー定義を追記して
> Java と SQL を続けて生成できます（同じ変数が両方で使われます）。
