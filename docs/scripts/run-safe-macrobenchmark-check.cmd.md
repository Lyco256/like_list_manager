# `run-safe-macrobenchmark-check.cmd`

PowerShell実行ポリシーの影響を避けるため、`scripts/run-safe-macrobenchmark-check.ps1` を `ExecutionPolicy Bypass` 付きで起動するWindows用ラッパーです。

通常はこの `.cmd` から起動し、引数はそのまま `.ps1` へ渡します。
