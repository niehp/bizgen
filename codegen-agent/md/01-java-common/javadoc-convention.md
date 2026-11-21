# Java コメント規約（Java共通）

## 必須コメント

生成する Java には、以下を必ず付与する。

1. `package-info.java` にパッケージ Javadoc（`/** ... */` + `package` 宣言）
2. クラス直前にクラス Javadoc
3. public / protected メソッド直前にメソッド Javadoc
4. フィールド直前にフィールド Javadoc

## 日本語コメントの書き方

各行は次の 2 要素で構成する。簡潔に、1 文ずつ。

- `@param`, `@return`, `@throws` には「何を」を書く。
- 本文には「なぜそうするか」を 1 文で書く。

例:

```java
/**
 * 受注を新規登録する。
 * 入力検証を生成側で先に行い、不正な値が永続化層へ渡らないようにする。
 *
 * @param command 受注登録コマンド
 * @return 生成された受注ID
 * @throws IllegalArgumentException 必須項目が未設定の場合
 */
```

## 禁止事項

- 「ここに処理を書く」などの曖昧な記述。
- モデルが推測した仕様を事実として書くこと。不明な場合は md の記述のみを根拠にする。
