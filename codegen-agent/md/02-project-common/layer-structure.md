# プロジェクト構成規約（プロジェクト共通）

## レイヤ

| レイヤ | 責務 | クラス名接尾辞 |
| --- | --- | --- |
| presentation | 入力変換・画面/API 境界 | Controller / Form |
| application | ユースケース調整・トランザクション境界 | Service / UseCase |
| domain | 業務ルール・ドメインモデル | Entity / ValueObject |
| infrastructure | DB・外部連携 | Repository / Mapper |

## 依存方向

presentation → application → domain ← infrastructure

- domain は他レイヤに依存しない。
- 上位レイヤから下位レイヤへのみ依存する。逆方向の依存は例外を除き禁止。

## 既存プロジェクトへの二次開発

- 既存クラスを勝手に作り直さない。メソッド追加またはメソッド修正として扱う。
- 機能 md の `mode:` に `NEW_FILE` / `ADD_METHOD` / `MODIFY_METHOD` を明示する。
- 変更対象は `targetFile:` に既存プロジェクトからの相対パスで指定する。
- 生成結果は必ず人間がレビューしてから取り込む。
