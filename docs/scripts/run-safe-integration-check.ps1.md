# `run-safe-integration-check.ps1`

本番 `com.lyco256.llm` と許可済みの同居実機だけを使って、build、unit test、lint、APK導入、実機統合テストをまとめて実行する安全スクリプトです。`test-device.local.properties` の許可設定を確認し、`aapt` で APK の package を検証してから `adb install -r` を使います。

`-DebugMethod usb` では既存の connected instrumentation テストを実行し、`-DebugMethod wireless` では `adb shell am instrument -w -r com.lyco256.llm.test.test/androidx.test.runner.AndroidJUnitRunner` を直接呼びます。wirelessでは、`testDeviceSerial`を端末のhardware serialとしてmDNSの`_adb-tls-connect._tcp`サービスを自動検出・接続し、`getprop ro.serialno`で一致を確認したendpointを以後のADB操作に使用します。wireless では `app-integrationTest.apk` に加えて `app-integrationTest-androidTest.apk` も同じ実機へ上書き導入します。

成功時の表示は `Preflight`、`Build`、`UnitTest`、`Lint`、`Install`、`IntegrationTest`、最後に `Success` です。Gradle、ADB、aapt の詳細ログは `build/safe-script-logs/run-safe-integration-check/` に保存されます。

前後チェックでは、実機上の本番 package metadata が変わっていないこと、そして本番 package とテスト package の Android UID が別々であることを確認します。
