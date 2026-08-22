# `build.gradle.kts`

## 対応ソース

`build.gradle.kts`

## 役割

ルートプロジェクトで利用可能なAndroid、Kotlin、Compose、KSPプラグインを宣言します。実際の適用は `app/build.gradle.kts` が行います。

## 関連ファイル

- `docs/gradle/libs.versions.toml.md`: プラグインIDとバージョンの定義元です。
- `docs/app/build.gradle.kts.md`: 各プラグインをアプリモジュールへ適用します。
- `docs/settings.gradle.kts.md`: plugin repositoryとモジュールを定義します。

## 変更時の確認

プラグイン追加時はVersion Catalogにもaliasを追加し、Gradle Syncと `assembleDebug` を確認します。

`com.android.library` plugin aliasは公式PaddleOCR Android SDKをローカルlibrary moduleとしてビルドするため、ルートで`apply false`宣言します。
