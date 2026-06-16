# `LikeListDatabaseMigrationTest.kt`

## 対応ソース

`app/src/androidTest/java/com/lyco256/llm/data/LikeListDatabaseMigrationTest.kt`

## 役割

version 1相当のSQLiteテーブルへタグと投稿タグ関連を作成し、`MIGRATION_1_2` 後もID、名称、並び順、割り当てが保持され、既存タグがルート直下になることをAndroid実機で検証します。

## 実行

接続済み実機またはエミュレーターで `connectedDebugAndroidTest` を実行します。
