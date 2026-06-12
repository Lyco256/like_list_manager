# `ClipRepository.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/ClipRepository.kt`

## 役割

認証、X同期、Room保存、画像取得、月間/API制限、タグ、概要、ローカル削除をまとめる業務ロジック層です。

## 主な処理

- OAuth: 認証Intent生成、code交換、`/users/me`、session保存、token更新、logout/revoke
- 同期: 月間上限確認、liked postsのpagination、新規投稿だけ保存、rate limit保存
- media: photoは回線を問わず保存、video/GIF thumbnailはWi-Fi時だけ保存
- 初期化: DBが空の初回だけサンプルを投入し、既存同期状態は上書きしない
- タグ/概要: 作成、名称変更、削除、一括追加、投稿ごとの再割り当て、概要更新
- エラー: 401、403、429、5xxをユーザー向け文言へ変換

## 関連ファイル

- `Daos.kt.md`: DB操作を提供します。
- `Entities.kt.md`: 保存モデルと同期状態です。
- `XApiClient.kt.md`: X API通信を実行します。
- `XOAuthManager.kt.md`: OAuth code交換とtoken更新を実行します。
- `ApiSettingsStore.kt.md`: Client IDとsessionを保存します。
- `../MainActivity.kt.md`: Repository操作のUI入口です。
- `AppContainer.kt.md`: 依存関係を注入します。

## 変更時の確認

このファイルは影響範囲が広いため、同期変更ではAPI、Entity、DAO、使用量表示、media保存、認証期限切れを一緒に確認します。
