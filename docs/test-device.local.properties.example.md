# `test-device.local.properties.example`

Macrobenchmark用に `benchmarkPackage=com.lyco256.llm.test.benchmark` と `macrobenchmarkHostPackage=com.lyco256.llm.macrobenchmark.host` も記録します。本番packageとは別UIDで共存させ、本番metadataを前後比較する安全スクリプトからのみ使います。

メインアプリと隔離テストアプリを同じ実機へ共存させるための、serial、明示同意、両package IDのローカル設定例です。実値を入れた `test-device.local.properties` はGit管理しません。
