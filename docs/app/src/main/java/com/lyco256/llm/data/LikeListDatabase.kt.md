# `LikeListDatabase.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/LikeListDatabase.kt`

## 役割

Room DatabaseにEntityを登録し、`ClipDao` と `TagDao` を公開します。現在のschema versionは2です。

## Migration

- `MIGRATION_1_2`: `tag_groups` を追加し、既存タグへnullableな `parentGroupId` を追加する
- version 1のタグID、名称、色、並び順、投稿タグ割り当てを一時テーブル経由で保持し、既存タグはルート直下へ配置する
- `clip_tags` は最終テーブル名 `tags` を参照する外部キーで再作成する

## 関連ファイル

- `Entities.kt.md`: 登録される5つのEntityです。
- `Daos.kt.md`: 公開するDAOです。
- `AppContainer.kt.md`: `Room.databaseBuilder` で実体を生成します。
- `ClipRepository.kt.md`: DAOを利用します。

## 変更時の確認

Entity追加・列変更時はversionを更新し、既存実機データを保持するmigrationを追加します。破壊的migrationは明示的な判断なしに導入しません。
