# 対応ソース

`app/src/benchmark/AndroidManifest.xml`

## 役割

非debuggableな隔離benchmarkアプリを `com.lyco256.llm.test.benchmark` として測定可能にし、通常の `MainActivity` をbenchmark専用 `BenchmarkMainActivity` へ置き換えます。snapshot setup ActivityとAppAuth callback receiver除去もこのvariantだけに含まれます。通信は専用network security configでloopbackだけを許可します。

## 安全条件

- 本番packageとは別UIDです。
- shell profileableだけを有効にし、本番OAuth receiverは含めません。
- `BenchmarkMainActivity`、snapshot importer、benchmark metricsは `app/src/benchmark` だけに存在し、debug/release/integrationTest APKには含まれません。
