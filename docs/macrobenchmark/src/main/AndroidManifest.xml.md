# `macrobenchmark/src/main/AndroidManifest.xml`

Macrobenchmark Instrumentation Testを保持するためのローカルホストアプリmanifestです。

バックアップを無効にし、Activityや外部公開コンポーネントは持ちません。AndroidX Benchmark runnerが結果保存のために要求する `WRITE_EXTERNAL_STORAGE` と、Perfetto localhost HTTP接続に必要な `INTERNET` は、このホストアプリ側だけに宣言します。本番アプリ `com.lyco256.llm` や隔離benchmark対象 `com.lyco256.llm.test.benchmark` には影響しません。

Android 11以降のpackage visibility制限下でもMacrobenchmarkライブラリが測定対象を検出できるように、`<queries>` で `com.lyco256.llm.test.benchmark` だけを明示します。本番packageはquery対象にしません。

Perfetto trace processorが端末内localhost HTTPサーバーを使えるように、ホストアプリ専用のnetwork security configでlocalhost cleartextだけを許可します。

変更時は、ホストpackageが `com.lyco256.llm.macrobenchmark.host` のままであること、測定対象が本番packageになっていないこと、`scripts/run-safe-macrobenchmark-check.cmd` が本番metadata前後不変を確認できることを合わせて確認します。
