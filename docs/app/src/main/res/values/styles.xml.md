# `app/src/main/res/values/styles.xml`

## 対応ソース

`app/src/main/res/values/styles.xml`

## 役割

Android Activityのベーステーマ `Theme.LikeListManager` を定義し、標準ActionBarを無効にします。実際の配色はCompose側です。

## 関連ファイル

- `../../../AndroidManifest.xml.md`: Application themeとして参照します。
- `../../java/com/lyco256/llm/MainActivity.kt.md`: Composeのダークカラースキームを定義します。

## 変更時の確認

起動時のちらつき、ステータスバー、Composeテーマとの色差を実機で確認します。
