# メソッド実装仕様書

## 対象
- パッケージ: com.example.biz
- クラス: ReceiptReconciliationService
- メソッド: matchPayments
- 戻り値: List<String>
- 引数: List<String> payments, List<String> receipts
- モード: MODIFY_METHOD
- 対象ファイル: specs/existing/ReceiptReconciliationService.java

## 処理概要
既存の matchPayments を差し替え、入金リストと領収書リストの一致項目を返す実装にする。
現在は空リストを返すだけなので、実際に突き合わせを行う処理へ変更する。
順序は payments に従うこと。

## 使用可能API
- java.util.List
- java.util.ArrayList

## 受入基準
- 引数が null の場合は空リストを返すこと
- 一致項目が 0 件の場合は空リストを返すこと
- 戻り値の順序が payments の順序と一致すること
