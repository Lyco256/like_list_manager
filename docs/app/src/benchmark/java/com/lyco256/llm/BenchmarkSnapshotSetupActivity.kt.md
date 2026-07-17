# `BenchmarkSnapshotSetupActivity.kt`

## 対応ソース

`app/src/benchmark/java/com/lyco256/llm/BenchmarkSnapshotSetupActivity.kt`

## 役割

Macrobenchmark対象アプリの起動前に、benchmark専用のsnapshotを`BenchmarkSnapshotImporter`へ準備させる明示的なsetup入口です。

- 成功時は古い`setup-error.txt`を削除して`RESULT_OK`を返す
- 失敗時はbenchmark専用外部ファイル領域へスタックトレースを書き、`RESULT_CANCELED`を返す
- `MainActivity`や本番DBを開かず、処理後すぐに終了する
