# プロジェクト共通 / SQL バインド変数の既定値

SQL 雛形（prompt テンプレート）と概要 md で使う変数をここで定義する。
**共通 md の定義は大模型へ渡す前にすべて具体的な値へ置換される**。
個別案件は `--var KEY=VALUE` で上書きできる（共通定義より優先）。

{{receiptDate=CURRENT_DATE}}
{{paymentDate=CURRENT_DATE}}
{{updatedAt=CURRENT_DATE}}
{{minAmount=9}}

## 型ごとの書式
- 文字型（varchar 等）  : `/*変数*/'xx'`  … 例 `/*applicationId*/'APP001'`
- 日付型（date 等）    : `/*変数*/CURRENT_DATE` … 例 `/*paymentDate*/CURRENT_DATE`
- 数値型（bigint 等）  : `/*変数*/9` … 例 `/*minAmount*/9`

どの形式になるかは**表構造 md の列型**から Java が決める（モデルは判断しない）。
