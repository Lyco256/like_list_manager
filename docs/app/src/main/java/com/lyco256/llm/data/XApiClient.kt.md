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

## いいね数取得（2026-06-20）

- liked posts同期は `tweet.fields=public_metrics` を要求し、`like_count` を返します。
- `fetchPostMetrics` は最大100 IDを `/2/tweets` へ渡し、取得できた指標と投稿単位エラーを分離して返します。

## テスト境界

- `XApiGateway` がRepository向け契約です。
- `XApiClient` はbase URLとtimeoutを注入でき、MockWebServerでJSON・HTTP異常系を検証します。
- base URLにdefaultはなく、呼び出し側が本番またはlocalhostを明示しなければ生成できません。
- `XApiException` は429時のlimit、remaining、resetを保持します。
- `DisabledXApiGateway` はテスト用アプリからのネットワーク要求を即時拒否します。

## 2026-07 media fields

- `fetchLikedPosts` requests `media.fields=media_key,type,url,preview_image_url,width,height`.
- `XMedia.previewImageUrl` is the only source used for `video` and `animated_gif` thumbnail downloads.
