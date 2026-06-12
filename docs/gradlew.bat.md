# `gradlew.bat`

## 対応ソース

`gradlew.bat`

## 役割

Windows向けGradle Wrapper起動スクリプトです。この開発環境ではビルド、lint、テストに使用します。

## 関連ファイル

- `docs/gradlew.md`: macOS/Linux版です。
- `docs/gradle/wrapper/gradle-wrapper.jar.md`: Wrapper実行本体です。
- `docs/gradle/wrapper/gradle-wrapper.properties.md`: 使用するGradle版を定義します。

## 変更時の確認

通常は手編集しません。更新後は `gradlew.bat --version` と `assembleDebug` を確認します。
