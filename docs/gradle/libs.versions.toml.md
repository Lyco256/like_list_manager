# `gradle/libs.versions.toml`

## 対応ソース

`gradle/libs.versions.toml`

WorkManager `2.10.1`をpersistent JPEG preview workerの実行とテストへ追加しています。

## 役割

プラグインとライブラリのバージョン、Maven座標、Gradle aliasを一元管理します。

## 現状の主要依存

Compose、Room、Bundled SQLite 2.7.0、Coil、AndroidX Security Crypto、AppAuth、material icons extended、JUnit 4、org.json、AndroidX Testを管理しています。Bundled SQLiteは正本Room DBから分離した派生検索DBの通常FTS5／trigram FTS5に使います。AppAuthはOAuth 2.0 + PKCE、テスト依存は階層ロジック、Undo payload codec、DB migrationの検証に使います。

## 関連ファイル

- `../../docs/build.gradle.kts.md`: plugin aliasを参照します。
- `../../docs/app/build.gradle.kts.md`: library aliasを参照します。
- `../../docs/app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md`: AppAuthの利用箇所です。

## 変更時の確認

更新時は互換性、非推奨API、APKビルド、lintを確認します。

## 統合テスト依存

AndroidX Test Rules、Room testing、Compose UI Test、MockWebServer、sqlite-jdbcのversionとaliasを管理します。

MacrobenchmarkとUI Automator、および `com.android.test` plugin aliasも管理します。

## 2026-08 OCR7 update

- Adds ONNX Runtime and coroutines aliases used by the local `:ppocr-sdk` module.
- Removes the ML Kit Japanese text recognition version and alias.
