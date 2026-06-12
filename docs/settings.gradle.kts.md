# `settings.gradle.kts`

## 対応ソース

`settings.gradle.kts`

## 役割

プロジェクト名、プラグイン取得先、依存ライブラリ取得先、`:app` モジュールを定義します。

## 関連ファイル

- `docs/build.gradle.kts.md`: ルートのプラグイン宣言です。
- `docs/app/build.gradle.kts.md`: `:app` モジュールの設定です。
- `docs/gradle/libs.versions.toml.md`: 取得する依存関係の座標とバージョンです。

## 変更時の確認

repositoryやモジュールを変更した場合は、依存解決と全モジュールのビルドを確認します。
