# `run-safe-debug-check.cmd`

## 対応ソース

`scripts/run-safe-debug-check.cmd`

## 役割

PowerShellの実行ポリシーが署名なし`.ps1`を拒否するPCでも、安全確認スクリプトを一度のコマンドで起動できるWindowsラッパーです。

引数はそのまま `run-safe-debug-check.ps1` へ渡すため、実機上書きは `run-safe-debug-check.cmd -InstallToDevice` を使います。

## 変更時の確認

`.ps1`の終了コードを保持し、ビルド・テスト・lintまたは実機確認の失敗を成功扱いにしないことを確認します。
