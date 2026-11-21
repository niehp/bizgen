# SELECT 仕様：表構造ライブラリを使う版

表構造は specs/tables/receipt_detail.md（1 ファイル 1 テーブル）から取得する。
このファイルには要求だけを書く。

## 対象
- テーブル: receipt_detail
- 種別: SELECT

## 選択列
- id
- amount

## 条件
- application_id = {{applicationId}}
- status = 'PAID'

## 並び順
- payment_date DESC
