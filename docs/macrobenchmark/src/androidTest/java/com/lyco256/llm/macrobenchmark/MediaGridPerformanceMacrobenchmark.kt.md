# `MediaGridPerformanceMacrobenchmark.kt`

## 対応ソース

`macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/MediaGridPerformanceMacrobenchmark.kt`

## 役割

benchmark専用variantに対して、5回反復のスクロールと実ポインター入力によるメディアグリッド列変更シナリオを`FrameTimingMetric`で測定します。

- `FRAME_ONLY`、`PRIORITY_ONLY`、`CACHED_UI`、`ENCODER_ONLY`、`FULL`の5 modeを実行する
- 通常シナリオはfast round trip、slow drag、settle after scrollを使う
- 二指シナリオは4→5→4、8→9→8、往復を含む
- 実行前にbenchmark専用snapshotのmarkerと必要件数を検査する
- `BenchmarkMainActivity`へmode、scenario、生成リセット、metric flushをIntent extraで渡す

このテストはbenchmark source setにのみ属し、通常のdebug/release/integrationTestの実機テストではありません。
