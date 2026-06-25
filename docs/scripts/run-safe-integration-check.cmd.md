# `run-safe-integration-check.cmd`

PowerShell実行ポリシーの影響を避けて、同名 `.ps1` を起動するWindows用入口です。

引数と終了コードは `.ps1` へそのまま渡します。標準出力仕様、ログ保存、timeout、失敗時要約は `.ps1` 側が持ち、成功時はフェーズ名と `Success` だけが表示されます。
