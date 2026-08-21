# `ClipRepository.kt`

## 2026-08 heavy local work tracker

`heavyLocalWorkActive` は共通 `HeavyLocalWorkTracker` の参照カウントを `StateFlow<Boolean>` で公開します。同期取得後のclip/asset DB保存、画像decode・WebP圧縮・raw publish、like count取得後のまとまったDB保存、複数clipの一括タグ更新、大量relationを持つタグ削除/Undo、投稿削除の画像stagingとUndo復元だけがtracker対象です。

同期mediaはHTTP responseをbytesへ読む区間をtracker外に分離し、decode/圧縮/保存とDB更新だけをactiveにします。X API待機、MediaGridのpreload・preview・morphなど表示用background処理、単一clipの軽量tag更新、専用Progressを使う保存先移動はtracker対象外です。

## 2026-08 tag/group move and reorder Undo invalidation

`moveNode` / `moveNodeToParentAt` / `moveNodeToParentAtSlot` / `reorderSiblings` は、ユーザーの移動・並べ替えをDBで検証する前に直前の共通Undo slotをinvalidateします。移動や並べ替えが失敗しても旧slotは復活せず、成功時もこれらの操作用の新しいUndo slotは作りません。このinvalidateは移動・並べ替えAPIだけに限定し、X同期、likeCount、API使用量などの内部更新には適用しません。

## 2026-08 clip deletion durable Undo

`moveClipToTrash` は旧Undoを先に確定し、削除対象の最新 `ClipEntity`、全 `AssetEntity`、全 `ClipTagEntity` をsnapshotします。実在する管理画像を永続stagingへコピーしてsizeとSHA-256を検証できた場合だけ、snapshot payloadの保存とclipのhard DELETEを同じRoom transactionで確定します。CASCADEによりasset/relationは一覧Flowから即時に消えます。コピーまたはtransactionが失敗した場合はDBと元画像を残します。

削除確定後の元画像は、staging済みのcanonical path・size・digestと現在も一致する場合だけ破棄します。Undoは現在選択中の画像保存先へ検証済みbytesを復元し、同名の無関係ファイルを上書きせず、実際の復元pathを `AssetEntity.localPath` に設定します。同じIDでclip/assetsと元の`createdAt`を持つrelationsを1 transactionで復元し、成功後だけslotとstagingを消してpersistent previewの通常生成を再予約します。失敗時はDB transactionをrollbackし、slotとstagingを再試行用に保持します。slotのdismiss/overwriteでは記録されたstagingだけをbest effort cleanupします。

## 2026-08 OCR Undo

`updateOcrText` は操作開始時に以前の共通Undoを確定し、DB上の最新 `ocrText` と要求値を比較します。文字列が変わる場合だけ、新しい更新時刻とOCR文字列の保存、および変更前の `ocrText` / `ocrUpdatedAt` を持つ `OcrEditedUndoPayload` の保存を同じRoom transactionで確定します。同一文字列ではtimestampを更新せず、新しいUndo slotも作りません。Undo handlerはOCRの2列だけを元へ戻すため、保存前後に更新された `likeCount`、summary、本文、Asset、タグrelationは巻き戻しません。OCR認識処理はDBを変更せず、Undo slotも作りません。

## 2026-08 summary Undo

`updateSummary` は操作開始時に以前の共通Undoを確定し、DB上の最新summaryと新しい値を比較します。値が変わる場合だけ、変更前summaryを持つ `SummaryEditedUndoPayload` の保存とsummary専用UPDATEを同じRoom transactionで確定します。同値の場合や更新失敗時には新しいUndo slotを作りません。Undo handlerもsummary列だけを変更前へ戻すため、保存前後に更新された `likeCount`、OCR、本文、Asset、タグrelationは巻き戻しません。

## 第14実装

新規画像を1件ずつ処理し、source Bitmapからraw payloadを作ってからWebP品質85を保存し、単一Asset insertでIDを確定してraw slotのpublish・CRC再読込確認まで待ちます。asset ID未確定のpayloadは常に最大1枚分です。

一時IO失敗はDelayなしで2回再試行します。raw失敗でもWebPとAssetをrollbackせず、`downloadState`を変更せずrepairだけをenqueueします。既存JPEG生成enqueueはraw成否と独立して維持します。

## 2026-07 persistent JPEG preview

新規同期で元画像の保存と`AssetEntity`のinsertが完了した後、insert成功かつ`localPath`を持つasset IDだけを`MediaGridPreviewEnqueuer`へ渡します。JPEG生成処理は待たず、生成失敗を`downloadState`や同期結果へ反映しません。seedや直接fixture挿入はschedulerを経由しません。

clip削除ではDB削除成功後に、共有公開ロック下でasset ID由来のpreview JPEGをbest effort削除します。JPEG削除失敗は元画像・DB削除の失敗にはしません。

## 2026-07-10 bulk tag transaction

`applyClipTagChanges` applies `pendingAddTagIds` and `pendingRemoveTagIds` to all selected clip IDs through a single Room transaction. It is called only when the media-grid bulk editor is applied. 実際に追加・削除されたrelationだけを一括操作全体で1つの共通Undo slotへ保存し、Undoでは追加分だけを削除して削除分を元の`createdAt`で復元します。既存relationや選択外clipには触れず、実差分が0件ならslotを作りません。

`addAllFromTagToTag` はsource tagのrelationを一括取得し、target tagが未付与のclipだけへrelationを追加します。新規relationとその`createdAt`だけを共通Undo slotへ保存するため、Undo後もsource relationと操作前から存在したtarget relationは元の`createdAt`のまま残ります。実差分が0件なら新しいslotは作りません。

## 2026-08 single-clip tag Undo

`setClipTags` invalidates the previous common Undo slot, reads the clip's current relations, applies only the added/removed relation diff, and stores that same diff in the new slot within one Room transaction. Undo deletes only relations added by that edit and restores removed relations with their original `createdAt`; unrelated clip fields and other clips are not restored from snapshots.

## 2026-08 tag/group creation Undo

`createTag` / `createGroup` は旧共通Undo slotを先に破棄し、node挿入と確定IDを持つ作成Undo payloadの保存を1つのRoom transactionで行います。戻り値はDBへ保存された確定ID入りEntityです。Undoはpayloadに記録したタグまたはグループのIDだけを削除し、親group、兄弟順、同名nodeを含む既存nodeは変更しません。作成失敗時は新しいslotを残しません。

## 2026-08 tag/group edit Undo

`renameTag` / `renameGroup` はDB上の最新nodeと要求値を比較し、実際に変わる `name` / `colorId` の変更前値だけを共通Undo payloadへ保存します。名前だけ、色だけ、同時変更はいずれも1操作・1slotです。Undoはpayloadに含まれるfieldだけをDAOのfield更新queryで戻すため、その後に変わった `parentGroupId`、`sortOrder`、payload対象外fieldを上書きしません。無変更または更新失敗では新しいslotを残しません。

## 2026-08 tag deletion Undo

`deleteTag` は旧共通Undo slotを先に破棄し、削除前の `TagEntity` 全fieldとそのタグの全 `ClipTagEntity` を取得して、payload保存とタグDELETEを同じRoom transactionで確定します。Undoは同じタグIDを新規挿入してrelationを元の `createdAt` のまま復元します。同じIDの別タグが存在する場合や、削除済みclipなどにより全relationを復元できない場合はtransaction全体をrollbackし、Undo slotを保持します。他タグ、他relation、clip本体は変更しません。

## 2026-08 empty group deletion Undo

`deleteGroup` は旧共通Undo slotを先に破棄し、子groupとtagを持たないことを確認した上で、削除前の `TagGroupEntity` 全fieldをpayloadへ保存し、group DELETEとslot保存を同じRoom transactionで確定します。Undoは同じID、親、`sortOrder`、名称、色、timestampsでgroupだけを再挿入し、親やsiblingを更新しません。同じIDのgroupが既に存在する場合や親groupが消失した場合は復元せず、Undo slotを保持します。

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/ClipRepository.kt`

## 役割

認証、X同期、Room保存、画像取得、保存先変更、月間/API制限、タグ、概要、ローカル削除をまとめる業務ロジック層です。

## 主な処理

- OAuth: 認証Intent生成、code交換、`/users/me`、session保存、token更新、logout/revoke
- OAuth: 認証Intent生成、code交換、`/users/me`、session保存、token更新、logout/revoke。logoutはrevoke失敗を呼び出し側へ返し、ローカルのsessionは必ず削除する
- 同期: 月間上限確認、liked postsのpagination、新規投稿だけ保存、rate limit保存
- media: photoは回線を問わずWebP lossy quality 85へ変換して保存、video/GIFはpreviewImageUrlからthumbnailを取得して同じWebP保存経路を使う。新規同期では`wifi_waiting`を作らず、取得・変換失敗時も投稿を保存してassetを`failed`で記録する
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
## 2026-07 追加

- `createTag` / `createGroup` に `colorId` を追加した。
- `renameTag` / `renameGroup` は色も更新できるようになった。
- 既存の呼び出しは `standard` をデフォルトにしている。

## 2026-07 OCR update

- Added OCR detection for locally stored `photo` and `video_thumbnail` assets using the new OCR gateway abstraction.
- Added `updateOcrText` persistence with a timestamp and changed local delete to a hard delete that removes rows and files.

## 2026-08 structured OCR boundary

`detectOcrText()` now returns an `OcrPostRecognitionResult` containing the clip ID, ordered `OcrAssetRecognitionResult` entries (`assetId`, `localPath`, and the image-level `OcrRecognitionResult`), and the compatibility `fullText`. Eligible assets remain limited to locally available `photo` and `video_thumbnail` entries sorted by asset ID. The full text still joins non-blank image full texts with `\n\n`. Structured results are not persisted; the OCR UI keeps them only in the active unsaved session.

## 2026-08 OCR session flow

OCR detection remains read-only. The existing `updateOcrText()` path is the only persistence path and still preserves field-level `ocrText` / `ocrUpdatedAt` updates, no-op behavior, and OCR edit Undo semantics. ViewModel save callbacks report success or failure so the UI closes only after a successful transaction.

## 2026-07 media thumbnail update

- `photo` assets are downloaded from `media.url`, converted to WebP, and stored with quality 85.
- `video` and `animated_gif` assets are saved only from `media.previewImageUrl`.
- `photo` and `video_thumbnail` go through the same image download and WebP save flow regardless of network type.
- Newly synced media never create `wifi_waiting`.
- If image download, decode, or WebP conversion fails, the post still saves and the asset is recorded with `downloadState = "failed"`.
## 2026-07 media grid lightweight flow

- `mediaGridSource` is a repository Flow that does not depend on `clipsWithDetails` or `observeTags()`.
- It combines current clips, lightweight asset rows, and lightweight clip-tag rows. The source stores tag IDs and one precomputed `TweetAuthorKey`; it never stores or copies `TagEntity` lists.
- Clips with matching filters but no media are still preserved in the source so the UI can show the existing media-free empty state.
- The lightweight flow keeps the card path separate, and card rendering still uses `clipsWithDetails`.

## 2026-07 media-grid tweet dialog

- `observeClipWithDetails(clipId)` combines only the selected current clip, its assets, and its tags.
- The Flow emits `null` for a missing or deleted clip and stops when the ViewModel clears the selected clip ID.
- Updates to the selected clip, its assets, or its tag relations re-emit the `ClipWithDetails` used by the open dialog.
# メディアグリッド高速化追補

Repositoryは現存Clip、対象Asset、ClipTagから軽量スナップショットを構築する。ClipごとのタグIDは`LongArray`で保持し、タグEntityの複製とメディア専用タグ経路の二重購読を行わない。

ClipTag行はClipごとの可変バッファへ集約してから一度だけ`LongArray`へ変換する。投稿者キー、投稿日、local day、メディア件数もsource生成時に前計算するため、タグ名・色だけの変更ではsource revisionを進めず、ClipTag変更では更新する。
