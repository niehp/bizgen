# {{tableJapaneseName}}（{{tablePhysicalName}}）

> 表構造 md テンプレート。1 ファイル = 1 表。
> ファイル名には日本語名または物理名を付ける（例: `受注.md`）。

## 基本情報

- 日本語名: {{tableJapaneseName}}
- 英語物理名: {{tablePhysicalName}}
- 概要: {{tableDescription}}

## カラム定義

| 日本語名 | 英語物理名 | 型 | 主キー | NOT NULL | 備考 |
| --- | --- | --- | --- | --- | --- |
| {{columnJapaneseName1}} | {{columnPhysicalName1}} | STRING | ○ | ○ | {{columnRemark1}} |
| {{columnJapaneseName2}} | {{columnPhysicalName2}} | NUMBER |  | ○ | {{columnRemark2}} |
| {{columnJapaneseName3}} | {{columnPhysicalName3}} | DATE |  |  | {{columnRemark3}} |

## 型の取り扱い

- `STRING` : SQL 変数は `/*小驼峰名*/'xx';`
- `DATE`   : SQL 変数は `/*小驼峰名*/CURREN_DATE;`
- `NUMBER` : SQL 変数は `/*小驼峰名`*/9;`

## リレーション

| from | to | 結合条件 |
| --- | --- | --- |
| {{tableJapaneseName}} | {{relationTableJapaneseName}} | {{relationCondition}} |
