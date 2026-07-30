# `run-safe-integration-check.cmd`

Windows 用の薄いラッパーです。PowerShell の実行ポリシーを回避して `run-safe-integration-check.ps1` を起動し、受け取った引数をそのまま渡します。

USB接続とワイヤレスデバッグで同じ入口を使います。接続方式の指定は不要です。ADB serverのmDNS自動接続を無効化し、必要なwireless endpointはPowerShell側の安全resolverだけが明示接続します。
