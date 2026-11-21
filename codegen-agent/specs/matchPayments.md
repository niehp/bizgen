# メソッド実装仕様書

## 対象
- パッケージ: com.example.biz
- クラス: ReceiptReconciliationService
- メソッド: matchPayments
- 戻り値: List<String>
- 引数: List<String> payments, List<String> receipts

## 処理概要
入金リスト（payments）と領収書リスト（receipts）を受け取り、
両方に存在する項目を一致項目として返すメソッドを実装する。
一致する項目が存在しない場合は空のリストを返す。
順序は payments の順序に従うこと。

## 使用可能API
- java.util.List
- java.util.ArrayList

## 受入基準
- 引数が null の場合は空リストを返すこと
- 一致項目が 0 件の場合は空リストを返すこと
- 戻り値の順序が payments の順序と一致すること
