# `Daos.kt`

## 2026-07-10 bulk tag transaction

`ClipDao.applyClipTagChanges` is the `@Transaction` boundary covering `pendingRemoveTagIds` deletion and `pendingAddTagIds` insertion for every selected clip. It rejects overlap between add and remove sets.

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/Daos.kt`

## 保存容量集計

- `getStoredAssetStats`: `localPath` がある保存済み画像・サムネイルの件数と `sizeBytes` 合計をSQLで集計し、保存容量表示と移動見積もりに使う
- `countActiveClips`: `isDeleted = 0` の有効投稿件数を集計する

## 役割

Roomを通じた投稿、画像、タグ、投稿タグ関連、同期状態のqueryとtransactionを定義します。

## 主要処理

- `ClipDao`: 有効投稿の監視、投稿/画像挿入、概要・削除状態更新、タグ集合置換、同期状態保存、保存先移動時の画像パス更新
- `ClipDao`: 有効投稿の監視、投稿/画像挿入、概要・削除状態更新、タグ集合置換、同期状態保存、月別API使用量履歴の更新、保存先移動時の画像パス更新
- `countClips`: 初回サンプル投入の判定
- `TagDao`: グループ／タグ監視、削除済み投稿を除く件数集計、追加・更新・削除、子要素数確認、一括タグ付け対象取得
- `observeActiveClipTags`: グループ件数用に有効投稿のタグ関連だけを監視
- `replaceClipTags`: 現在値との差分だけを追加・削除するtransaction
- `observeApiUsageMonths` / `getTotalBillableReadCount` / `incrementApiUsageMonth`: 月別履歴の監視、累計取得、加算を行う

## 関連ファイル

- `Entities.kt.md`: query対象のテーブルと戻り値を定義します。
- `LikeListDatabase.kt.md`: DAOを公開します。
- `ClipRepository.kt.md`: DAOを業務処理として組み合わせます。
- `../MainActivity.kt.md`: DAO更新結果をFlow経由で表示します。

## 変更時の確認

SQL変更時はEntity列名、Foreign Key、削除時のcascade、Flowの更新範囲、重複時のConflictStrategyを確認します。

## いいね数再取得（2026-06-20）

- `getActiveClips` でローカル削除されていない再取得候補を読みます。
- 成功時はいいね数と取得日時を更新して失敗情報をクリアし、明確な恒久失敗だけ失敗日時・理由を記録します。

## 2026-07 OCR update

- Added DAO queries for `updateOcrText` and hard-deleting a clip by id.

## 2026-07 media-grid tweet dialog

- Added clip-scoped Flow queries for one active clip, its assets ordered by `id ASC`, and its joined tags.
- These queries do not change the database schema or the lightweight media-grid queries.
## 2026-07 media grid lightweight query

- `observeActiveMediaGridAssetRows` returns active clip media rows only for `photo` and `video_thumbnail` assets, joined through `clips` so deleted clips stay out.
- `observeActiveMediaGridClipTags` returns clip tag rows for active clips that still have media-grid assets, so media filters can work without loading full card data.
- `MediaGridAssetRow` carries the clip fields needed for filtering and sort order plus the asset fields needed to render the grid.
- The lightweight media-grid path keeps one row per asset and excludes unsupported asset types entirely.
# メディアグリッド高速化追補

メディアグリッドAsset projectionは`clipId`とAsset固有列だけを返し、Clip本文・概要・OCR・投稿者・日時・いいね数などの重複列を含めない。対象はactive Clipの`photo`/`video_thumbnail`のみ。タグ関連はactive ClipTag queryへ統一する。
