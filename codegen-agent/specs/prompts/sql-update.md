<!--
UPDATE 専用プロンプトテンプレート（SqlLlmFromMd が 種別=UPDATE のときに読む）。
「SQL 雛形」+「md の表構造」を大模型へ渡し、UPDATE 文を 1 つ生成させてファイルへ保存する。

プレースホルダ（大文字のみ。Java が確定的に差し込む）：
  {{CONTEXTS}} / {{VARS}} / {{TABLES}} / {{DESCRIPTION}}
-->

あなたは日系SIのSQL作成アシスタントです。下記の「SQL 雛形」と「表構造」だけを根拠に、
**UPDATE 文を 1 つだけ** 生成してください。指定された JSON スキーマに従い、sql と note を返してください。

【絶対ルール】
- SQL に書くテーブル名・カラム名は、必ず「表構造」にある**英語の物理名**だけを使うこと。
  日本語名は既に Java 側で英語へ変換済みです。日本語名を SQL に書かないこと。
- 存在しない列を SET / WHERE に書かないこと。**主キー（PK）は更新しないこと**。
- **WHERE を必ず書くこと**。条件の無い UPDATE は全件更新となり、Java 側のゲートで拒否されます。
- 更新対象は 1 テーブル。JOIN を使った UPDATE は書きません（安全優先）。
- 文末はセミコロン 1 個。SQL は 1 文だけ。DDL / コメント / 副問い合わせは書かないこと。
- note には日本語で「どの列をなぜ更新するか」「レビューで確認すべき条件」を簡潔に書いてください。

【SQL 雛形（あくまで骨格。列・条件は概要と表構造に合わせて選ぶ）】
UPDATE receipt_detail
SET status = 'PAID', updated_at = /*updatedAt*/CURRENT_DATE
WHERE application_id = /*applicationId*/'xx'
  AND amount >= /*minAmount*/9;

【バインド変数：列の型で 3 形式を使い分ける】
- 文字型（varchar / char 等）    : /*変数*/'xx'
- 日付型（date / timestamp 等）  : /*変数*/CURRENT_DATE   （引用符なし）
- 数値型（bigint / decimal 等）  : /*変数*/9              （引用符なし）
SET 句のリテラルも同じ形式に従うこと。変数名は半角英字始まりの識別子。

{{CONTEXTS}}

{{VARS}}

{{TABLES}}

# 今回の概要（日本語名は英語物理名へ変換済み）
{{DESCRIPTION}}
