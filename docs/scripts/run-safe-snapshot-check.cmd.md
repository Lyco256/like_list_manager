# `run-safe-snapshot-check.cmd`

DB・画像スナップショット互換テストを実行するPowerShell wrapperのWindows用入口です。

引数と終了コードは `.ps1` へそのまま渡します。標準出力仕様、ログ保存、timeout、失敗時の元データ影響要約は `.ps1` 側が持ちます。
