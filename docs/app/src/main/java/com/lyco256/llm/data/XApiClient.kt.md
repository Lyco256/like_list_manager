# `XApiClient.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/XApiClient.kt`

## 役割

OAuth 2.0ユーザーaccess tokenをBearerとしてX APIを呼び、JSONをアプリ内モデルへ変換します。

## 主要API

- `getMyUser`: `GET /2/users/me` でログインユーザーを取得
- `fetchLikedPosts`: `GET /2/users/{id}/liked_tweets` をpagination対応で取得
- `revokeToken`: `POST /2/oauth2/revoke` でtokenを失効

## 主要モデル

- `XUser`: ログインユーザー
- `XPost`: 投稿本文、投稿者、作成時刻、media
- `XMedia`: photo/video/GIFのURLと寸法
- `XApiResult`: 投稿、next token、rate limit
- `XApiException`: HTTP statusとerror body

## 関連ファイル

- `XOAuthManager.kt.md`: APIへ渡すaccess tokenを取得します。
- `ClipRepository.kt.md`: API呼び出し、ページング、エラー変換、DB保存を行います。
- `Entities.kt.md`: X APIモデルから永続モデルへ変換されます。
- `ApiSettingsStore.kt.md`: tokenとX user IDの保存先です。

## 変更時の確認

fields/expansions変更時はJSON parser、Entity、UIを確認します。X API仕様変更とrate-limit headerも公式資料で再確認します。
