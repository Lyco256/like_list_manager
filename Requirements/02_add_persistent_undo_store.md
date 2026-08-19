# 02. 永続Undoスロット追加とDB v9移行

## 目的

プロセス終了後も直前のUndo情報を1件だけ保持できる、共通の永続保存領域をRoom DBへ追加する。

## 実装

- DBをversion 9へ上げ、8→9 migrationでUndo専用tableを追加する。
- Undo tableは対象投稿・タグ等への外部キーを持たせない。削除対象そのものを復元する情報を保持できる構造にする。
- 保存できるUndoは常に1件だけとし、固定primary keyの1スロットとして扱う。
- 最低限、以下を永続化できる列を持たせる。
  - action type
  - version付きpayload
  - ユーザーへ表示する完了メッセージ
  - 作成時刻
- payloadは後続作業で異なる操作を保存できるよう、opaqueなversioned JSON文字列として扱う。Room rowに巨大な画像バイナリを直接入れる設計にはしない。
- `UndoDao` を追加し、以下を提供する。
  - 現在slotの監視
  - 現在slotの同期取得
  - slotの置換
  - slotの削除
- `LikeListDatabase` からUndo DAOへアクセスできるようにする。
- `PostStorageManager` のRoom builderへ8→9 migrationを登録する。
- 投稿保存先移動ではDB自体がコピーされる既存構造を維持し、Undo rowも通常のDB内容として一緒に移るようにする。

## スキーマ上の制約

- Undo slotは対象EntityをCASCADEで失わない。
- 7→8→9の連続migrationで、01で復活させた旧 `isDeleted=true` 投稿も失わない。
- 新規DBにも同じ最新schemaが作られる。

## テスト

- 8→9 migration後に既存投稿・asset・tag・clip_tagが不変。
- Undo tableへ1件を保存・監視・取得・置換・削除できる。
- 置換後に古いrowが複数残らない。
- DB close/reopen後にもslotが残る。
- 7→8→9の連続migrationで投稿・関連データが保持される。
- 保存先コピー後のDBにもUndo slotが存在できることを、既存PostStorageManager testで安全に確認できる範囲で追加する。
