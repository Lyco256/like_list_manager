# `LikeListDatabase.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/LikeListDatabase.kt`

## 役割

Room DatabaseにEntityを登録し、`ClipDao`、`TagDao`、`UndoDao` を公開します。現在のschema versionは9で、1→2から8→9までの非破壊migrationを登録しています。

## Migration

- `MIGRATION_1_2`: `tag_groups` を追加し、既存タグへnullableな `parentGroupId` を追加する
- version 1のタグID、名称、色、並び順、投稿タグ割り当てを一時テーブル経由で保持し、既存タグはルート直下へ配置する
- `clip_tags` は最終テーブル名 `tags` を参照する外部キーで再作成する
- `MIGRATION_4_5`: `api_usage_months` を追加し、`sync_state` の当月使用量を履歴へ1行バックフィルする
- `MIGRATION_7_8`: `clips.isDeleted` を廃止するため `clips` を再作成し、clip、asset、clip-tag relationとindex/Foreign Keyを保持する
- `MIGRATION_8_9`: ID=1の単一永続Undo slotを保持する `undo_slot` tableを追加する

## 関連ファイル

- `Entities.kt.md`: 登録されるEntityと合成モデルです。
- `Daos.kt.md`: 公開するDAOです。
- `AppContainer.kt.md`: `Room.databaseBuilder` で実体を生成します。
- `ClipRepository.kt.md`: DAOを利用します。

## 変更時の確認

Entity追加・列変更時はversionを更新し、既存実機データを保持するmigrationを追加します。破壊的migrationは明示的な判断なしに導入しません。

## Migration 2→3（2026-06-20）

- version 2から3へ更新します。
- `clips` にいいね数、取得日時、恒久失敗日時、失敗理由のnullable列を追加します。
- 既存投稿は全列NULLの未取得状態で保持します。

## Migration 3→4（2026-06-22）

- `sync_state` にliked posts同期の継続用 `likedPostsNextToken` nullable列を追加します。
- 既存状態はNULLのため、次回同期は先頭から開始します。

## Migration 4→5（2026-07-02）

- `api_usage_months` を追加し、当月の `usageMonth` と `monthlyFetchedCount` を1行として履歴へ移します。
- 既存の `sync_state` は維持し、月間取得数の表示と内部履歴を両立します。
## 2026-07 追加

- DB version を 5 から 6 に上げた。
- `tag_groups.colorId` と `tags.colorId` を migration 5->6 で追加した。
- 既存データは両方とも `standard` で埋める。

## 2026-07 OCR update

- Bumped the database to version 7 and added migration `6 -> 7` for the OCR columns on `clips`.

## Migration 7→8→9（2026-08）

- 7→8では既存の全clipを現行データとして保持し、`isDeleted` 列だけを除去します。assetとclip-tag relationは一時tableを介して戻します。
- 8→9では既存tableを変更せず `undo_slot` を追加します。
