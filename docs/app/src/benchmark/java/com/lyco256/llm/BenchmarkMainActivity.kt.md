# `BenchmarkMainActivity.kt`

`app/src/benchmark/java/com/lyco256/llm/BenchmarkMainActivity.kt`

benchmark variantだけで使用する起動Activityです。通常の `MainActivity` を継承し、分類済みメディアグリッドを初期表示して、benchmark側のframe timingと結果出力を管理します。通常Activityや本番AppContainerへ計測用引数を追加しません。

snapshot importは `BenchmarkSnapshotSetupActivity` が担当し、通常起動時には実行されません。
