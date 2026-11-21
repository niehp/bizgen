# Javaクラス / 依存と互換

## 対象
ReceiptReconciliationService。

## 処理概要
- 依存は List のみに抑え、コンストラクタ injection は使わない（単純さ優先）。
- 既存の matchPayments と countMatched を壊さないこと。

## 受入基準
- 既存メソッドが残っていること。
- パッケージが com.example.biz であること。
