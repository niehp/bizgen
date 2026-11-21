# メソッド実装仕様書

## 対象
- パッケージ: com.example.biz
- クラス: ReceiptReconciliationService
- メソッド: countMatched
- 戻り値: int
- 引数: List<String> payments, List<String> receipts
- モード: ADD_METHOD
- 対象ファイル: specs/existing/ReceiptReconciliationService.java

## 処理概要
既存の消込サービスに、一致項目の件数を返すメソッドを追加する。
一致とは、両方のリストに同じ文字列が存在することを指す。
一致項目が無い場合は 0 を返す。

## 使用可能API
- java.util.List

## 受入基準
- 引数が null の場合は 0 を返すこと
- 一致する項目数を正しく返すこと
