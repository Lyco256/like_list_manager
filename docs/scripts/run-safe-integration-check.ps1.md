# `run-safe-integration-check.ps1`

本番 `com.lyco256.llm` と許可済みの同居実機だけを使って、build、unit test、lint、APK導入、実機統合テストをまとめて実行する安全スクリプトです。`test-device.local.properties` の許可設定を確認し、`aapt` で APK の package を検証してから `adb install -r` を使います。

USB接続とワイヤレスデバッグで同じ入口を使います。`testDeviceSerial`を端末のhardware serialとして接続済み端末を先に照合し、USB接続があればそれを優先します。同じ端末のwireless endpointが複数接続済みなら1件を残して余分なendpointを`adb disconnect`します。接続済みendpointがなければ、mDNSの`_adb-tls-connect._tcp`候補から重複suffixのない1件を優先して1回だけ接続し、`getprop ro.serialno`で一致を確認します。接続方式にかかわらず、`app-integrationTest.apk`と`app-integrationTest-androidTest.apk`を同じ実機へ上書き導入し、許可端末を明示した`adb shell am instrument`で統合テストを実行します。

成功時の表示は `Preflight`、`Build`、`UnitTest`、`Lint`、`Install`、`IntegrationTest`、最後に `Success` です。Gradle、ADB、aapt の詳細ログは `build/safe-script-logs/run-safe-integration-check/` に保存されます。

Buildは`assembleDebug`、隔離設定検証、integration target APK、androidTest APK生成をまとめ、`run-safe-debug-check`とtask・成功stateを共有します。Build、UnitTest、Lintはphase別の入力fingerprintを使い、無関係なtest source setの変更では他phaseを無効化しません。Buildが必要な場合も通常は`clean`せずGradle incremental buildを使います。UnitTestとLintもdebug入口と成功stateを共有し、main入力が不変で変更unit test classを安全に抽出できる場合だけ変更classへ限定します。それ以外はUnitTest全体へ戻ります。Build省略にはdebug APK、integrationTest APK、androidTest APKのSHA-256一致が必要です。Install、IntegrationTest、実機前後チェックは毎回実行します。

`-FullRebuildTest`オプションが存在します。Codexはユーザーからその実行を明示指示された場合だけ使用します。

前後チェックでは、実機上の本番 package metadata が変わっていないこと、そして本番 package とテスト package の Android UID が別々であることを確認します。
