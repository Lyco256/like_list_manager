# `gradle/libs.versions.toml`

## 対応ソース

`gradle/libs.versions.toml`

WorkManager `2.10.1`をpersistent JPEG preview workerの実行とテストへ追加しています。

## 役割

プラグインとライブラリのバージョン、Maven座標、Gradle aliasを一元管理します。

## 現状の主要依存

Compose、Room、Coil、AndroidX Security Crypto、AppAuth、material icons extended、JUnit 4、org.json、AndroidX Testを管理しています。AppAuthはOAuth 2.0 + PKCE、テスト依存は階層ロジック、Undo payload codec、DB migrationの検証に使います。

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

## 2026-08 PaddleOCR SDK dependencies

ONNX Runtime `1.21.1`, Kotlin coroutines `1.9.0`, AndroidX Test Monitor, and the Android library plugin alias support the official local `:ppocr-sdk` module. OpenCV remains `4.5.3` but is referenced as the local 16KB-arm64 rebuild documented under `ppocr-sdk/libs`, rather than as a version-catalog Maven alias.
