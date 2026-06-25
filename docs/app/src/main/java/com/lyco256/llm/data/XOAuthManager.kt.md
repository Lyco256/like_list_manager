# `XOAuthManager.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt`

## 役割

AppAuthを使い、XのOAuth 2.0 Authorization Code Flow with PKCEを実行します。

## 現状の設定

- Authorization endpoint: `https://x.com/i/oauth2/authorize`
- Token endpoint: `https://api.x.com/2/oauth2/token`
- Callback: `likelistmanager://oauth/x/callback`
- Scopes: `tweet.read users.read like.read offline.access`
- Client authentication: `NoClientAuthentication`。Client Secretは使用しません。

## 主要処理

- `createAuthorizationIntent`: PKCE/state付き認証Intentを生成
- `exchangeAuthorizationResult`: callbackのcodeをtokenへ交換
- `refresh`: refresh tokenでaccess tokenを更新
- `OAuthTokens`: Repositoryへ渡すtoken応答モデル

## 関連ファイル

- `../../../../AndroidManifest.xml.md`: callback receiverを宣言します。
- `ApiSettingsStore.kt.md`: tokenとClient IDを保存します。
- `ClipRepository.kt.md`: 認証開始、完了、自動更新を統括します。
- `../MainActivity.kt.md`: 認証Intentを起動し結果を受け取ります。
- `../../../../../../../build.gradle.kts.md`: AppAuth依存を追加します。

## 変更時の確認

endpoint、scope、callback変更時はX Developer Console、Manifest、設定画面、実機callbackをまとめて確認します。

## テスト境界

`OAuthGateway` を介してRepositoryへ注入します。統合テスト用アプリは `DisabledOAuthGateway` を使い、認証画面、code交換、token refreshを本番endpointへ送信できません。Repositoryテストでは記録可能なFakeを使います。
