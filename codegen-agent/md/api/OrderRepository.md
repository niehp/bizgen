# OrderRepository

受注の永続化アクセスを行うリポジトリ。

| シグネチャ | 説明 |
| --- | --- |
| `public Optional<Order> findById(String orderId)` | 受注IDで受注を1件検索する |
| `public List<Order> findActiveByCustomerId(String customerId)` | 顧客IDと削除フラグで有効な受注一覧を検索する |
