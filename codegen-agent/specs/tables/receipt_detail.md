# 入金明細テーブル

アプリケーションに紐づく入金明細を保持する。ステータスと支払日で照会・更新に使う。
入金ヘッダとは application_id で結合する（多表結合のサンプル）。

## テーブル
- テーブル名: receipt_detail
- テーブル日本語名: 入金明細

## カラム
| カラム名 | 日本語名 | 型 | NOT NULL | PK |
| --- | --- | --- | --- | --- |
| id | 明細ID | bigint | YES | YES |
| application_id | アプリケーションID | varchar(32) | YES | NO |
| amount | 入金額 | bigint | NO | NO |
| payment_date | 支払日 | date | NO | NO |
| status | ステータス | varchar(16) | YES | NO |
| created_at | 作成日時 | timestamp | NO | NO |
| updated_at | 更新日時 | timestamp | NO | NO |

## 備考
- アプリケーションIDはバインド変数で受け取る（値は共通 md で定義）。
- 支払日は日付型、入金額は数値型のバインド変数になる（列型から自動判定）。
- 削除は条件を必須とする（全件削除を防ぐ）。
