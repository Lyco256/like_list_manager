# `run-safe-integration-check.cmd`

Windows 用の薄いラッパーです。PowerShell の実行ポリシーを回避して `run-safe-integration-check.ps1` を起動し、受け取った引数をそのまま渡します。

たとえば `-DebugMethod wireless` を指定して、そのまま wireless 実機の統合テストを実行できます。
