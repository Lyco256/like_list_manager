# `TestEnvironmentIsolationTest.kt`

統合テストvariantのapplicationId、DB/画像/Preferences名、API URL、Settings/OAuth/API実装が本番と分離されていることを検証します。同じ実機上のメインpackageが別UIDであることと、Disabled APIが必ず例外になることも確認します。
