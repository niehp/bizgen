---
title: 受注と顧客の結合検索
# generate: java | sql | both。md 一枚で生成対象を決める（要件 4）
generate: sql
sqltype: select
tables: 受注,顧客
# SQL で扱う対象フィールド（日本語名 / 表.フィールド名）。英語物理名へ変換して prompt に渡す。
fields:
  - 受注.受注ID
  - 受注.受注金額
  - 受注.受注日
  - 顧客.顧客名
applicationId: order-app
# 対象が明記されていない場合、本文から日本語表名を自動検出する
---

# 受注と顧客の結合検索

受注（`{{applicationId}}` に紐づくもの）と顧客を結合し、
受注ID・顧客名・受注日・受注金額を取得する。
削除済みの受注は対象外とし、受注日の降順で並べる。

主キーは受注.受注ID、顧客.顧客ID とする。
申込ID の代わりに `{{applicationId}}` を条件に使う。

## 期待する SQL の形
SELECT 文（複数表の JOIN 可）。
変数は md/schema/*.md に定義された書式に従うこと。
