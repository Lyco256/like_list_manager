# `app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt`

## 2026-08-12 選択済みタグ条件とTreeの双方向同期

- Treeで選択したタグが絞り込み画面本体の選択済み一覧へ現れること、一覧側の状態変更がTree再表示時に反映されることを検証します。
- 一覧の削除ボタンで条件をNONE相当に戻した後のTree表示と、Dialog全体をキャンセルした際に確定済みfilterへ影響しないことも続けて検証します。

## 2026-08-10 production Stable-idle fast path counters

- stable-idle後の代表4→5 claimはfast-path hit 1、live fallback／claim-time full capture 0、claim時pair／plan／RequiredRenderSet／text measure 0、selected direction render model 1を確認する。
- missing required imageを同じidentityでReady公開した区間はresource membership recheckだけが増え、stable snapshot、focal entry、pair、plan、RequiredRenderSet、full captureが増えないことを確認する。
- 既存の先頭one-finger overscroll→同一gesture二本指pinch回帰は実offsetが変わらないpointer状態だけではSnapshotを無効化せず、fast-path 1、live/full capture 0でMorphを継続することを確認する。実offset 1px差のstale拒否は`MediaGridMorphTest`で固定する。

## 2026-08-16 stable-idle readiness synchronization

- 代表Morphテストは対象viewportの最新readyイベントだけを採用し、cache世代のrequest／invalidate／publishが静止してからperformance traceをクリアする。これにより、古いready通知や直後の非同期再準備でclaimがfallbackへ落ちる競合を検出可能な期待値のまま防ぐ。

## 2026-08-10 production Morph claim/draw counters（Phase 2履歴）

- headerなし／あり4→5の既存代表gestureで、Phase 2時点ではstable-idle後claim pair build 0、selected plan 1、RequiredRenderSet 1、selected direction model 1、反対direction model 0、claim text measure 0を確認していた。Phase 3の現行期待値は上記fast-path節を正とする。
- 各Morph draw frameのcounter snapshotがclaim時から増えず、Rect/blend helper callが0のままであることを、既存のsource/target geometry・header Crossfade・exact handoff回帰と同時に検証する。

## 2026-08-09 continuous fling hot-path counters

- The production fling regression asserts that full Morph capture, row-pair preparation, urgent requests, resident PreviewPreloader reconciliation, and exact target index construction remain zero during motion while lightweight viewport signatures continue to publish. After idle, preparation is allowed and the existing Morph/pinch behavior remains covered.

## 2026-08-07 start and exact-handoff regression

- The same three representative 4-to-5 sequences now compare the underlying source viewport with the first Morph model and compare the progress-1 Morph endpoint with the first Idle Normal frame by item/header identity and rectangle.
- The existing visible-header sequence must exercise a real date/like-count section-header appearance or disappearance. Without adding another gesture, every observed Morph frame verifies linear header-height scaling and the matching text fade, in addition to claim, no-dark-fallback, column completion, and exact handoff checks.
- The existing headerless all-slot-change case returns 5-to-4 immediately after its 4-to-5 handoff without waiting for a stable-idle readiness event. This single reverse gesture verifies an accepted claim, a non-dark first Morph frame, no fallback, column completion, exact target validation, endpoint/Normal position equality, and unlocked Idle state; no repeated location matrix was added.
- These cases no longer force all 48 fixture assets into the resident store. Production preparation/readiness must supply the required visible set. Readiness waits also match the actually composed first media ordinal, so a ready event from a previous scroll position cannot authorize the gesture.

## 2026-08-05 real-device sequence reduction

- The broad display-toggle test keeps the fallback, reverse-direction, and threshold-miss patterns, but removes the duplicate second increase/decrease pair after cell interaction; recreation and source-revision checks remain.
- The legacy TEST_HARNESS same-surface smoke is excluded from the default production sequence because the production claim and handoff cases cover the same behavior.
- All three production location-matrix patterns remain enabled; only the duplicate post-interaction increase/decrease pair was removed.

## 2026-08-05 exact 4-to-5 production matrix

The production coverage is split into exactly three representative 4-to-5 sequences: all slots change, a visible PostTime header, and a roughly four-row offset. Each claim asserts RequiredRenderSet counts, exact target row metadata, Morph-before-up, no fallback, no rollback, exact viewport validation, and terminal handoff completion. The four-row case also verifies that the first post-handoff underlying draw is the matching Normal frame. These three flows use a 15-second per-wait cap and bounded draw/phase/handoff event assertions; they do not add a repeated location matrix.

## 2026-08-02 production claim-path regression

`productionMorphClaimDrawsBeforePhysicalUpWhenFirstPointerStartsScroll` seeds a real filtered classified grid, retains all resident draw handles, starts with a one-pointer scroll, adds the second pointer, and verifies the real `ClassifiedMediaGridContent` path draws `Tracking`/`Morph` on the unified surface before physical up. It asserts direction, complete plan/model, generation-backed protection, positive progress, no fallback, and unchanged columns.

## 2026-08-01 production uniform-lattice and toolbar coverage

- The real classified Activity flow continues to exercise normal grid rendering, production same-surface Morph input, reveal/handoff round trips, filter/sort changes, and the absence of the historical overlay.
- The real classified-grid round trip also asserts canonical fallback callbacks for the incomplete-image 4→5 and 5→4 gestures, plus no callback below the release threshold.
- The production rendering contract verifies one clipped LazyGrid surface and opaque positive-layer containers for both the filter/count/sort toolbar and selection toolbar. No second grid or overlay is introduced.

`imageViewerSwipeMovesBetweenSavedPhotosWithoutChangingDatabase` sends the horizontal swipe through the Compose `image_viewer` node instead of relying on raw full-screen coordinates; the viewer parent drag handler leaves horizontal movement available to `HorizontalPager`.

## 2026-07-31 production Morph correction coverage（履歴: 現行経路では不使用）

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
- 検索/絞り込みの適用、日付条件、DatePicker内解除、投稿者条件とタグ条件の複合E2E、深さ3以上・大量タグのTree popup、タグ／グループ巡回、popup Back後のdraft保持、投稿者Dialogクリア、タグ条件のみクリア、投稿者クリックによる分類済み投稿者フィルター遷移、キャンセル、Dialog内全クリア確認キャンセル、全クリアとDB fingerprint不変
- 投稿カードのいいね数ポップアップが詳細と暫定警告を表示し、開閉でDB fingerprintを変えないこと
- 未分類から分類済みへの移動、分類解除、Roomの `clip_tags` 更新
- 別グループに同名の子タグがある場合の複数タグ同時付与
- 投稿カードのローカル削除Dialogで、キャンセル時は保持、確定時は一覧とDBからhard DELETEされること
- 投稿カードの概要編集がDBへ保存され、Activity再作成後も入力内容が残ること
- 保存済みPhotoの画像viewerが戻る操作で閉じること、複数画像をswipeで移動できること、開閉やページ移動でDB fingerprintを変えないこと
- タグ/グループ作成、タグ/グループ作成Dialogキャンセル、同名子タグ、タグ名称変更、グループ名称変更、名称変更Dialogキャンセル、タグの別グループ移動、タグ/グループ移動Dialogキャンセル、Treeをscrollして選ぶ別タグへの一括追加、別タグへの一括追加Dialogキャンセル、削除、タグ/グループ削除Dialogキャンセル
- popup外tapがカードへ伝播せず、タグ関係も変化しないこと
- 空状態、同期エラー表示、Activity再作成後のタブ復元と分類済みフィルター復元

変更時は `scripts/run-safe-integration-check.cmd` で、同じ実機上の本番package metadataが前後不変であることも合わせて確認します。

## 2026-07-01 追記: 設定画面/結果Dialogの安定操作

主要導線は `top_settings_button` から `settings_screen` を開き、設定画面内の `settings_*` test tagで操作します。同期未ログインエラーの結果Dialogを閉じ、同期エラー表示前後のDB fingerprintが変わらないことも確認します。

いいね数更新の確認Dialogは、隔離DBに数値post IDの対象clipを追加して `settings_like_refresh` から開き、`settings_like_refresh_cancel` で閉じた前後のDB fingerprintが変わらないことを確認します。

投稿カードのローカル削除Dialogは `clip_local_delete_*_<clipId>` のtest tagで開閉/実行し、キャンセル時は保持、確定時は一覧とDBからhard DELETEされることを確認します。永続画像stagingからの復元はRepository integration testの責務です。

分類済み検索では、フィルタ適用後に一覧をスクロールして `scroll_to_top` で戻っても検索条件summaryと対象clipが残りDB fingerprintが変わらないことを確認します。未分類一覧では、タグchip選択後に一覧をスクロールし、対象clipへ戻ってから分類確定できることを確認し、スクロールで未確定選択状態が失われないことを固定します。

カード内のタグ選択を操作する統合テストは、対象clipの永続タグ状態から未分類/分類済み画面を確定し、ViewModelの対象一覧への反映、縦一覧の対象カード、カード配下の横タグselectorの順に待ってから対象chipへスクロールします。これにより実機viewportやテスト実行順に依存せず、同じtest tagを持つ別カードを誤操作しません。

分類済み一覧の絞り込み保持テストは、横スクロール可能なtoolbar summaryの文字列が狭い実機viewportと交差することを前提にせず、一覧スクロールと先頭復帰の前後で同じ適用済み条件semanticsが1件保持されることを確認します。対象カードの表示とDB fingerprint不変の検証は維持します。

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

## 2026-08-13 classified filter clear path

- 分類済みtoolbarからclearを除いたため、filterのDB非変更E2Eは検索/絞り込みDialog内の全クリア確認を実行してから適用する経路を使用します。

## 2026-08-01 Phase 1 row reflow coverage

- The integration build uses `TEST_HARNESS=true`; `testHarnessMediaGridUsesSameSurfaceRendererWithoutLegacyMorphCanvas` drives the TEST_HARNESS pinch and explicitly rejects the historical Morph canvas, while the rendering contract test verifies the same-surface modifier statically.
- The classified display-toggle flow exercises the real LazyVerticalGrid pinch handoff after the claim-time capture, including column changes through the actual target frame.
- Before pinch assertions, the flow waits for `showInitialProgress == false`; a visible media item alone does not prove that the TEST_HARNESS Morph controller is enabled.

## 2026-08-13 tag integration viewport stabilization

- Card tag integration flows scroll the vertical `clip_list` to the target card and then the card-local horizontal `tag_selector_<clipId>` to the requested tag or group chip before interaction. Classified-card removal explicitly applies its draft before waiting for Room, and the flows stay independent of the device viewport width.
- The card helper waits for the selected screen, list, and target card as Compose nodes directly. The ViewModel clip collection is used only to wait for Room observation; derived classified/unclassified membership is not coupled to the screen-transition wait.
- Root tag/group fixture helpers identify the newly inserted row from the before/after ID difference, so duplicate names are valid fixtures, and finalize the fixture-only creation Undo before navigating away so the bottom notification cannot intercept a tab tap.
- E2E cases that apply tag relations only as setup also finalize that setup slot before changing bottom tabs. The pending-selection case instead waits for the expanded clip collection to reach the ViewModel and verifies the still-dirty Apply control before committing.
- Fixture finalization waits for the asynchronous pending-slot Flow emission before reading and finalizing the slot; it cannot silently miss a newly committed edit.
- Tag-management actions scroll back to their exact row after returning from a card tab, and classified-result assertions scroll the target card into view.
- The long-list pending-draft case scrolls the card's Apply descendant itself into the viewport before clicking, rather than treating partial card visibility as proof that its bottom action is tappable.
- Its unique assertion is that the dirty Apply state survives list replacement and round-trip scrolling; the final database classification uses the same ViewModel entry point, while exact draft IDs through the Apply callback remain covered by the focused card tests.
- Add-all integration flows scroll `tag_list` back to the source tag row after creating later tags, so source-row actions do not depend on the tag-management viewport left by fixture setup.
- Child-tag fixtures wait until the Activity's observed hierarchy contains the inserted ID before opening a group popup, avoiding a race between direct Room fixture insertion and Compose collection.
