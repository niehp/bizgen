#  SELECT 仕様：アプリケーションに紐づく入金明細を取得する

## テーブル
- テーブル名: receipt_detail

## カラム
| カラム名 | 型 | NOT NULL | PK |
| --- | --- | --- | --- |
| id | bigint | YES | YES |
| application_id | varchar(32) | YES | NO |
| amount | bigint | NO | NO |
| payment_date | date | NO | NO |
| status | varchar(16) | YES | NO |

## 対象
- テーブル: receipt_detail
- 種別: SELECT

## 選択列
- id
- amount
- payment_date

## 条件
- application_id = {{applicationId}}
- status = 'PAID'

## 並び順
- payment_date DESC
