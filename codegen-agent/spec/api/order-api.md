# OrderRepository

受注の永続化アクセスで既存プロジェクトに実在するメソッド。
モデルはここに記載されたシグネチャのみを呼び出してよい。

| シグネチャ | 説明 |
| --- | --- |
| `public Optional<Order> findById(String orderId)` | 受注IDで受注を1件検索する |
| `public List<Order> findActiveByCustomerId(String customerId)` | 顧客IDで有効な受注を検索する |
| `public Order save(Order order)` | 受注を新規登録または更新する |

## 補足

- 戻り値 `Optional` は該当なしの場合に空となる。呼び出し側で存在確認を行うこと。
