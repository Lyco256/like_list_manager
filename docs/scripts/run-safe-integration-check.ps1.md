# `run-safe-integration-check.ps1`

Git管理外のserial許可リストと共存への明示同意を検証します。メイン `com.lyco256.llm` が既に入った同じ実機へ、`aapt` で確認した隔離APK `com.lyco256.llm.test` を導入してAndroidJUnitRunnerテストを実行します。メインpackageのpath、UID、version、導入・更新日時を前後比較し、テストpackageとのUID分離と両方のインストール状態を確認します。

標準出力は低出力化されており、成功時は `Preflight`、`Build`、`UnitTest`、`Lint`、`Install`、`IntegrationTest`、最後に `Success` だけを表示します。Gradle、ADB、aaptの詳細出力は `build/safe-script-logs/run-safe-integration-check/` 配下へ保存します。

USBが一時的に切れた場合は開始時、実機テスト直前、metadata事後確認前に最大90秒だけ許可serialの再接続を待ちます。別端末へ切り替えたり、待機後も未接続のまま続行したりはしません。

APK生成前後に `verifyTestEnvironmentIsolation` を実行し、BuildConfigとmerged manifestも検査します。

日本語を含む作業パスを `aapt` が誤解釈しないよう、検査時だけAPKをASCII名の一時ファイルへコピーします。端末へ渡す前にnative toolの終了code、badging、package IDをすべて検証します。

buildまたはtestが失敗した場合も、メインpackage metadataの前後比較を必ず実行してから失敗を返します。

失敗時は `Failed:`、`Error:`、`Log:` だけを標準出力へ表示します。標準出力の要約だけで原因が分からない場合に限り、表示されたログファイルを確認します。
