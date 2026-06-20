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

## 実行条件

- Windows PowerShellから、リポジトリルートまたは任意のカレントディレクトリで実行できます。
- Android Studio同梱JBRが `C:\Program Files\Android\Android Studio\jbr` にある前提です。違う場合は `-JavaHome` で指定します。
- `-InstallToDevice` を使う場合は、ADBで認識される端末が1台だけ接続されている必要があります。
- `-InstallToDevice` は、対象package `com.lyco256.llm` がすでに実機へ入っている場合だけ実行できます。未インストール端末への新規インストールは拒否します。
- 実機の蓄積データがある端末では、事前に手動バックアップ方針を確認してください。このスクリプトはDBや画像バックアップを自動作成しません。

## やってくれること

- `git status --short --branch` で現在のブランチと作業ツリーを表示します。
- `git diff --check` で空白エラーを確認します。
- `JAVA_HOME` と `PATH` をAndroid Studio同梱JBRへ合わせます。
- `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug --console=plain --no-daemon` を実行します。
- `-InstallToDevice` 指定時だけ、ADB端末が1台であること、既存packageがあること、debug APKが存在することを確認します。
- `adb install -r` で上書き再インストールします。
- 再インストール前後で `uid`、`appId`、`firstInstallTime` が変わっていないことを確認します。

## やらないこと

- `adb uninstall` は実行しません。
- `adb shell pm clear` は実行しません。
- `connectedDebugAndroidTest` や `connectedAndroidTest` は実行しません。
- アプリデータ、DB、画像、SharedPreferencesを削除しません。
- 実機データのバックアップやDB変換は行いません。
- アプリ起動やlogcat確認は行いません。必要な場合は別途、実機確認手順として実行します。

## 失敗したとき

- Gradleが失敗した場合は、出力された最初のコンパイルエラー、テスト失敗、lintエラーを修正して再実行します。
- `-InstallToDevice` でADB端末が0台または複数台の場合は、接続状態を整理してから再実行します。
- 再インストール後に `uid`、`appId`、`firstInstallTime` が変わった場合は、以降の操作を止めて実機データ状態を確認します。

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
