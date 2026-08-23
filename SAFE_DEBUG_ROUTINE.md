この文書は、Codexが検証、ビルド、テスト、lint、実機上書き、実機統合テストを安全に実行するための手順だけを扱う。

## Codex実行ルール

Codexは、検証・ビルド・テスト・lint・実機操作を自己判断で直接実行しない。必ずこの文書に書かれた `.cmd` 入口だけを使う。ただし、ADB接続を確立・復旧・確認するための非破壊操作だけは、下記の条件付き例外として直接実行できる。

禁止:

* `.\gradlew.bat ...` を直接実行する
* 検証・インストール・テスト実行・アプリ状態変更を目的とした `adb ...` を直接実行する
* `aapt ...` を直接実行する
* `.ps1` を直接実行する
* `.cmd` や `.ps1` の中身を読んで、同等のGradle/adb/PowerShell処理を手動で再現する
* Android StudioやGradleのconnected系タスクを自己判断で実行する
* 成功時に詳細ログ、HTMLレポート、JUnit XML、lintレポートを読みに行く

### ADB接続復旧の例外

次の操作だけは、wireless/USB ADBの接続確立・復旧・状態確認に限って直接実行してよい。

* `adb devices -l`、`adb get-state`、`adb get-serialno`、接続確認用の`adb shell getprop ro.serialno`
* `adb mdns services`
* `adb connect <mDNS名またはhost:port>`、`adb disconnect <endpoint>`、`adb reconnect`

この例外では、`adb install`、`adb uninstall`、`adb shell pm clear`、`adb shell am instrument`、アプリ起動・停止、ファイル転送、設定変更、権限変更、DB・画像・Preferencesへ触れる操作は禁止する。接続復旧後のbuild、install、test、lintは必ず対応する`.cmd`入口へ戻す。統合テストとMacrobenchmarkの安全入口は、許可hardware serialに一致するUSB接続を優先し、USB接続がなければwireless ADB endpointを自動解決する。

専用入口がない検証が必要な場合は、直接コマンドを実行せず、必要な入口を追加するか、未実行として報告する。

## 許可する入口

通常のローカル確認:

```powershell
.\scripts\run-safe-debug-check.cmd
```

実機へ安全に上書きする場合:

```powershell
.\scripts\run-safe-debug-check.cmd -InstallToDevice
```

隔離統合テスト:

```powershell
.\scripts\run-safe-integration-check.cmd
```

USB接続とワイヤレスデバッグで入口は共通です。`testDeviceSerial`に登録したhardware serialと接続端末を照合し、USB接続がなければmDNSのADB TLS endpointを自動解決する。

Macrobenchmark:

```powershell
.\scripts\run-safe-macrobenchmark-check.cmd
```

USB接続とワイヤレスデバッグで入口は共通です。USB接続を優先し、USB接続がなければmDNSのADB TLS endpointを`testDeviceSerial`のhardware serialへ照合して接続する。

Snapshot互換テスト:

```powershell
.\scripts\run-safe-snapshot-check.cmd -SnapshotRoot <DBを含むフォルダ> -ImageRoot <画像バックアップ>
```

## 待機とログ確認

長時間コマンドは外側timeoutを無効にして一度だけ実行し、終了まで待つ。実行APIが有限値を必須とする場合は、通常運用で到達しない24時間以上を設定する。10分以下の外側timeoutでintegrationを起動しない。短いtimeoutで何度も状態確認しない。

通常debug入口とintegration入口はnative commandを時間で打ち切らない。長いGradle／instrumentation工程では30秒ごとに同じ行へ`.`を追加して進行中であることを通知し、工程終了時に改行する。テスト失敗、commandの非ゼロ終了、ADB接続エラーなど、実際の終了結果で成否を決める。呼び出し側が固定のwall-clock上限で切る場合は入口側から延長できないため、外側timeoutを十分長くする。

禁止:

* 実行中の逐次ログ監視
* `tail -f`
* 短い間隔のポーリング
* timeout前のログ確認
* 成功時の `build/safe-script-logs/` 確認

見るもの:

* 成功時: 標準出力のフェーズ名と `Success` だけ
* 失敗時: 標準出力の `Failed:`、`Error:`、`Log:` だけ
* 標準出力だけで原因が分からない失敗時: 該当ログの必要範囲だけ
* 明示的timeoutを持つ他入口のtimeout時: 該当ログの末尾だけ

## 通常検証

ローカル確認だけ行う場合:

```powershell
.\scripts\run-safe-debug-check.cmd
```

成功時の標準出力は、フェーズ名と `Success` だけを確認する。

通常確認と隔離統合確認は、debug APK、隔離設定検証、integration target APK、androidTest APKを生成する同じBuild task集合と成功stateを共有する。Build、UnitTest、Lintはphase別の入力fingerprintを使い、必要なphaseだけを実行する。Buildは通常`clean`せずGradle incremental buildを使う。main入力が不変で変更unit test classを安全に抽出できる場合だけUnitTestをそのclassへ限定し、判断不能時は全UnitTestへ戻る。Build成果物は前回成功時のSHA-256と一致する必要がある。利用者が部分範囲を指定するオプションは設けない。

`-FullRebuildTest`オプションは存在するが、Codexはユーザーから実行を明示指示された場合だけ使用する。Codex自身の判断では使用しない。

```text
Preflight
Build
UnitTest
Lint
Success
```

## 実機上書き

実機へdebug APKを上書きする場合:

```powershell
.\scripts\run-safe-debug-check.cmd -InstallToDevice
```

この入口は、既存packageへの `adb install -r` だけを許可する。`adb uninstall`、`adb shell pm clear`、新規インストール、package変更、署名変更、applicationId変更は行わない。

各`.cmd`入口はADB serverのmDNS自動接続を無効化する。USBとwirelessのADB entryが同時に見えても、`ro.serialno`が同じ1台の物理端末だけを示す場合はUSB entryを優先して自動選択する。同じ物理端末の余分なwireless entryは`adb disconnect`して1件へ整理する。wireless接続が必要な場合だけ安全resolverがmDNS候補1件へ明示接続する。異なる物理端末が混在する場合や端末identityを確認できない場合は停止する。選択したserialはpackage確認、install、前後確認の全ADB操作へ明示する。

成功時の標準出力は、フェーズ名と `Success` だけを確認する。

```text
Preflight
Build
UnitTest
Lint
Install
Success
```

## 隔離統合テスト

Instrumentation、Compose、Room統合テストは、メインアプリと隔離テストアプリを同じ実機へ共存させて実行する。

```powershell
.\scripts\run-safe-integration-check.cmd
```

この入口だけを使う。`connectedDebugAndroidTest`、`connectedAndroidTest`、`connectedIntegrationTestAndroidTest` を直接実行しない。接続方式にかかわらず、検証済みのtarget APKとandroidTest APKを上書き導入し、許可端末を明示してInstrumentationを実行する。端末解決は既存接続を先に再利用し、同じhardware serialの重複wireless endpointを切断する。接続済みendpointがない場合だけmDNS候補を1件選んで接続する。

Build、UnitTest、Lintには通常検証と同じ共有state・部分実行を適用する。Install、IntegrationTest、本番package metadataとUIDの前後確認は毎回実行する。

この入口は、次を満たさない限り停止する。

* `test-device.local.properties` の `testDeviceSerial` と接続端末が完全一致する
* `allowCoLocatedProductionApp=true` が明示されている
* メイン `com.lyco256.llm` が既に端末へインストールされている
* テストAPKが `com.lyco256.llm.test` であり、メインとは別package・別UIDである
* 通常のbuild、unit test、lintゲートが成功する

長いbuild・unit test・lint中に端末が消灯してもCompose Activityを起動できるよう、隔離実機テストの直前に対象端末をwakeし、keyguard解除を要求してAwake状態を確認する。端末データやpackageは変更しない。

テスト後も隔離テストアプリを端末へ残し、両アプリを共存させる。

## Macrobenchmark

Macrobenchmarkは次の入口だけを使う。

```powershell
.\scripts\run-safe-macrobenchmark-check.cmd
```

Gradleのmacrobenchmarkタスクを直接実行しない。メインpackageのmetadata前後不変を安全スクリプト側で確認する。

## Snapshot互換テスト

DB・画像snapshotは、明示指定したバックアップをホスト上の一時コピーで検証する。

```powershell
.\scripts\run-safe-snapshot-check.cmd -SnapshotRoot <DBを含むフォルダ> -ImageRoot <画像バックアップ>
```

この入口だけを使う。元DB・画像を直接開いたり、端末へ送ったりしない。OAuth設定やtokenは対象に含めない。

## 失敗時

失敗時は、まず標準出力の `Failed:`、`Error:`、`Log:` だけを見る。

```text
Failed: <phase>
Error: <最初に確認すべきエラー情報>
Log: <詳細ログファイルのパス>
```

標準出力だけで原因が分からない場合に限り、該当ログの必要範囲だけ読む。関係ないフェーズのログ、成功したフェーズのログ、HTMLレポート、JUnit XML、lintレポートを広く読まない。

## 冗長化防止

* 同じコマンドを複数箇所に繰り返し書かない
* 成功出力例は最小限にする
* スクリプト内部のGradle/adbコマンド列を書きすぎない
* AGENTS.mdと同じ禁止事項を長く重複させない
* 詳細なテスト証跡は `TEST_REQUIREMENTS_COVERAGE.md` に置く
* 実Xアカウントでの確認手順は `REAL_API_VERIFICATION.md` に置く
