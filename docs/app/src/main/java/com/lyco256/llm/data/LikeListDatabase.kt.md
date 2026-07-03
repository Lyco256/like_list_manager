# `LikeListDatabase.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/LikeListDatabase.kt`

## 役割

Room DatabaseにEntityを登録し、`ClipDao` と `TagDao` を公開します。現在のschema versionは5です。

## Migration

- `MIGRATION_1_2`: `tag_groups` を追加し、既存タグへnullableな `parentGroupId` を追加する
- version 1のタグID、名称、色、並び順、投稿タグ割り当てを一時テーブル経由で保持し、既存タグはルート直下へ配置する
- `clip_tags` は最終テーブル名 `tags` を参照する外部キーで再作成する
- `MIGRATION_4_5`: `api_usage_months` を追加し、`sync_state` の当月使用量を履歴へ1行バックフィルする

## 関連ファイル

- `Entities.kt.md`: 登録される6つのEntityです。
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
