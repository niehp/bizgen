package com.walk_nie.bizgen.sample;

import java.util.List;
import java.util.Optional;

/**
 * 受注の永続化アクセスを担当するリポジトリ。
 * 検索条件の組み立てを一箇所に集め、サービス層の重複を防ぐ。
 */
public interface OrderRepository {

    /**
     * 受注IDから受注を1件取得する。
     * 主キー検索であるため、結果が無い場合も例外とせず Optional で返す。
     *
     * @param orderId 受注ID
     * @return 該当する受注
     */
    Optional<Order> findByOrderId(String orderId);

    /**
     * 顧客IDに一致する有効な受注一覧を取得する。
     * 論理削除済みの受注は画面に表示しないため、削除フラグで除外する。
     *
     * @param customerId 顧客ID
     * @return 有効な受注一覧
     */
    List<Order> findActiveOrders(String customerId);

    /**
     * 受注を新規登録する。
     * 生成された受注IDを呼び出し側へ返し、後続の処理で利用できるようにする。
     *
     * @param order 登録する受注
     * @return 生成された受注ID
     */
    String insert(Order order);
}
