# `ClipRepository.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/ClipRepository.kt`

## 役割

認証、X同期、Room保存、画像取得、保存先変更、月間/API制限、タグ、概要、ローカル削除をまとめる業務ロジック層です。

## 主な処理

- OAuth: 認証Intent生成、code交換、`/users/me`、session保存、token更新、logout/revoke
- OAuth: 認証Intent生成、code交換、`/users/me`、session保存、token更新、logout/revoke。logoutはrevoke失敗を呼び出し側へ返し、ローカルのsessionは必ず削除する
- 同期: 月間上限確認、liked postsのpagination、新規投稿だけ保存、rate limit保存
- media: photoは回線を問わずWebP lossy quality 85へ変換して保存、video/GIF thumbnailはWi-Fi時だけ元形式で保存
- 設定画面: Client ID、ログイン状態、月間/API使用量、保存件数、画像枚数、ツイートデータ容量のスナップショットを公開
- `PostStorageManager` の現在DBへFlowと更新操作を接続し、保存先変更後は新しいDBへ自動で切り替える
- 保存先一覧、移動見積もり、移動実行をViewModelへ公開
- 設定画面向けに月間/API使用量、保存件数、画像枚数、ツイートデータ容量のスナップショットを取得する
- 初期化: DBが空の初回だけサンプルを投入し、既存同期状態は上書きしない
- タグ/概要: グループとタグの作成、同一親での重複名禁止、名称変更、移動、兄弟並び替え、空グループ削除、タグ削除、一括追加、投稿ごとの再割り当て、概要更新
- `moveNodeToParentAt`: タグまたはグループを、指定親の指定位置へ移動する。ルート直下の `parentGroupId = null` を正常な所属として扱い、同一親内の下方向移動では元要素除外後の挿入位置へ補正する
- `moveNodeToParentAtSlot`: タグリストのplaceholder方式から呼ぶ移動APIです。indexは「移動対象nodeを除外した移動先兄弟リスト上の挿入位置」として受け取り、同一親内の下方向移動でも追加補正しません。
- 階層制約: グループ自身／子孫への移動を禁止し、タグとグループの混在順を正規化
- `parentGroupIdForMove` / `orderNodesAfterMove` / `orderNodesAfterMoveAtSlot`: 移動元親の解決と移動後の兄弟順計算をJVM単体テスト可能な純粋関数として提供する。不正な負数indexは先頭へ丸めずエラーにします。
- エラー: 401、403、429、5xxをユーザー向け文言へ変換
- refresh通信自体が失敗した場合は旧sessionを消去せず、次回同期で再試行可能にする。refresh後のtokenでも401になった場合だけsessionを無効化する
- page取得に失敗する直前のpagination tokenを同期状態へcheckpointし、次回同期を失敗pageから再開できるようにする
- 月別API使用量履歴: `api_usage_months` に月単位の累積を保存し、累計使用量を履歴の合計から算出する

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

## いいね数再取得（2026-06-20）

- 通常同期では新規投稿だけにいいね数と取得日時を保存し、既存投稿は更新しません。
- 未取得投稿と、投稿後7日以内に取得され現在7日以上経過した暫定値を候補にします。ローカル削除・恒久失敗済み投稿は除外します。
- 月間残り枠まで最大100件ずつ取得し、要求ID数を使用量へ加算します。完了バッチは即時保存し、一時エラー時の未処理投稿は次回候補に残します。
- 通常同期は既存DBがあれば最初に5件だけ取得し、取得済み投稿IDに達したページでpaginationを停止します。新規が5件を超える場合だけ以降を100件単位で取得します。
- 月間枠・rate limit・通信中断などで取得済み地点より前に止まる場合は、最後に成功したページの `next_token` をページごとに保存します。次回同期はその続きから再開し、続きの完了後は先頭も再確認します。

## テスト可能な依存関係

- `SettingsStore`、`OAuthGateway`、`XApiGateway`をconstructorから受け取り、Fakeで同期を一気通貫検証できます。
- `XApiGateway`にdefault実装はなく、本番containerまたはテストが明示注入します。
- 401ではrefresh tokenを1回だけ試して同じAPI要求を再実行し、再度401ならsessionを破棄します。
- 429ではheaderからrate limit状態をDBへ保存してから同期を停止します。
- 空ページへ不正なnext tokenが付いていてもtokenを破棄して終了し、無限loopを防ぎます。
- `includeSeedMedia=false` のテストvariantでは外部画像URLを持つsample assetを作らず、UI起動だけでネットワーク通信しません。
