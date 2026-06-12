# `gradle.properties`

## 対応ソース

`gradle.properties`

## 役割

AndroidX、Rクラス生成、compileSdk警告、Kotlinコードスタイル、Gradle JVMメモリを設定します。

## 現状

- Gradle JVM最大ヒープ: 2GB
- 文字コード: UTF-8
- Android SDKパス検査の上書き設定あり

## 関連ファイル

- `docs/app/build.gradle.kts.md`: compileSdkやJava/Kotlin toolchainを定義します。
- `docs/gradle/wrapper/gradle-wrapper.properties.md`: Gradle本体のバージョンを定義します。

## 変更時の確認

低スペックPCへの影響が大きいため、メモリ設定変更後は単一ビルドで安定性を確認します。
