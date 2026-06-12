# `gradle/wrapper/gradle-wrapper.properties`

## 対応ソース

`gradle/wrapper/gradle-wrapper.properties`

## 役割

Gradle Wrapperが取得するGradle配布物、保存先、タイムアウト、URL検証を定義します。現在はGradle 8.11.1です。

## 関連ファイル

- `gradle-wrapper.jar.md`: この設定を読み取るWrapper本体です。
- `../../gradlew.md`, `../../gradlew.bat.md`: Wrapperの起動入口です。
- `../../app/build.gradle.kts.md`: Android Gradle Pluginとの互換性が必要です。

## 変更時の確認

Gradle更新時はAndroid Gradle PluginとKotlinの互換表を確認し、クリーンな環境でも取得・ビルドできることを確認します。
