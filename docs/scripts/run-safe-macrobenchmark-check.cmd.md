# `run-safe-macrobenchmark-check.cmd`

PowerShell実行ポリシーの影響を避けるため、`scripts/run-safe-macrobenchmark-check.ps1` を `ExecutionPolicy Bypass` 付きで起動するWindows用ラッパーです。

通常はこの `.cmd` から起動し、引数はそのまま `.ps1` へ渡します。

USB接続とワイヤレスデバッグで同じ入口を使い、接続方式の指定は不要です。

ADB serverのmDNS自動接続を無効化し、必要なwireless endpointはPowerShell側の安全resolverだけが明示接続します。
