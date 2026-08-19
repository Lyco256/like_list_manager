# `BenchmarkSnapshotImporter.kt`

`app/src/benchmark/java/com/lyco256/llm/BenchmarkSnapshotImporter.kt`

benchmark target専用の一時snapshot handoffを検証・展開します。Room DBと対象元画像の`localPath`をbenchmark用保存先へ書き換え、読み取り専用snapshotに含まれる`filesDir/media_grid_previews/v1`の永続JPEGをbenchmark targetの同じ相対位置へコピーします。Preferences・OAuth token・Client IDは扱いません。debug/release/integrationTestのAPKにはコンパイルされません。

永続JPEGはasset ID名のまま利用できるため、local path由来keyの変換や生成処理は行いません。import失敗時も永続JPEGを削除するcleanupは実行しません。

snapshotの使用可否は、`isDeleted` のないversion 9 schemaに合わせ、全clip数、対応media asset数、tag付きmedia clip数、local media asset数で検証します。検証JSONのkeyも `clips` / `mediaAssets` を使います。
