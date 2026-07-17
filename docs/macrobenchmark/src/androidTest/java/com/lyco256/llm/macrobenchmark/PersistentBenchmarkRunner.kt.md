# `PersistentBenchmarkRunner.kt`

## 対応ソース

`macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/PersistentBenchmarkRunner.kt`

## 役割

Macrobenchmarkの実行状態と完了結果を、端末のリムーバブルSDカード上の`media-grid-run`へ原子的に記録します。

- `onStart`で`running.json`を作り、前回の`completion.json`を削除する
- `finish`で終了コードと結果Bundleを`completion.json`へ保存し、実行中ファイルを削除する
- 出力先にリムーバブルSDカードがない場合は開始時に失敗する
- 一時ファイルからrenameするため、途中状態のJSONを公開しない
