# `gradle/wrapper/gradle-wrapper.jar`

## 対応ソース

`gradle/wrapper/gradle-wrapper.jar`

## 役割

Gradle Wrapperのバイナリ実行本体です。`gradlew` と `gradlew.bat` から呼ばれ、指定されたGradle配布物を検証・取得・起動します。

## 関連ファイル

- `gradle-wrapper.properties.md`: 配布URLと保存設定です。
- `../../gradlew.md`, `../../gradlew.bat.md`: OS別の起動スクリプトです。

## 変更時の確認

バイナリを直接編集しません。公式Wrapper更新コマンドで生成し、改ざん防止のため出所と差分を確認します。
