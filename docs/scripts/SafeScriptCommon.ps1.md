# `SafeScriptCommon.ps1`

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

## 変更時の確認事項

成功時に詳細ログを読む必要がないこと、失敗時にログパスが必ず表示されること、timeout時に該当フェーズが分かることを確認します。
