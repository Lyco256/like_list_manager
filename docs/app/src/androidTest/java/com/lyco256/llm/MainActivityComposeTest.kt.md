# `app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt`

## 2026-08-02 production claim-path regression

`productionMorphClaimDrawsBeforePhysicalUpWhenFirstPointerStartsScroll` seeds a real filtered classified grid, retains all resident draw handles, starts with a one-pointer scroll, adds the second pointer, and verifies the real `ClassifiedMediaGridContent` path draws `Tracking`/`Morph` on the unified surface before physical up. It asserts direction, complete plan/model, generation-backed protection, positive progress, no fallback, and unchanged columns.

## 2026-08-01 production uniform-lattice and toolbar coverage

- The real classified Activity flow continues to exercise normal grid rendering, production same-surface Morph input, reveal/handoff round trips, filter/sort changes, and the absence of the historical overlay.
- The real classified-grid round trip also asserts canonical fallback callbacks for the incomplete-image 4→5 and 5→4 gestures, plus no callback below the release threshold.
- The production rendering contract verifies one clipped LazyGrid surface and opaque positive-layer containers for both the filter/count/sort toolbar and selection toolbar. No second grid or overlay is introduced.

`imageViewerSwipeMovesBetweenSavedPhotosWithoutChangingDatabase` sends the horizontal swipe through the Compose `image_viewer` node instead of relying on raw full-screen coordinates; the viewer parent drag handler leaves horizontal movement available to `HorizontalPager`.

## 2026-07-31 production Morph correction coverage

The Phase 1 production test now asserts the legacy one-step pinch path and the absence of both the old production Morph Canvas and TEST_HARNESS same-surface tag. Production normal grid rendering remains connected during the gesture.

- The real Activity test covers production release-time column changes, repeated increase/decrease round trips, threshold no-op, dialog interaction, recreation, and filter/sort changes.
- Column changes are synchronized against the session's `columnCount`, `requestedColumnCount`, and frame key; clipped cell bounds are not used as a column-count oracle during handoff translation.

## 2026-07-19 direct preview coverage

- The grid fixture now lets the shared Coil loader read local image files directly; it does not pre-generate or hydrate custom thumbnail files.
- The same tests cover multiple initial visible cells, activity recreation, filter/sort changes, slow drag, fast fling, immediate cell actions, placeholders, and final errors. Direct candidate unit tests cover mixed source ordering and cache identity.

## 2026-07-19 第5実装 integration coverage

- The classified display-toggle fixture pre-generates persistent previews for the photo and video cells, then opens the media grid and waits for both cells to finish their current image requests without scrolling. The failed asset remains the no-candidate error path.
- The same fixture still covers activity recreation, filter and sort source revisions, column changes, placeholders, display success, cell taps, and the tweet dialog. The large-media test remains the fast-fling, retouch, final-viewport, and immediate-action regression gate.
- No Macrobenchmark or measurement path is changed or run.

隔離された `com.lyco256.llm.test` 上で主要Compose画面を検証するInstrumentationテストです。各テストの前に隔離DBだけを初期化し、seedデータを投入します。本番 `com.lyco256.llm` のDBや画像には触れません。

主な検証内容:

- 主要タブ、設定画面、設定画面表示中のタブ非表示、Android戻るでの元タブ復帰、使用量セクション、データ管理セクション、隔離環境でのXログイン無効化、X API設定の保存/trim/消去UI
- 設定画面の開閉操作とDB fingerprint不変
- 検索/絞り込みの適用、日付条件、DatePicker内解除、投稿者条件とタグ条件の複合E2E、投稿者Dialogクリア、タグ条件のみクリア、投稿者クリックによる分類済み投稿者フィルター遷移、キャンセル、BackHandler破棄、Dialog内全クリア確認キャンセル、全クリアとDB fingerprint不変
- 投稿カードのいいね数ポップアップが詳細と暫定警告を表示し、開閉でDB fingerprintを変えないこと
- 未分類から分類済みへの移動、分類解除、Roomの `clip_tags` 更新
- 別グループに同名の子タグがある場合の複数タグ同時付与
- 投稿カードのローカル削除Dialogで、キャンセル時は保持、確定時は一覧から消えつつDB上はsoft deleteとして残ること
- 投稿カードの概要編集がDBへ保存され、Activity再作成後も入力内容が残ること
- 保存済みPhotoの画像viewerが戻る操作で閉じること、複数画像をswipeで移動できること、開閉やページ移動でDB fingerprintを変えないこと
- タグ/グループ作成、タグ/グループ作成Dialogキャンセル、同名子タグ、タグ名称変更、グループ名称変更、名称変更Dialogキャンセル、タグの別グループ移動、タグ/グループ移動Dialogキャンセル、別タグへの一括追加、別タグへの一括追加Dialogキャンセル、削除、タグ/グループ削除Dialogキャンセル
- popup外tapがカードへ伝播せず、タグ関係も変化しないこと
- 空状態、同期エラー表示、Activity再作成後のタブ復元と分類済みフィルター復元

変更時は `scripts/run-safe-integration-check.cmd` で、同じ実機上の本番package metadataが前後不変であることも合わせて確認します。

## 2026-07-01 追記: 設定画面/結果Dialogの安定操作

主要導線は `top_settings_button` から `settings_screen` を開き、設定画面内の `settings_*` test tagで操作します。同期未ログインエラーの結果Dialogを閉じ、同期エラー表示前後のDB fingerprintが変わらないことも確認します。

いいね数更新の確認Dialogは、隔離DBに数値post IDの対象clipを追加して `settings_like_refresh` から開き、`settings_like_refresh_cancel` で閉じた前後のDB fingerprintが変わらないことを確認します。

投稿カードのローカル削除Dialogは `clip_local_delete_*_<clipId>` のtest tagで開閉/実行し、キャンセル時は保持、確定時は一覧から消えつつDB上はsoft deleteとして残ることを確認します。

分類済み検索では、フィルタ適用後に一覧をスクロールして `scroll_to_top` で戻っても検索条件summaryと対象clipが残りDB fingerprintが変わらないことを確認します。未分類一覧では、タグchip選択後に一覧をスクロールし、対象clipへ戻ってから分類確定できることを確認し、スクロールで未確定選択状態が失われないことを固定します。

## 2026-07-02 追記: 設定画面UI調整

- 設定画面の検証では `settings_login_logout` を使い、未ログイン時の「保存してXにログイン」とログイン中の「Xからログアウト」を同一ボタンで扱う
- 同期系の表示確認では `いいね数を更新しますか？` の確認Dialogを使う
- `settings_client_id_save` / `settings_client_id_clear` は横並びのボタンとして確認する
- 既存の設定画面系テストは、項目間の余白や見出し表示の変更後も `settings_screen` / `settings_x_api_section` / `settings_sync_section` / `settings_usage_section` / `settings_data_management_section` を基準に検証する
## 2026-07-02 設定画面UI微修正

- 使用量セクションの確認では `警告ライン` と `停止ライン` が出ないことを確認する
- いいね数更新の確認Dialogは `いいね数を更新しますか？` を使う
- 設定画面の表示検証は `settings_screen` / `settings_x_api_section` / `settings_sync_section` / `settings_usage_section` / `settings_data_management_section` を基準にする

## 2026-07-03 追記: 設定画面表示状態

- `settings_content` をスクロールして4セクションを表示確認する
- 設定画面表示中は `tab_unclassified` / `tab_classified` / `tab_tags` が存在しないことを確認する
- Android戻るボタン相当で設定画面を閉じ、開く前のタブへ戻ることを確認する
- X API設定に `Callback URI` / `Scope` が出ないことを確認する
- データ管理に保存件数、画像枚数、保存先の使用状況、ストレージ凡例が表示され、旧ラベルが出ないことを確認する

## 2026-07-04 Update

- Added coverage for the tag-management dropdown menu flow that creates both child groups and child tags under the pressed group.
- The color-picker persistence test still verifies create and rename flows, and the X logo open button test remains in place.

## 2026-07-05 Update

- The color-picker E2E coverage now asserts that the dialog renders two palette rows, so the fixed two-row layout is verified in tests.

## 2026-07 OCR update

- Added compose coverage for the OCR dialog save/cancel flow and the hard-delete path.
- The settings data-management check now waits for the seeded clip count before asserting the summary, and the like-count/local-delete flows scroll the card into view before tapping overflow actions.
- The local-delete test now follows the tweet options menu (`tweet_options_button` -> `tweet_options_local_delete`) before asserting the confirmation dialog.
- The like-count test now verifies the tap path without depending on popup rendering, which keeps the device run stable while still proving the database stays unchanged.
## 2026-07 media grid pinch coverage

- The classified display toggle test now exercises pinch-in and pinch-out on the real app, then verifies the media-grid column count survives activity recreation and switching back and forth between card and grid mode.
- The media-grid filtering fixture recreates the Activity after replacing its complete Room snapshot so the classified and media-grid flows are observed from the same state.
- The classified grid flow taps a media cell, verifies the existing tweet card opens inside `media_grid_tweet_dialog`, closes it, and confirms the grid remains available with the same cell geometry.
- The same test taps both a photo and a video thumbnail belonging to one clip and verifies that both open the same tweet card dialog.

## Current simple column-change coverage

## 2026-07-18 第2実装 integration coverage

- The existing large local-media fling test remains the integration gate for fast fling image following, immediate cell action, and filter revision changes.
- The production path keeps the normal grid and column-change behavior unchanged; no Macrobenchmark or measurement code is added.
- The same fixture performs a normal paced drag, a fast fling followed by an immediate touch-down/up retouch, and opens the current visible cell without waiting for image completion.

## 2026-07-19 第3実装 placeholder rendering

- `classifiedDisplayToggleSwitchesBetweenCardAndMediaGridAndSurvivesActivityRecreation` waits for a valid persistent preview to display and verifies that `media_grid_placeholder_<assetId>` is gone after the current candidate succeeds.
- The same flow verifies that an Error cell keeps `media_grid_error_<assetId>` and never exposes a placeholder tag.
- The existing fling/retouch/cell-action test remains unchanged as the integration regression gate for scrolling and immediate interaction.
- `normalClassifiedGridComposesProductionCanvasDuringLivePinch` drives the real classified Activity with a two-pointer pinch, seeds the production retained store from the loaded local previews, and verifies that the `media_grid_morph_canvas` is composed during the live gesture. Canvas removal and handoff completion remain covered by the production-host handoff tests.

## 2026-07 viewport dispatch integration coverage

- `classifiedMediaGridSingleFlingKeepsLatestImageAndImmediateCellActionAfterFilter` seeds 96 local classified media assets, performs one fast fling without waiting between input events, and verifies that the visible range advances and a visible image request succeeds.
- The same flow opens a cell immediately after the fling, applies a filter that changes the source revision, verifies that only the target cell remains visible, and opens that cell immediately. This covers image following, no rollback after a screen/filter change, and immediate cell operations.

- The real two-pointer test verifies 4→5→4 changes, a threshold-miss no-op, repeated round trips, stable media-cell position, immediate cell interaction, scroll after the change, and absence of `media_grid_morph_overlay` after every release.

## 2026-07-19 第4実装 integration coverage

- The classified display-toggle flow waits for the first local media cell to display without scrolling or tapping, covering the ordered initial prepared-image path and card-to-grid transition.
- The large-media flow keeps the normal paced drag, fast fling, immediate retouch, final visible-range selection, and post-filter cell action as the integration regression coverage for operation-state suppression and latest-viewport following.
- Placeholder tags, existing image success/error behavior, column changes, selection, dialogs, and Macrobenchmark behavior remain covered by their existing tests and are not changed by this implementation.

## 2026-08-01 Phase 1 row reflow coverage

- The integration build uses `TEST_HARNESS=true`; `testHarnessMediaGridUsesSameSurfaceRendererWithoutLegacyMorphCanvas` drives the TEST_HARNESS pinch and explicitly rejects the historical Morph canvas, while the rendering contract test verifies the same-surface modifier statically.
- The classified display-toggle flow exercises the real LazyVerticalGrid pinch handoff after the claim-time capture, including column changes through the actual target frame.
- Before pinch assertions, the flow waits for `showInitialProgress == false`; a visible media item alone does not prove that the TEST_HARNESS Morph controller is enabled.
