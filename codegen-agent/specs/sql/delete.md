#  DELETE 仕様：対象アプリケーションの入金明細を削除する（条件必須）

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
- 種別: DELETE

## 条件
- application_id = {{applicationId}}
- status = 'CANCELLED'
