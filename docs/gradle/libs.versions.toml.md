# `gradle/libs.versions.toml`

## 対応ソース

`gradle/libs.versions.toml`

## 役割

プラグインとライブラリのバージョン、Maven座標、Gradle aliasを一元管理します。

## 現状の主要依存

Compose、Room、Coil、AndroidX Security Crypto、AppAuth、material icons extended、JUnit 4、AndroidX Testを管理しています。AppAuthはOAuth 2.0 + PKCE、テスト依存は階層ロジックとDB migrationの検証に使います。

## 関連ファイル

- `../../docs/build.gradle.kts.md`: plugin aliasを参照します。
- `../../docs/app/build.gradle.kts.md`: library aliasを参照します。
- `../../docs/app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md`: AppAuthの利用箇所です。

## 変更時の確認

更新時は互換性、非推奨API、APKビルド、lintを確認します。

## 統合テスト依存

AndroidX Test Rules、Room testing、Compose UI Test、MockWebServer、sqlite-jdbcのversionとaliasを管理します。

MacrobenchmarkとUI Automator、および `com.android.test` plugin aliasも管理します。

## 2026-07 OCR update

- Added the `mlkitTextJapanese` version entry and the `mlkit-text-japanese` library alias.
