# 対応ソース

`app/src/benchmark/AndroidManifest.xml`

## 役割

非debuggableな隔離benchmarkアプリを `com.lyco256.llm.test.benchmark` として測定可能にし、AppAuth callback receiverを除去します。通信は専用network security configでloopbackだけを許可します。

## 安全条件

- 本番packageとは別UIDです。
- shell profileableだけを有効にし、本番OAuth receiverは含めません。
