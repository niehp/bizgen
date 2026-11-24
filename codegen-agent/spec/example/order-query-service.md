# 機能インスタンス: 受注検索サービス

既に完成・レビュー済みの機能例。命名・分层・Javadoc の粒度を揃えるための参考。
機能を持ち込んでよいのは「書き方」だけであり、処理内容を仕様外に拡張してはならない。

## 要件

- 受注IDを受け取り、必須チェックのうえ受注を1件検索して返す。
- 該当なしの場合は空の Optional を返す。

## 想定コード

```java
package com.example.order;

import java.util.Optional;

/**
 * 受注検索サービス。
 *
 * <p>受注IDを検証して受注を検索し、呼び出し側へ返すために存在する。</p>
 */
public class OrderQueryService {

    private final OrderRepository repository;

    /**
     * 受注IDで受注を検索する。
     *
     * <p>不正な入力で無駄な検索をしないよう、先に必須チェックを行う。</p>
     */
    public Optional<Order> find(String orderId) {
        if (StringUtils.isBlank(orderId)) {
            return Optional.empty();
        }
        return repository.findById(orderId);
    }
}
```
