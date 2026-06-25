# `app/build.gradle.kts`

## 対応ソース

`app/build.gradle.kts`

## 役割

AndroidアプリモジュールのapplicationId、SDK、Java/Kotlin 21、Compose、依存ライブラリを定義します。

## 現状の主要機能との対応

- Compose: 全画面UI
- `androidx.compose.material:material-icons-extended`: タグ種別やナビゲーションのアイコン表示
- Room/KSP: 投稿、タグ、同期状態のDB
- Coil: 投稿画像表示
- Security Crypto: Client IDとOAuthセッションの暗号化保存
- AppAuth: OAuth 2.0 Authorization Code + PKCE
- JUnit 4: 階層・絞り込みロジックのローカル単体テスト
- unit test classpath補強: 日本語を含む作業パスでJUnitがテスト/本体クラスを読み込めない環境差を避けるため、`Test` タスク実行前に `debug` と `debugUnitTest` のKotlin/Java出力を一時ASCIIパスへコピーし、そのパスをclasspath先頭へ追加する
- AndroidX Test: SQLite migrationの実機テスト
- `AndroidJUnitRunner`: `connectedDebugAndroidTest` で実機テストを検出・実行

## 関連ファイル

- `../gradle/libs.versions.toml.md`: aliasとバージョンの定義元です。
- `src/main/AndroidManifest.xml.md`: アプリ構成と権限です。
- `src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md`: AppAuthの利用箇所です。

## 変更時の確認

依存追加やSDK変更後は `assembleDebug`、`testDebugUnitTest`、`lintDebug` を実行します。

## 統合テスト基盤

- `integrationTest` build typeは `com.lyco256.llm.test` とテスト専用保存名、Fake API/OAuthを使います。
- 実機テストはAndroidJUnitRunnerで `.test` packageを対象に連続実行します。SC-56CではOrchestratorが正常なテストプロセス終了をクラッシュと誤判定するため使用しません。
- `benchmark` build typeは軽量なdebug設定を継承しつつAPK自体は非debuggableな `com.lyco256.llm.test.benchmark` を生成し、専用DB・画像・Preferences、disabled OAuth/APIを使用します。R8縮小は性能測定の必須条件ではないため使いません。
- Compose UI Test、Room testing、MockWebServer、sqlite-jdbcでUI・DB・HTTP・snapshotを検証します。
- Instrumentation Testは `scripts/run-safe-integration-check.cmd` から、メインと `.test` を別package・別UIDで共存させる許可済み実機だけで実行します。
- `verifyTestEnvironmentIsolation` はdebug/testの生成BuildConfigとtest merged manifestを検査し、本番identity・保存名・API・OAuth receiverの混入を端末接続なしで失敗させます。
