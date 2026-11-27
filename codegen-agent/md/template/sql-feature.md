---
title: {{sqlTitle}}
# java | sql | both。md 一枚が生成の入口
generate: sql
# select | update | delete
sqltype: {{sqlType}}
# 日本語の対象表名（カンマ区切り）。省略時は本文から自動検出する。
tables: {{tableNames}}
# 対象フィールド。表.フィールド名 の形式で書くと英語物理名へ変換される。
fields:
  - {{tableName}}.{{fieldName1}}
  - {{tableName}}.{{fieldName2}}
applicationId: {{applicationId}}
prompt: {{sqlPromptName}}
# 保存先は config/agent-config.properties の path.outputRoot 配下。指定が無ければ自動命名。
out: {{sqlOutFile}}
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
