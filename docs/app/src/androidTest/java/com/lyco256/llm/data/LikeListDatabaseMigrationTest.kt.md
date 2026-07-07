# `LikeListDatabaseMigrationTest.kt`

## 対応ソース

`app/src/androidTest/java/com/lyco256/llm/data/LikeListDatabaseMigrationTest.kt`

## 役割

version 1相当のタグDB、version 2相当の投稿DB、version 3相当の同期状態DBを作成し、`MIGRATION_1_2`、`MIGRATION_2_3`、`MIGRATION_3_4` が既存データを保持することをAndroidテスト環境で検証します。

## 実行

接続済み実機またはエミュレーターで `connectedDebugAndroidTest` を実行します。

version 2→3についても、既存投稿保持、4つのnullable列、未取得NULL状態、migration後の更新を検証します。蓄積データのある日常利用端末では実行しません。

version 3→4では既存使用量の保持、継続token列のNULL初期値、migration後のtoken更新を検証します。
## 2026-07-03 追加確認

- version 4->5では `api_usage_months` の作成、既存 `sync_state.usageMonth` と `monthlyFetchedCount` のバックフィル、既存 `sync_state` の保持を検証します。

## 2026-07 OCR update

- Added migration coverage for version `6 -> 7`, including the default OCR columns on `clips`.
