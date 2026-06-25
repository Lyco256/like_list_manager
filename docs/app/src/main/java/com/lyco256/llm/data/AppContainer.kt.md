# `AppContainer.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/AppContainer.kt`

## 役割

投稿保存先マネージャー、暗号化設定ストア、OAuthマネージャー、Repositoryを組み立てる簡易DIコンテナです。

## 生成順

`PostStorageManager` → `ApiSettingsStore` / `XOAuthManager` → `ClipRepository` の順で生成します。Room Databaseは保存先マネージャーが現在の保存先に対して開閉します。

## 関連ファイル

- `../LikeListManagerApp.kt.md`: AppContainerの所有者です。
- `LikeListDatabase.kt.md`: Room Databaseを定義します。
- `PostStorageManager.kt.md`: Room DBと画像の保存先、移動、復旧を管理します。
- `ApiSettingsStore.kt.md`: Client IDとOAuthセッションを保存します。
- `XOAuthManager.kt.md`: AppAuth認証を担当します。
- `ClipRepository.kt.md`: 全依存を受け取る業務ロジック層です。

## 変更時の確認

Repositoryのconstructor変更や新しい共有サービス追加時は、このファイルとApplication初期化を同時に確認します。

## テスト分離

`BuildConfig.TEST_HARNESS` がtrueの専用variantでは、テスト専用DB/画像/Preferences名、`InMemorySettingsStore`、`DisabledOAuthGateway`、`DisabledXApiGateway`を注入します。本番variantは従来どおり暗号化設定、AppAuth、X API実装を使います。

テストvariantではsample mediaも無効化し、UI起動時のCoil外部画像通信を防ぎます。
