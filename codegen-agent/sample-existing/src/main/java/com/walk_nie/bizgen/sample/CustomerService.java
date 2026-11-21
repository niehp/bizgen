package com.walk_nie.bizgen.sample;

import java.util.List;

/**
 * 顧客に関するアプリケーションサービス。
 * 受注検索では顧客名の取得をこのクラスへ委譲する。
 */
public class CustomerService {

    private final CustomerRepository customerRepository;

    /**
     * コンストラクタ。
     * 依存を明示的に受け取り、テスト時に差し替えられるようにする。
     *
     * @param customerRepository 顧客リポジトリ
     */
    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * 顧客IDから顧客名を取得する。
     * 受注一覧の表示で顧客名を併記するため、存在しない場合は空文字を返す。
     *
     * @param customerId 顧客ID
     * @return 顧客名
     */
    public String findCustomerName(String customerId) {
        return customerRepository.findNameById(customerId);
    }

    /**
     * 有効な顧客一覧を取得する。
     * 削除フラグが立っている顧客は検索対象外とする。
     *
     * @return 有効な顧客一覧
     */
    public List<Customer> findActiveCustomers() {
        return customerRepository.findAllActive();
    }
}
