# `run-safe-debug-check.ps1`

## 対応ソース

`scripts/run-safe-debug-check.ps1`

## 役割

ビルド、単体テスト、lint、任意の実機debug APK上書き再インストールを、データを消さない安全側の手順としてまとめたPowerShellスクリプトです。

## 主な処理

- リポジトリルートへ移動し、`git status --short --branch` と `git diff --check` を実行する
- Android Studio同梱JBRを `JAVA_HOME` / `PATH` に設定する
- `assembleDebug testDebugUnitTest lintDebug` を一括実行する
- `-InstallToDevice` 指定時だけ、ADB端末が1台であることと既存packageがインストール済みであることを確認する
- `adb install -r` だけを使ってdebug APKを上書きし、前後の `uid`、`appId`、`firstInstallTime` が維持されていることを確認する

## 関連ファイル

- `../../SAFE_DEBUG_ROUTINE.md`: 実行条件、実行例、やること/やらないことを説明します。
- `../../AGENTS.md`: 実機データ保護と検証方針の基準です。
- `../../app/build.gradle.kts.md`: Gradleタスクとdebug APK生成に関係します。

## 変更時の確認事項

実機操作を増やす場合は、`adb uninstall`、`pm clear`、connected Android Test、データ削除、再インストールを伴う可能性のある操作を入れないことを確認します。端末へ影響する処理は、既存package確認、単一端末確認、`adb install -r`、package identity維持確認の順序を崩さないでください。
