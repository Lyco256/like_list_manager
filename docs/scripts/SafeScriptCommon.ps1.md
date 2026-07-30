# `SafeScriptCommon.ps1`

Instrumentation commands are treated as failed when their output contains a JUnit failure or process-crash marker, even if `adb shell am instrument` exits with code 0. Android's normal successful result can use `INSTRUMENTATION_CODE: -1`, so that code is not itself treated as failure.

## 対応ソース

`scripts/SafeScriptCommon.ps1`

## 役割

安全検証スクリプトの低出力実行を共通化するPowerShell helperです。

## 主な処理

- `build/safe-script-logs/<script-name>/` にtimestampログと `latest.log` を保存する
- フェーズ開始時だけ標準出力へフェーズ名を表示する
- 外部コマンドの標準出力・標準エラーをログファイルへ保存し、通常の標準出力へ流さない
- フェーズごとのtimeout、終了コード確認、失敗時要約抽出を行う
- 失敗時は `Failed:`、`Error:`、必要に応じて `Impact:`、`Log:` を表示する
- 指定されたソース・Gradle設定・安全スクリプトを内容hashでfingerprint化する
- 前回成功時のfingerprintと成果物SHA-256を`build/safe-script-state/`へ保存し、完全一致時だけ検証フェーズを省略できるようにする
- state欠損、破損、入力変更、成果物欠損・hash不一致はcache missとして安全側へ倒す
- phase別の入力fingerprintとunit test source snapshotを保持し、main入力が不変で変更test classを一意に抽出できる場合だけ、そのclassへ`testDebugUnitTest`を限定する
- 1回の安全スクリプト実行中は同じlength・更新時刻のファイルSHA-256を再利用し、phase別fingerprint間の重複hash計算を避ける

## 変更時の確認事項

成功時に詳細ログを読む必要がないこと、失敗時にログパスが必ず表示されること、timeout時に該当フェーズが分かることを確認します。validation cacheを変更する場合は、成功したフェーズだけが記録され、入力または成果物が変われば必要なphaseが再実行されることを確認します。main source・設定変更、test削除、class抽出不能、9ファイル以上のtest変更はUnitTest全体へ戻します。
