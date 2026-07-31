# `run-safe-debug-check.ps1`

Gradle taskはすべて `:app:` へ明示的に限定し、macrobenchmarkなど別moduleの同名taskを通常ゲートへ巻き込みません。

## 対応ソース

`scripts/run-safe-debug-check.ps1`

## 役割

ビルド、単体テスト、lint、任意の実機debug APK上書き再インストールを、データを消さない安全側の手順としてまとめたPowerShellスクリプトです。

標準出力は低出力化されており、成功時は `Preflight`、`Build`、`UnitTest`、`Lint`、必要に応じて `Install`、最後に `Success` だけを表示します。Gradle、ADB、gitの詳細出力は `build/safe-script-logs/run-safe-debug-check/` 配下へ保存します。

## 主な処理

- リポジトリルートへ移動し、`git status --short --branch` と `git diff --check` を `Preflight` で実行する
- Android Studio同梱JBRを `JAVA_HOME` / `PATH` に設定する
- Buildは`assembleDebug`、隔離設定検証、integration target APK、androidTest APK生成をまとめ、`run-safe-integration-check`とtask・成功stateを共有する
- Build、UnitTest、Lintはphase別の入力fingerprintを持つ。unit testだけの変更はBuildとLintを無効化せず、androidTestだけの変更はUnitTestとLintを無効化しない
- Buildが必要な場合も`clean`せずGradle incremental buildを使う。省略時はdebug・integration・androidTest APKすべてのSHA-256一致を必須とする
- UnitTestとLintの成功stateも`run-safe-integration-check`と共有する
- main入力が不変で、変更されたunit testファイルを1ファイル1test classとして安全に抽出できる場合だけ、変更classへUnitTestを限定する。それ以外はUnitTest全体へ戻す
- `-InstallToDevice` 指定時だけ、接続中ADB entryを`ro.serialno`で物理端末単位にまとめ、物理端末が1台だけであることと既存packageがインストール済みであることを確認する
- 同じ物理端末のUSB entryとwireless entryが同時に存在する場合はUSBを優先する。異なる物理端末が混在する場合は自動選択せず停止する
- 同じ物理端末の重複wireless entryは選択した1件を残して`adb disconnect`し、後続実行へ持ち越さない
- `.cmd`入口はADB serverのmDNS自動接続を無効化し、wireless接続が必要な場合だけ安全resolverが許可端末の候補1件へ明示接続する
- 選択serialを全ADB操作へ明示し、`adb install -r` だけを使ってdebug APKを上書きし、前後の `uid`、`appId`、`firstInstallTime` が維持されていることを確認する
- フェーズごとの失敗を識別し、失敗時は `Failed:`、`Error:`、`Log:` だけを標準出力へ表示する
- 通常debug入口のnative command timeoutは無効で、経過時間だけを理由にbuild・unit test・lint・installを強制終了しない

## 関連ファイル

- `../../SAFE_DEBUG_ROUTINE.md`: 実行条件、実行例、やること/やらないことを説明します。
- `../../AGENTS.md`: 実機データ保護と検証方針の基準です。
- `../../app/build.gradle.kts.md`: Gradleタスクとdebug APK生成に関係します。

## 変更時の確認事項

実機操作を増やす場合は、`adb uninstall`、`pm clear`、connected Android Test、データ削除、再インストールを伴う可能性のある操作を入れないことを確認します。端末へ影響する処理は、単一物理端末確認、既存package確認、serial明示の`adb install -r`、package identity維持確認の順序を崩さないでください。

低出力化を変更する場合は、成功時に詳細ログを読む必要がないこと、失敗時に失敗フェーズ・最初に見るべきエラー・ログパスが出ることを確認します。

自動省略は`build/safe-script-state/`の前回成功stateを使います。部分範囲を利用者が指定するオプションはなく、入力変更時は影響するphaseを自動実行し、state削除後は全phaseを実行します。

`-FullRebuildTest`オプションが存在します。Codexはユーザーからその実行を明示指示された場合だけ使用します。
