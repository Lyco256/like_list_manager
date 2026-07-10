この文書は、Codexが検証、ビルド、テスト、lint、実機上書き、実機統合テストを安全に実行するための手順だけを扱う。

## Codex実行ルール

Codexは、検証・ビルド・テスト・lint・実機操作を自己判断で直接実行しない。必ずこの文書に書かれた `.cmd` 入口だけを使う。

禁止:

* `.\gradlew.bat ...` を直接実行する
* `adb ...` を直接実行する
* `aapt ...` を直接実行する
* `.ps1` を直接実行する
* `.cmd` や `.ps1` の中身を読んで、同等のGradle/adb/PowerShell処理を手動で再現する
* Android StudioやGradleのconnected系タスクを自己判断で実行する
* 成功時に詳細ログ、HTMLレポート、JUnit XML、lintレポートを読みに行く

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

Macrobenchmark:

```powershell
.\scripts\run-safe-macrobenchmark-check.cmd
```

Snapshot互換テスト:

```powershell
.\scripts\run-safe-snapshot-check.cmd -SnapshotRoot <DBを含むフォルダ> -ImageRoot <画像バックアップ>
```

## 待機とログ確認

長時間コマンドは十分長いtimeoutで一度だけ実行する。短いtimeoutで何度も状態確認しない。

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
* timeout時: 該当ログの末尾だけ

## 通常検証

ローカル確認だけ行う場合:

```powershell
.\scripts\run-safe-debug-check.cmd
```

成功時の標準出力は、フェーズ名と `Success` だけを確認する。

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

この入口だけを使う。`connectedDebugAndroidTest`、`connectedAndroidTest`、`connectedIntegrationTestAndroidTest` を直接実行しない。

この入口は、次を満たさない限り停止する。

* `test-device.local.properties` の `testDeviceSerial` と接続端末が完全一致する
* `allowCoLocatedProductionApp=true` が明示されている
* メイン `com.lyco256.llm` が既に端末へインストールされている
* テストAPKが `com.lyco256.llm.test` であり、メインとは別package・別UIDである
* 通常のbuild、unit test、lintゲートが成功する

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
