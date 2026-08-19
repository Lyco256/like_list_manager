# `Daos.kt`

## 2026-08 OCR Undo

`ClipDao.updateOcrText` はIDで指定した投稿の `ocrText` と `ocrUpdatedAt` だけを更新し、更新件数を返します。通常保存とUndoの両方で対象投稿が存在することを確認でき、`likeCount`、summary、タグrelationなどを古いEntity snapshotで上書きしません。

## 2026-08 summary Undo

`ClipDao.getClip` は概要保存transaction内で対象投稿の最新値を取得します。`updateSummary` はIDで指定した投稿の `summary` 列だけを更新して更新件数を返し、概要保存時やUndo時に `likeCount`、OCR、本文など別fieldを古いEntity snapshotで上書きしません。

## 2026-07-10 bulk tag transaction

`ClipDao.applyClipTagChanges` is the `@Transaction` boundary covering `pendingRemoveTagIds` deletion and `pendingAddTagIds` insertion for every selected clip. It rejects overlap between add and remove sets. 対象clip・tagの既存relationを1 queryで取得して実差分を返し、追加・削除はrelationリスト単位のDAO呼び出しで処理するため、clipごとのread/writeは行いません。削除差分は元の`createdAt`を維持します。

## 2026-08 tag/group field edit

`TagDao.getTag` / `getGroup` は編集transaction内で最新nodeを取得します。`updateTagName` / `updateTagColor` / `updateGroupName` / `updateGroupColor` は対象fieldと `updatedAt` だけを更新し、共通Undoで親・兄弟順などの同時期の更新を巻き戻さないために使います。

## 2026-08 tag deletion Undo

`ClipDao.clipTagsForTag` はタグ削除前の全relation取得とUndo後の完全性確認に使います。`TagDao.deleteTag` は削除件数を返し、対象タグを削除できなかったtransactionを成功扱いしません。タグDELETE時の `clip_tags` 削除は既存Foreign KeyのCASCADEを利用します。

## 2026-08 empty group deletion Undo

`TagDao.deleteGroup` は削除件数を返し、対象groupを1件削除できた場合だけUndo slot保存transactionを確定できるようにします。`insertGroup` のABORT conflictとForeign Key RESTRICTに加え、Repository側でID衝突と親group存在を挿入前に確認します。

## 単一Undo slot

`UndoDao` は `undo_slot` のID=1をFlow/単発で取得し、`replaceSlot` で旧行を削除して新行を挿入します。`deleteSlot` は古いUI通知が置換後のslotを消さないよう、CoordinatorのDB transactionとidentity確認から使われます。

## 2026-07 persistent JPEG preview

- `insertAssets`は入力順に対応するRoom insert IDの`List<Long>`を返し、呼び出し側がinsert成功assetだけを後続処理へ渡せる。
- `getAsset`はworkerの実行時・公開直前のasset再確認に使う。schema列やmigrationは追加していない。

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/Daos.kt`

## 保存容量集計

- `getStoredAssetStats`: `localPath` がある保存済み画像・サムネイルの件数と `sizeBytes` 合計をSQLで集計し、保存容量表示と移動見積もりに使う
- `countClips`: hard DELETE後の現在の投稿件数を集計する

## 役割

Roomを通じた投稿、画像、タグ、投稿タグ関連、同期状態のqueryとtransactionを定義します。

## 主要処理

- `ClipDao`: 全投稿の監視、投稿/画像挿入、概要・OCRのfield-level更新、投稿のhard DELETE、タグrelation差分、同期状態・月別API使用量履歴、保存先移動時の画像パス更新
- `countClips`: 初回サンプル投入の判定
- `TagDao`: グループ／タグ監視、件数集計、追加・field-level更新・削除、子要素数確認、一括タグ付け対象取得
- `observeClipTags`: グループ件数と軽量メディア表示に使うrelationを監視
- `replaceClipTags`: 現在値との差分だけを追加・削除するtransaction
- `UndoDao`: 単一永続Undo slotの監視、取得、置換、削除
- `observeApiUsageMonths` / `getTotalBillableReadCount` / `incrementApiUsageMonth`: 月別履歴の監視、累計取得、加算を行う

## 関連ファイル

- `Entities.kt.md`: query対象のテーブルと戻り値を定義します。
- `LikeListDatabase.kt.md`: DAOを公開します。
- `ClipRepository.kt.md`: DAOを業務処理として組み合わせます。
- `../MainActivity.kt.md`: DAO更新結果をFlow経由で表示します。

## 変更時の確認

SQL変更時はEntity列名、Foreign Key、削除時のcascade、Flowの更新範囲、重複時のConflictStrategyを確認します。

## いいね数再取得（2026-06-20）

- `getAllClips` で現在DBに存在する再取得候補を読みます。
- 成功時はいいね数と取得日時を更新して失敗情報をクリアし、明確な恒久失敗だけ失敗日時・理由を記録します。

## 2026-07 OCR update

- Added DAO queries for `updateOcrText` and hard-deleting a clip by id.

## 2026-07 media-grid tweet dialog

- Added clip-scoped Flow queries for one current clip, its assets ordered by `id ASC`, and its joined tags.
- These queries do not change the database schema or the lightweight media-grid queries.
## 2026-07 media grid lightweight query

- `observeMediaGridAssetRows` returns current media rows only for `photo` and `video_thumbnail` assets.
- `observeClipTags` supplies current clip-tag rows, so media filters can work without loading full card data.
- `MediaGridAssetRow` carries the clip fields needed for filtering and sort order plus the asset fields needed to render the grid.
- The lightweight media-grid path keeps one row per asset and excludes unsupported asset types entirely.
# メディアグリッド高速化追補

メディアグリッドAsset projectionは`clipId`とAsset固有列だけを返し、Clip本文・概要・OCR・投稿者・日時・いいね数などの重複列を含めない。対象はDBに現存するClipの`photo`/`video_thumbnail`のみ。タグ関連は共通ClipTag queryへ統一する。
