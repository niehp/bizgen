# 入金ヘッダテーブル

入金要求のヘッダ情報。明細（入金明細）とは application_id で紐づく。

## テーブル
- テーブル名: receipt_header
- テーブル日本語名: 入金ヘッダ

## カラム
| カラム名 | 日本語名 | 型 | NOT NULL | PK |
| --- | --- | --- | --- | --- |
| id | ヘッダID | bigint | YES | YES |
| application_id | アプリケーションID | varchar(32) | YES | NO |
| receipt_date | 入金日 | date | YES | NO |
| total_amount | 合計金額 | bigint | NO | NO |
| status | ステータス | varchar(16) | YES | NO |

## 備考
- アプリケーションIDはバインド変数で受け取る（値は共通 md で定義）。
- 入金日は日付型、合計金額は数値型のバインド変数になる。
