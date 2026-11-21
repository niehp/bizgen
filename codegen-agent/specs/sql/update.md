#  UPDATE 仕様：入金明細のステータスを支払済みへ更新する

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
- 種別: UPDATE

## 設定値
- status = 'PAID'
- amount = 1200

## 条件
- id = 7
- application_id = {{applicationId}}
