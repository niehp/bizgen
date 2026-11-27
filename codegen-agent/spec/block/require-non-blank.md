# ブロック: require-non-blank

- kind: API_CALL
- description: 引数が空でないことを検証し、未設定なら例外を投げる
- keywords: 必須チェック, 引数検証, 入力検証, requireNonBlank, isBlank
- api: `public static void requireNonBlank(String value, String name)`
- vars: value=orderId, name="orderId"

```java
Validate.requireNonBlank({{value}}, {{name}});
```
