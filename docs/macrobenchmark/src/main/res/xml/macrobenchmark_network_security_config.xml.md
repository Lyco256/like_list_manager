# `macrobenchmark/src/main/res/xml/macrobenchmark_network_security_config.xml`

Macrobenchmarkホストアプリ専用のnetwork security configです。

Perfetto trace processorが端末内localhost HTTPサーバーへcleartextで接続できるよう、`localhost` と `127.0.0.1` だけを許可します。本番アプリや隔離benchmark対象アプリの通信許可には影響しません。
