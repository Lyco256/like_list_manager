# `app/build.gradle.kts`

## 対応ソース

`app/build.gradle.kts`

## 役割

AndroidアプリモジュールのapplicationId、SDK、Java/Kotlin 21、Compose、依存ライブラリを定義します。

## 現状の主要機能との対応

- Compose: 全画面UI
- Room/KSP: 投稿、タグ、同期状態のDB
- Coil: 投稿画像表示
- Security Crypto: Client IDとOAuthセッションの暗号化保存
- AppAuth: OAuth 2.0 Authorization Code + PKCE
- JUnit 4: 階層・絞り込みロジックのローカル単体テスト
- AndroidX Test: SQLite migrationの実機テスト
- `AndroidJUnitRunner`: `connectedDebugAndroidTest` で実機テストを検出・実行

## 関連ファイル

- `../gradle/libs.versions.toml.md`: aliasとバージョンの定義元です。
- `src/main/AndroidManifest.xml.md`: アプリ構成と権限です。
- `src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md`: AppAuthの利用箇所です。

## 変更時の確認

依存追加やSDK変更後は `assembleDebug`、`testDebugUnitTest`、`lintDebug` を実行します。
