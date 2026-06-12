# `LikeListDatabase.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/LikeListDatabase.kt`

## 役割

Room DatabaseにEntityを登録し、`ClipDao` と `TagDao` を公開します。現在のschema versionは1です。

## 関連ファイル

- `Entities.kt.md`: 登録される5つのEntityです。
- `Daos.kt.md`: 公開するDAOです。
- `AppContainer.kt.md`: `Room.databaseBuilder` で実体を生成します。
- `ClipRepository.kt.md`: DAOを利用します。

## 変更時の確認

Entity追加・列変更時はversionを更新し、既存実機データを保持するmigrationを追加します。破壊的migrationは明示的な判断なしに導入しません。
