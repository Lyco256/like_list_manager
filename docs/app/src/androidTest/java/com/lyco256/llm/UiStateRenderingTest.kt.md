# `UiStateRenderingTest.kt`

## 2026-08-01 async error-state assertion

- `classifiedMediaGridShowsDateHeadersVideoBadgeAndErrorCells` waits for both terminal error tags before asserting them. The missing-local/remote candidate path is asynchronous and must not be asserted synchronously after `setContent`.

## 2026-07-10 media-grid selection coverage

- Covers long-press tweet-level selection, shared selection across multiple assets, selected count, selection indicators, like-count suppression, and the 2–6 column card-dialog action boundary.
- Covers select-all over the filtered media result, union-based initial tag selection, pending tag changes, discard confirmation, and one apply callback for all selected clip IDs.

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
- Directly composed grid tests cover the nullable initial data key as well as keyed production state, so a completed frame cannot remain hidden behind Progress.
- The large-dataset column-change checks wait for the replacement keyed frame, because Compose idleness does not include its `Dispatchers.Default` build.

## 2026-07-19 第3実装 placeholder rendering

- `cellPlaceholderRenderingStaysIndependentInLightAndDarkThemes` composes two independent placeholder cells in both light and dark color schemes and verifies that an Image-state cell does not add the placeholder rendering modifier.

## 2026-07 media grid pinch / anchor follow-up

- The large-dataset smoke test identifies the media cell nearest the visible grid center before each column-count change and verifies that same cell remains displayed after the grid is resized.
- The large-dataset smoke test waits for the asynchronously prepared grid container before its initial display assertion, so a slow device does not turn valid preparation time into a false failure.

## 2026-07 media-grid tweet dialog

- `mediaGridTweetDialogShowsLoadingAndNotFoundStatesAndCanClose` covers the dialog state tags and close transition without requiring a database.
- `mediaGridTweetDialogReusesCardActionsAndClosesBeforeLocalDelete` exercises the reused card's image viewer, tag selection, summary/OCR save, X entry point, author callback, and local-delete confirmation from inside the media-grid dialog.
## 2026-07 media grid column coverage

- The media-grid compose smoke now checks that changing the column count keeps the anchor media cell visible and that the day/month headers still span the full grid width.
