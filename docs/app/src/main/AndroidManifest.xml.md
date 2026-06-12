# `app/src/main/AndroidManifest.xml`

## 対応ソース

`app/src/main/AndroidManifest.xml`

## 役割

ネットワーク権限、Application、メインActivity、OAuth callback受信用Activityを宣言します。

## 現状の重要設定

- `INTERNET`: X APIと画像取得に必要です。
- `ACCESS_NETWORK_STATE`: 動画サムネイルをWi-Fi時だけ保存する判定に使います。
- callback: `likelistmanager://oauth/x/callback`
- callback受信先: AppAuthの `RedirectUriReceiverActivity`

## 関連ファイル

- `java/com/lyco256/llm/LikeListManagerApp.kt.md`: Applicationクラスです。
- `java/com/lyco256/llm/MainActivity.kt.md`: launcher Activityです。
- `java/com/lyco256/llm/data/XOAuthManager.kt.md`: callback URIと認証endpointを定義します。
- `res/values/styles.xml.md`: Application themeです。

## 変更時の確認

callback URIはX Developer Console、Manifest、`XOAuthManager`の3か所で完全一致させます。
