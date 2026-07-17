# `run-safe-macrobenchmark-check.ps1`

通常実行は端末側の `media-grid-metrics`、instrumentation完了marker、benchmark targetデータを終了時に削除しない。ADBが途中で切断されても、端末上で開始済みのinstrumentationは継続し、結果をapp-specific外部領域へ保存する。

後から回収する場合は `.\scripts\run-safe-macrobenchmark-check.cmd -DebugMethod wireless -RecoverMetricsOnly` を使う。回収確認後に端末側成果を削除する場合だけ `.\scripts\run-safe-macrobenchmark-check.cmd -DebugMethod wireless -CleanupOnly` を使う。

未回収成果が端末にある場合、通常実行は `pm clear` 前に失敗する。通常実行の前に回収と明示cleanupを行う。

Wireless mode: `-DebugMethod wireless` resolves the mDNS `_adb-tls-connect._tcp` endpoint whose reported hardware serial matches `testDeviceSerial`, then uses that endpoint for the same package and UID safety checks. It does not uninstall the production app or clear device data.

同じ実機に本番 `com.lyco256.llm` を残したまま、隔離された性能測定対象 `com.lyco256.llm.test.benchmark` とMacrobenchmarkホスト `com.lyco256.llm.macrobenchmark.host` だけを扱う安全実行スクリプトです。

`test-device.local.properties` の許可済みserial、`allowCoLocatedProductionApp=true`、本番/benchmark/host package IDを検証します。古い設定ファイルにbenchmark/host packageがない場合は、固定値 `com.lyco256.llm.test.benchmark` と `com.lyco256.llm.macrobenchmark.host` を使います。実行前後で本番packageのpath、UID、version、初回インストール日時、更新日時を比較し、変化があれば失敗します。

主なフェーズは `Preflight`、`Build`、`InstallBenchmarkTarget`、`Macrobenchmark`、`PostCheck` です。`Build` では先に `:app:clean` と `:macrobenchmark:clean` でローカル成果物を削除し、その後 `:app:assembleBenchmark`、`:app:verifyTestEnvironmentIsolation`、`:macrobenchmark:assembleBenchmark`、`:macrobenchmark:assembleBenchmarkAndroidTest` を実行します。これによりbenchmark source setの古い増分成果物を混入させません。`aapt` でAPK package IDを確認してから導入します。Macrobenchmark本体は `:macrobenchmark:connectedBenchmarkAndroidTest` を許可済み端末に対して実行します。

本番アプリのアンインストール、データ消去、package ID変更は行いません。対象APKの導入は `adb install -r` のみを使います。
# 実装22

既存の`.cmd`入口からのみ、force-stop済み本命packageへ`run-as`で読み取り専用snapshotを作成する。run-asが利用できない場合はrootや権限回避へfallbackせず失敗する。コピー対象はRoom DB本体/WAL/SHM、既存`media_grid_thumbnails`、`files/images`から選んだ最大256件の元画像だけで、Preferences、DataStore、OAuth/API secret、Cookie、Client IDは対象外。

snapshotはbenchmark targetと同じpackageの一時debuggable setup APKを導入してbenchmark packageだけを`pm clear`し、`run-as`で`files/benchmark-handoff`を作成する。PC上のZIPはPowerShellの文字列パイプや外部ストレージを経由せず、`adb exec-in run-as com.lyco256.llm.test.benchmark dd of=files/benchmark-handoff/media-grid-snapshot.zip`の標準入力へFileStreamで直接転送する。端末側のサイズとSHA-256をPC側と照合し、不一致なら最終APKを導入しない。最終的な非debuggable benchmark APKへ`install -r`した後、setup Activityを一度だけ明示起動して内部handoffをimportし、marker、件数、Room DB path、data inodeを検証してからMacrobenchmarkを開始する。測定に使うAPKは`benchmark`だけで、handoff入力にapp-specific外部ストレージ、複数storage root探索、shellから見える外部パス、外部領域間の`run-as cp`を使わない。本命package metadataとDB/対象media hashは前後比較し、PC側snapshotは成功・失敗にかかわらずcleanupする。

`build/reports/media-grid-benchmark/latest-summary.md`には5モード、スクロール/pinchシナリオ、P50/P90/P95/P99/jankを出力する。通常アプリ側の高頻度処理には計測Trace/counterを埋め込まず、従来のTrace/counter欄は空またはN/Aとして扱い、未計測値を推測しない。
