# `run-safe-debug-check.cmd`

## 対応ソース

`scripts/run-safe-debug-check.cmd`

## 役割

PowerShellの実行ポリシーが署名なし`.ps1`を拒否するPCでも、安全確認スクリプトを一度のコマンドで起動できるWindowsラッパーです。

引数はそのまま `run-safe-debug-check.ps1` へ渡すため、実機上書きは `run-safe-debug-check.cmd -InstallToDevice` を使います。

ADB serverのmDNS自動接続を無効化し、必要なwireless endpointはPowerShell側の安全resolverだけが明示接続します。

標準出力仕様、ログ保存、timeout、失敗時要約は `.ps1` 側が持ちます。この入口から実行しても成功時はフェーズ名と `Success` だけが表示されます。

## 変更時の確認

`.ps1`の終了コードを保持し、ビルド・テスト・lintまたは実機確認の失敗を成功扱いにしないことを確認します。
