# `run-safe-macrobenchmark-check.ps1`

Wireless mode: `-DebugMethod wireless` resolves the mDNS `_adb-tls-connect._tcp` endpoint whose reported hardware serial matches `testDeviceSerial`, then uses that endpoint for the same package and UID safety checks. It does not uninstall the production app or clear device data.

同じ実機に本番 `com.lyco256.llm` を残したまま、隔離された性能測定対象 `com.lyco256.llm.test.benchmark` とMacrobenchmarkホスト `com.lyco256.llm.macrobenchmark.host` だけを扱う安全実行スクリプトです。

`test-device.local.properties` の許可済みserial、`allowCoLocatedProductionApp=true`、本番/benchmark/host package IDを検証します。古い設定ファイルにbenchmark/host packageがない場合は、固定値 `com.lyco256.llm.test.benchmark` と `com.lyco256.llm.macrobenchmark.host` を使います。実行前後で本番packageのpath、UID、version、初回インストール日時、更新日時を比較し、変化があれば失敗します。

主なフェーズは `Preflight`、`Build`、`InstallBenchmarkTarget`、`Macrobenchmark`、`PostCheck` です。`Build` では `:app:assembleBenchmark`、`:app:verifyTestEnvironmentIsolation`、`:macrobenchmark:assembleBenchmark`、`:macrobenchmark:assembleBenchmarkAndroidTest` を実行し、`aapt` でAPK package IDを確認してから導入します。Macrobenchmark本体は `:macrobenchmark:connectedBenchmarkAndroidTest` を許可済み端末に対して実行します。

本番アプリのアンインストール、データ消去、package ID変更は行いません。対象APKの導入は `adb install -r` のみを使います。
# 実装22

既存の`.cmd`入口からのみ、force-stop済み本命packageへ`run-as`で読み取り専用snapshotを作成する。run-asが利用できない場合はrootや権限回避へfallbackせず失敗する。コピー対象はRoom DB本体/WAL/SHM、既存`media_grid_thumbnails`、`files/images`から選んだ最大256件の元画像だけで、Preferences、DataStore、OAuth/API secret、Cookie、Client IDは対象外。

snapshotは`com.lyco256.llm.test.benchmark`専用の外部handoffへpushし、benchmark target起動時にtarget内部へimportする。本命package metadataとDB/対象media hashは前後比較し、PC側・共有領域のsnapshotは成功・失敗にかかわらずcleanupする。

`build/reports/media-grid-benchmark/latest-summary.md`には5モード、スクロール/pinchシナリオ、P50/P90/P95/P99/jank、主要Trace/counter、指定差分を出力する。Metric exportが存在しない場合はN/Aと判定不能を明示し、未計測値を推測しない。
