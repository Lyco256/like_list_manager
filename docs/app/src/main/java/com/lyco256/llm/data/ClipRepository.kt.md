# `ClipRepository.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/ClipRepository.kt`

## 役割

認証、X同期、Room保存、画像取得、保存先変更、月間/API制限、タグ、概要、ローカル削除をまとめる業務ロジック層です。

## 主な処理

- OAuth: 認証Intent生成、code交換、`/users/me`、session保存、token更新、logout/revoke
- 同期: 月間上限確認、liked postsのpagination、新規投稿だけ保存、rate limit保存
- media: photoは回線を問わずWebP lossy quality 85へ変換して保存、video/GIF thumbnailはWi-Fi時だけ元形式で保存
- `PostStorageManager` の現在DBへFlowと更新操作を接続し、保存先変更後は新しいDBへ自動で切り替える
- 保存先一覧、移動見積もり、移動実行をViewModelへ公開
- 初期化: DBが空の初回だけサンプルを投入し、既存同期状態は上書きしない
- タグ/概要: グループとタグの作成、同一親での重複名禁止、名称変更、移動、兄弟並び替え、空グループ削除、タグ削除、一括追加、投稿ごとの再割り当て、概要更新
- `moveNodeToParentAt`: タグまたはグループを、指定親の指定位置へ移動する。ルート直下の `parentGroupId = null` を正常な所属として扱い、同一親内の下方向移動では元要素除外後の挿入位置へ補正する
- `moveNodeToParentAtSlot`: タグリストのplaceholder方式から呼ぶ移動APIです。indexは「移動対象nodeを除外した移動先兄弟リスト上の挿入位置」として受け取り、同一親内の下方向移動でも追加補正しません。
- 階層制約: グループ自身／子孫への移動を禁止し、タグとグループの混在順を正規化
- `parentGroupIdForMove` / `orderNodesAfterMove` / `orderNodesAfterMoveAtSlot`: 移動元親の解決と移動後の兄弟順計算をJVM単体テスト可能な純粋関数として提供する。不正な負数indexは先頭へ丸めずエラーにします。
- エラー: 401、403、429、5xxをユーザー向け文言へ変換

## 関連ファイル

- `Daos.kt.md`: DB操作を提供します。
- `Entities.kt.md`: 保存モデルと同期状態です。
- `XApiClient.kt.md`: X API通信を実行します。
- `XOAuthManager.kt.md`: OAuth code交換とtoken更新を実行します。
- `ApiSettingsStore.kt.md`: Client IDとsessionを保存します。
- `PostStorageManager.kt.md`: DBと画像の保存場所を提供します。
- `../MainActivity.kt.md`: Repository操作のUI入口です。
- `../TagHierarchyUiV2.kt.md`: タグ階層UIの画面実装です。
- `AppContainer.kt.md`: 依存関係を注入します。

## 変更時の確認

このファイルは影響範囲が広いため、同期変更ではAPI、Entity、DAO、使用量表示、media保存、認証期限切れを一緒に確認します。画像保存を変える場合は、photoだけがWebP化され、video/GIF thumbnailを巻き込まないこと、`localPath` と `sizeBytes` が変換後ファイルを指すことを確認します。
