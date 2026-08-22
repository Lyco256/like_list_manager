# `app/src/main/AndroidManifest.xml`

## 対応ソース

`app/src/main/AndroidManifest.xml`

## 役割

ネットワーク権限、Application、メインActivity、OAuth callback受信用Activityを宣言します。

## 現状の重要設定

- `INTERNET`: X APIと画像取得に必要です。
- `ACCESS_NETWORK_STATE`: 旧来のネットワーク状態ヘルパー用に宣言されています。現行のメディア保存経路はWi-Fi接続を条件にしません。
- callback: `likelistmanager://oauth/x/callback`
- callback受信先: AppAuthの `RedirectUriReceiverActivity`

## 関連ファイル

- `java/com/lyco256/llm/LikeListManagerApp.kt.md`: Applicationクラスです。
- `java/com/lyco256/llm/MainActivity.kt.md`: launcher Activityです。
- `java/com/lyco256/llm/data/XOAuthManager.kt.md`: callback URIと認証endpointを定義します。
- `res/values/styles.xml.md`: Application themeです。

## 変更時の確認

callback URIはX Developer Console、Manifest、`XOAuthManager`の3か所で完全一致させます。

OpenCV／ONNX Runtimeなどnative library変更時は、arm64 ELF alignmentと実機runtime smoke testも確認します。OpenCV 4.5.3の16KB対応はmanifest互換modeではなく、`ppocr-sdk/libs`の再build済みarm64 native libraryで行います。
