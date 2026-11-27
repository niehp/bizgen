# ブロック: find-order-by-id

- kind: API_CALL
- description: 受注IDで受注を1件検索する
- keywords: 受注検索, 受注ID, findById, OrderRepository, リポジトリ呼び出し
- api: `public Optional<Order> findById(String orderId)`
- vars: receiver=orderRepository, orderId=orderId

```java
return {{receiver}}.findById({{orderId}});
```
