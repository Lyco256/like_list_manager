# `UiStateRenderingTest.kt`

1,000投稿のLazyColumnを末尾まで移動し、末尾カードを確認した後に先頭へ戻れることも検証します。

読み込み中DialogのtitleとmessageをCompose semanticsで直接検証し、スクリーンショットなしでloading表示の回帰を検出します。

同期結果Dialogを直接renderし、権限不足メッセージの表示と閉じる操作を検証します。

保存先移動の見積もり確認Dialogを直接renderし、キャンセル操作でDialogが閉じ、移動開始側のcallbackが呼ばれないことを検証します。

保存済みPhotoを含むメディアグリッドを直接renderし、Photoタップで全画面viewerが開くこと、位置表示が出ること、閉じる操作でviewerが消えることを検証します。

画像グリッドのComposeテストでは、1〜4枚のケースで左右余白と行配置が崩れないことを測定します。`MediaGrid` と `EnhancedMediaGrid` の両方で、カードが左右 `16dp` 余白で配置されることを確認します。
## 2026-07 media grid headers / overlay

- `classifiedMediaGridShowsDateHeadersVideoBadgeAndErrorCells` now verifies the PostTime header row plus the existing video badge and error cells.
- `classifiedMediaGridShowsLikeHeadersAndLikeOverlaysWithoutBreakingBadgesOrErrors` covers LikeCount headers, top-left like overlays, and the null-likeCount no-overlay case.
- The existing 4-column layout regression still checks the grid geometry and the empty-state path remains unchanged.

## 2026-07 media grid pinch / anchor follow-up

- The large-dataset smoke test identifies the media cell nearest the visible grid center before each column-count change and verifies that same cell remains displayed after the grid is resized.
## 2026-07 media grid column coverage

- The media-grid compose smoke now checks that changing the column count keeps the anchor media cell visible and that the day/month headers still span the full grid width.
