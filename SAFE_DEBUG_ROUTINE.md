# Safe Debug Routine

この文書は、ビルド、単体テスト、lint、実機への安全なdebug APK上書き再インストールを、毎回同じ手順で行うための運用メモです。

## 使うスクリプト

```powershell
.\scripts\run-safe-debug-check.cmd
```

実機へ上書き再インストールまで行う場合:

```powershell
.\scripts\run-safe-debug-check.cmd -InstallToDevice
```

これらの安全スクリプトは低出力で動作します。成功時の標準出力はフェーズ名と最後の `Success` だけです。

通常検証の成功例:

```text
Preflight
Build
UnitTest
Lint
Success
```

実機上書きありの成功例:

```text
Preflight
Build
UnitTest
Lint
Install
Success
```

## 実行条件

- Windows PowerShellから、リポジトリルートまたは任意のカレントディレクトリで実行できます。
- Android Studio同梱JBRが `C:\Program Files\Android\Android Studio\jbr` にある前提です。違う場合は `-JavaHome` で指定します。
- `-InstallToDevice` を使う場合は、ADBで認識される端末が1台だけ接続されている必要があります。
- `-InstallToDevice` は、対象package `com.lyco256.llm` がすでに実機へ入っている場合だけ実行できます。未インストール端末への新規インストールは拒否します。
- 実機の蓄積データがある端末では、事前に手動バックアップ方針を確認してください。このスクリプトはDBや画像バックアップを自動作成しません。

## やってくれること

- `git status --short --branch` で現在のブランチと作業ツリーをログへ記録します。
- `git diff --check` で空白エラーを確認します。
- `JAVA_HOME` と `PATH` をAndroid Studio同梱JBRへ合わせます。
- `.\gradlew.bat :app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug` をフェーズごとに実行します。
- `-InstallToDevice` 指定時だけ、ADB端末が1台であること、既存packageがあること、debug APKが存在することを確認します。
- `adb install -r` で上書き再インストールします。
- 再インストール前後で `uid`、`appId`、`firstInstallTime` が変わっていないことを確認します。
- Gradle、ADB、git、aaptの詳細出力は `build/safe-script-logs/` 配下のログファイルへ保存します。
- フェーズごとに長めのtimeoutを持ち、timeout時は失敗フェーズとログパスを表示します。

## やらないこと

- `adb uninstall` は実行しません。
- `adb shell pm clear` は実行しません。
- `connectedDebugAndroidTest` や `connectedAndroidTest` は実行しません。
- アプリデータ、DB、画像、SharedPreferencesを削除しません。
- 実機データのバックアップやDB変換は行いません。
- アプリ起動やlogcat確認は行いません。必要な場合は別途、実機確認手順として実行します。

## 失敗したとき

- 失敗時の標準出力は、失敗フェーズ、最初に確認すべきエラー情報、詳細ログファイルのパスを表示します。
- Gradleが失敗した場合は、まず標準出力の `Error:` を確認し、それだけで原因が分からない場合に限り `Log:` のファイルを確認します。
- `-InstallToDevice` でADB端末が0台または複数台の場合は、接続状態を整理してから再実行します。
- 再インストール後に `uid`、`appId`、`firstInstallTime` が変わった場合は、以降の操作を止めて実機データ状態を確認します。

失敗時の出力例:

```text
Preflight
Build
Failed: Build
Error: <最初に確認すべきエラー情報>
Log: <詳細ログファイルのパス>
```

timeout時の出力例:

```text
Preflight
Build
Failed: Build
Error: Build timeout
Log: <詳細ログファイルのパス>
```

Codexはスクリプト実行中に高頻度で進捗確認せず、十分長いtimeoutで起動します。成功時は詳細ログを読みません。失敗時だけ、標準出力の失敗フェーズ、エラー要約、ログパスを確認し、標準出力だけで原因が分からない場合に詳細ログを読みます。

## よく使う例

ローカル確認だけ:

```powershell
.\scripts\run-safe-debug-check.cmd
```

ビルド、テスト、lint、SC-56Cなど接続中の1台へ安全な上書き再インストール:

```powershell
.\scripts\run-safe-debug-check.cmd -InstallToDevice
```

APKやpackageを明示する場合:

```powershell
.\scripts\run-safe-debug-check.cmd -InstallToDevice -PackageName com.lyco256.llm -ApkPath app\build\outputs\apk\debug\app-debug.apk
```

`.cmd` はPowerShellの署名ポリシーに左右されない入口です。内部で `-ExecutionPolicy Bypass` を今回のプロセスだけに指定し、既存の `.ps1` を実行します。

## 隔離統合テスト

Instrumentation/Compose/Room統合テストは、メインアプリと隔離テストアプリを同じ実機へ共存させて実行します。

```powershell
Copy-Item .\test-device.local.properties.example .\test-device.local.properties
# ローカルファイルへ実機serialと共存package設定を記録
.\scripts\run-safe-integration-check.cmd
```

成功時の標準出力例:

```text
Preflight
Build
UnitTest
Lint
Install
IntegrationTest
Success
```

この入口は次を満たさない限りインストール前に停止します。

- `test-device.local.properties` の `testDeviceSerial` と接続端末が完全一致する
- `allowCoLocatedProductionApp=true` が明示されている
- メイン `com.lyco256.llm` が既に端末へインストールされている
- テストAPKが `com.lyco256.llm.test` であり、メインとは別package・別UIDである
- 通常のbuild/unit test/lintゲートが成功する

対象アプリは `com.lyco256.llm.test` です。AndroidJUnitRunnerはこの隔離packageだけを対象にし、メインpackageのpath、UID、version、初回導入日時、更新日時が前後不変であることを確認します。テスト後も隔離テストアプリを端末へ残し、両アプリを共存させます。SC-56CではOrchestratorが正常なテスト終了をクラッシュと誤判定するため使用しません。

## DB・画像スナップショット互換テスト

明示指定したバックアップを端末へ送らず、ホスト上の一時コピーで検証します。

```powershell
.\scripts\run-safe-snapshot-check.cmd -SnapshotRoot <DBを含むフォルダ> -ImageRoot <画像バックアップ>
```

元DB・画像は読み取り元としてhashを取得するだけで、SQLiteで開くのは一時コピーです。OAuth設定やtokenは対象に含めません。

成功時の標準出力例:

```text
Preflight
SnapshotTest
Success
```

失敗時は通常の `Failed:`、`Error:`、`Log:` に加えて、元DB hash、画像件数、画像byte数の前後確認に基づく `Impact:` を表示します。詳細なhash、件数、Gradle出力はログファイルへ保存します。
