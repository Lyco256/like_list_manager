# `LikeListDatabaseMigrationTest.kt`

## 対応ソース

`app/src/androidTest/java/com/lyco256/llm/data/LikeListDatabaseMigrationTest.kt`

## 役割

version 1相当のタグDBとversion 2相当の投稿DBを作成し、`MIGRATION_1_2` と `MIGRATION_2_3` が既存データを保持することをAndroidテスト環境で検証します。

## 実行

接続済み実機またはエミュレーターで `connectedDebugAndroidTest` を実行します。

version 2→3についても、既存投稿保持、4つのnullable列、未取得NULL状態、migration後の更新を検証します。蓄積データのある日常利用端末では実行しません。
