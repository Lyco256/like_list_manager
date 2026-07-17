# `BenchmarkSnapshotImporter.kt`

`app/src/benchmark/java/com/lyco256/llm/BenchmarkSnapshotImporter.kt`

benchmark target専用の一時snapshot handoffを検証・展開します。Room DB、対象画像、Thumbnail cacheのlocalPathをbenchmark用保存先へ移し、Preferences・OAuth token・Client IDは扱いません。debug/release/integrationTestのAPKにはコンパイルされません。
