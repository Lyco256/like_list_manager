# `TagHierarchyUiV2.kt`

## 第14実装

セルはcontrollerが選んだraw→JPEG→local→URL候補を使います。`downloadState == "failed"`でも利用可能候補を抑止しません。pack open、mapping、metadata検査、raw生成はComposableで行いません。

2026-07-17: The classified media grid publishes a lightweight viewport snapshot without calling synchronous thumbnail viewport application. Each cell carries its immutable source index for the worker-side source lookup.

## 第7実装: keyed frame and background image preparation

- The classified media grid creates `MediaGridFrameData` on `Dispatchers.Default`. It contains headers, stable keys, item lookup, source indexes, and the media-cell index column.
- `MediaGridRenderKey` combines the current data key (source revision, hierarchy/filter, and effective sort) with the column count. A key mismatch clears the previous frame and shows `classified_media_grid_progress`; the old grid is never retained while a new frame is being built.
- Once frame data is ready, the grid and cell-local Placeholder render immediately. Image metadata is prepared independently by the single `MediaGridImagePreparer`, so frame publication does not wait for file checks or Coil candidates.
- A nullable initial `ClassifiedMediaGridState.dataKey` is resolved to the current UI key for both frame calculation and publication. The completed frame is discarded only when that resolved key is stale.
- Viewport collection uses only the visible item set/order and cell size. It reads adjacent cells from the prebuilt media-cell index column; it does not build candidate lists, inspect files, hash sources, or filter/sort all items. Direction reversal uses `collectLatest` cancellation to discard old preparation.

## 2026-07-19 direct preview pipeline

- The current grid publishes only a lightweight viewport read and never waits for generator state or cache restoration.
- `MediaGridImagePreparer` builds the available persistent JPEG → local → preview → remote → non-duplicate display candidates off composition, and each `ClassifiedMediaGridCell` starts `AsyncImage` from the prepared candidate list. Missing local files are omitted; `downloadState == "failed"` does not suppress URL fallback.
- The measured cell constraints supply the request target size while `ContentScale.Crop` remains unchanged. A stable source-identity-and-size key is applied to both memory and disk cache requests.
- Visible requests continue during drag and fling. Composition disposal cancels requests for cells leaving the viewport. The only separate work is targetless Coil prefetch for at most one adjacent row in the current direction.

## 第9実装: persistent preview display and preload

- The preparer adds a valid `filesDir/media_grid_previews/v1/<assetId>.jpg` before local → preview → remote → display. File stat and 256×256 JPEG metadata checks remain in the background preparer; the Composable and viewport path do not inspect files.
- The first non-empty layout preloads only visible persistent JPEGs. Before layout is available, the fallback target is limited to `columnCount * 6` media indexes from `firstVisibleItemIndex`. Subsequent viewport updates preload only the next row in the current direction, limited to `columnCount` valid persistent JPEGs.
- `MediaGridPreviewPreloader` deduplicates keys across preload requests and memory-cache hits, cancels obsolete requests on viewport/direction changes, and uses the same 256×256 memory key as the cell request. Persistent JPEG requests disable Coil disk cache; existing candidates retain their cache policy.
- Worker publication and preview deletion emit a process-local asset ID. Only a currently visible matching cell invalidates and re-prepares; off-screen notifications are deferred until normal preparation. A persistent-JPEG decode error advances once to the existing fallback candidates and schedules best-effort deletion/re-generation once per preview identity without changing DB state.

## 廃止済みviewport経路

実装6より前の画像生成用viewport経路は削除済みです。現行仕様は冒頭のdirect preview節だけを参照します。

## 2026-07-10 media-grid bulk tag editing

## 2026-07-12 mixed tags and header requirements

- Bulk tag states are aggregated as `NONE`, `ALL`, or `MIXED` per selected clip.
- `KEEP` tags are omitted from updates; `ADD_ALL` and `REMOVE_ALL` are sent together through the repository transaction.
- MIXED cycles `KEEP -> REMOVE_ALL -> ADD_ALL -> REMOVE_ALL`; untouched MIXED tags preserve each clip's original state.
- Apply confirms the selected tweet count and only all-add/all-remove tag names. Cancel and successful Apply preserve media-grid selection.
- Like buckets use 200/500/1000 units for 2-4/5-8/9-12 columns. Week headers use Monday-Sunday `yyyy/MM/dd ~ yyyy/MM/dd`.

- A long press starts tweet-level multi-selection. Every media cell with the same `clipId` shares the selected state.
- The selection toolbar reports distinct tweet count and selects all media-bearing tweets in the current filtered result, including off-screen cells.
- Selection indicators and video badges scale from 2 through 12 columns. Like-count overlays are hidden during selection.
- A per-cell card button is available during selection only at 2 through 6 columns. Normal cell taps continue to open the tweet dialog at every column count.
- Bulk tag editing starts from the union of tags on selected tweets. Draft changes remain local until Apply; cancelling a changed draft requires discard confirmation.
- `MainScreen` owns the saveable media-grid `LazyGridState`; `MediaGridSessionCoordinator` owns frame/controller/load-state/anchor lifetime. `EnhancedClassifiedScreen` receives these objects and does not dispose the controller when the screen leaves composition.
- Selection indicators use 28/24/18/14dp at 2–3/4–6/7–9/10–12 columns; video icons use 24/20/14/10dp. The card-dialog button uses 28dp at 2–3 columns and 24dp at 4–6 columns, with testTag `media_grid_selection_open_<assetId>`.
- A failed bulk apply keeps the editor, selected clips, and pending draft open and displays `media_grid_bulk_tag_error`; only a successful completion closes the editor.
Updated visible labels: `Xで開く`, `概要設定`, `文字起こし`, `いいね数`, `取得日時`, `取得エラー`, `タグを付ける`.

投稿一覧のLazyColumnには、未構築項目へ実機UIテストから安全にスクロールできる `clip_list` test tagがあります。

絞り込みbutton、Dialog、タグのみswitch、検索欄、適用buttonには安定したtest tagを付け、複合条件と破棄動作をスクリーンショットなしで検証できます。

## 対応ソース

`app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt`

## 役割

未分類、分類済み、タグ管理のCompose UIを分離した画面実装です。`MainActivity.kt`から受け取った状態とRepository操作を使い、タグ階層の選択、絞り込み、タグリストのドラッグ&ドロップを描画します。

## 主要な定義、設定、処理

- `EnhancedClipListScreen`: 未分類投稿のカード一覧と、分類確定までの一時タグ選択、カード右下の分類ボタンを扱います。
- `EnhancedClassifiedScreen`: 一致件数と条件文、右側固定の絞り込み/クリア操作、全画面Dialogの検索/絞り込みパネル、並び替えDialog、投稿の再割り当てを扱います。タグのみOFFでは未分類投稿も表示対象に含めます。
- `EnhancedTagListScreen`: タグ/グループの追加、名称変更、移動、削除、別タグへの一括追加、ドラッグ&ドロップ移動を扱います。
- `EnhancedTweetCard`: 投稿本文、画像、概要の読み取り表示、タグ選択、投稿オプションメニューをまとめます。
- `SearchFilterDialog`: タグのみ、文字列検索、期間、ユーザー、タグ条件を区分し、確定条件と分離した下書きとリアルタイム一致件数を扱います。期間DatePickerは未指定時に今日を初期選択し、日付クリア操作は開始・終了指定の次行へ固定します。画面下部には適用/キャンセル、変更破棄・全条件クリアの確認Dialogを持ちます。
- `AuthorFilterDialog`: 保存済み投稿者から生成したユーザー一覧を検索し、複数ユーザーOR条件を選択して「決定」で閉じます。選択数は入口ボタンの外に表示し、入口の文言は常に「ユーザーを選択」です。
- `withoutTrailingMediaUrl`: UI表示時だけ、メディア付き投稿の本文末尾に付く `https://t.co/...` を取り除きます。DB保存値、検索対象、本文途中のURLは変更しません。
- `PreserveScrollAnchor` / `LazyListScrollbar` / `ScrollToTopButton`: 未分類、分類済み、タグ管理のスクロール位置維持、常に薄い表示専用スクロールバー、白丸黒矢印の一番上へ移動ボタンを扱います。
- 各画面内ではTopAppBarと重複する画面名見出しを表示しません。
- `TagHierarchySelector` / `TagSelectionDialog`: 投稿カード内のタグ選択を、コンパクトな最上位チップと半画面Dialogの単一階層ナビゲーションで扱います。
- `TagFilterSummaryRow` / `filterConditionSummary`: 分類済み画面の一致件数と現在条件を、小さい文字と灰色背景の省スペースな横スクロール領域に表示します。右側には余白を抑えたフィルターアイコン、並び替えアイコン、クリアボタンを固定します。
- `SortConfigDialog`: 分類済み画面の表示順を切り替える全画面Dialogです。タグ順とユーザー件数順は個別にON/OFFでき、優先順も選べます。いいね数順と投稿時間順は昇順/降順を切り替えられます。クリアはダイアログ内の下書きを初期化し、適用で `ClassifiedSortState` を ViewModel に反映します。設定は画面状態のみで保持し、永続化しません。
- `TagManagementRow`: タグリストの行表示、グループの展開、操作メニュー、ドラッグ開始を扱います。
- `TagListItem` / `DragState`: ドラッグ中の表示リストを通常行とplaceholderへ分け、掴んだnodeと表示中子孫をLazyColumn本体から除外します。placeholderのindexは「drag中nodeを除外した移動先兄弟リスト上の挿入位置」です。
- `TagManagementRow` のdrag placeholder表示 / `TagDragPreview`: 挿入候補位置に同じ高さのplaceholderを表示し、overlayは縦方向だけ指に追従します。overlayの横位置と横幅はドラッグ開始時の行位置に固定します。
- ドラッグgestureは個別行ではなくタグリストを包む親Boxの `pointerInput` で受けます。長押し開始時にroot座標で行をhit-testし、LazyColumnのplaceholder移動や再composeでgesture coroutineが破棄されないようにします。
- placeholder移動は前回drag中心と今回drag中心が隣接行の中心線を跨いだ場合だけ行い、範囲外では現在親の先頭または末尾slotを候補として維持します。
- グループ内dropと前後slot判定も指位置ではなくdrag中オブジェクトの中心Yを基準にします。これにより、overlayの見た目と保存されるdrop先のズレを抑えます。
- placeholderは背景色や角丸を持たない完全な余白として描画します。グループ内drop候補中も直前の並び替え候補slotに余白を残し、対象グループ行のハイライトで保存先がグループ内になることを示します。
- `DragState` は保存先の `targetParentId` / `placeholderIndex` と、表示用余白の `visualParentId` / `visualPlaceholderIndex` を分けて持ちます。グループ内dropへ入っても表示用余白を消さず、前後の中心線を越えたときだけ表示用余白を移動します。
- drop直後はDB Flowの反映まで最後のplaceholder配置を短時間維持し、変更前順序へ一瞬戻って見える揺れを抑えます。
- auto-scroll loopからの静止中心線判定は、実際にスクロール量が消費された場合だけ許可します。上端・下端へ到達済みの状態で、中心線判定が連続して進みすぎないようにします。
- auto-scroll量の計算では、LazyColumnが該当方向へスクロール可能かを見ます。
- LazyColumn itemには `Modifier.animateItem()` を付け、placeholder移動時にドラッグ中でない行が急に瞬間移動して見えないようにします。
- `EnhancedMediaGrid`: 投稿内画像の表示を扱います。1枚画像はDB上のwidth/heightからアスペクト比を維持し、複数画像はX風グリッドとして切り取り表示を許容します。保存済みの `photo` だけをタップ可能にし、動画/GIFサムネイルや未保存画像は全画面表示対象にしません。
- `FullScreenImageViewer`: 保存済みPhotoを黒背景の全画面Dialogで表示します。上部固定行に閉じるボタンと現在位置だけを表示し、左右スワイプで同一投稿内のPhotoを切り替え、上下ドラッグまたは戻る操作で閉じます。
- `TagNodeRef.saveableKey`: `LazyRow`、`LazyColumn`、`LazyVerticalGrid`のkeyをBundle保存可能な文字列へ変換し、実機でのCompose保存状態エラーを防ぎます。
- ドラッグ中はリスト範囲外へ出ても状態を維持し、auto-scroll loopで端方向へ継続スクロールします。スクロール中もplaceholder位置を更新します。
- グループ中央領域へ乗っている場合だけグループ内drop候補にし、グループ自身や子孫へのdropは保存しません。ドラッグ中にグループを自動展開しません。

## 関連ファイル

- `MainActivity.kt.md`: 画面の状態とRepository呼び出しを渡す入口です。
- `data/ClipRepository.kt.md`: タグ移動、並び替え、タグ再割り当ての業務処理を実行します。
- `data/Entities.kt.md`: タグ階層やフィルター状態のモデルを定義します。

## 変更時の確認事項

タグ階層UIを変えるときは、投稿カードの一時状態、分類済み画面の即時保存、検索条件の一致件数、タグリストの移動制約、Repositoryのslot移動APIと合わせて確認します。画像表示を変えるときは、保存済みPhotoだけが全画面表示対象になること、動画/GIFサムネイルや未保存画像がタップ不可であること、左右スワイプと上下ドラッグ終了が競合しないことを確認します。ドラッグ変更では範囲外drag、auto-scroll、placeholder、グループ内drop、drop後の順序維持を確認します。スクロールバーは表示専用で、タグ管理のdrag gestureと競合せず、スクロール中も強調表示されないことを確認します。

## 件数表示改善（2026-06-20）

- 投稿カードは取得済みいいね数を表示し、1万以上を切り捨ての万表記にします。投稿後7日以内の取得値には警告を付け、タップで正確な件数・取得日時・警告または失敗理由を表示します。
- 投稿者選択は現行順／全件件数順をDialog内で切り替え、表示専用スクロールバーを備えます。
- 絞り込みではタグの投稿登録件数、グループの直下要素数を表示します。タグ管理の件数は背景Badgeを使わない薄い数字表示です。

## UI自動テスト

投稿カード、投稿者クリック領域、いいね数表示、分類確定、タグchip、タグ管理row、操作menu、名称変更menu、移動menu、削除menu、一括追加menu、group展開、root追加button、タグ/グループ削除DialogにはIDを含む安定した `testTag` を付けています。Compose E2Eは表示テキストだけに依存せず、操作後のRoom状態もassertします。タグ管理では、operation menuからのタグ名称変更、グループ名称変更、名称変更Dialogキャンセル、タグの別グループ移動、タグ/グループ移動Dialogキャンセル、別タグへの一括追加Dialogキャンセル、タグ/グループ削除DialogのキャンセルをRoom状態で確認します。
検索/絞り込みDialogには、日付条件、投稿者条件、タグ条件、キャンセル、変更破棄、Dialog内全クリア確認を実機E2Eから安定して操作するため、`filter_options_list`、`filter_start_date`、`filter_end_date`、`filter_date_clear`、`filter_date_picker_apply`、`filter_date_picker_clear`、`filter_author_open`、`filter_author_option_<authorId>_<username>`、`filter_author_confirm`、`filter_author_clear`、`filter_tag_condition_<type>_<id>`、`filter_tag_clear`、`filter_cancel`、`filter_discard_*`、`filter_clear_all_*` を付けています。
並び替えDialogには、`sort_open`、`sort_dialog`、`sort_options_list`、`sort_clear_all_open`、`sort_tag_toggle`、`sort_user_toggle`、`sort_base_like`、`sort_base_date`、`sort_apply`、`sort_cancel` などを付け、分類済み画面の表示順をUIテストから安定して切り替えられるようにしています。
メディアグリッドと全画面画像viewerには、保存済みPhotoのタップと閉じる操作をスクリーンショットなしで検証するため、`media_asset_<assetId>`、`image_viewer`、`image_viewer_close`、`image_viewer_position`、`image_viewer_photo_<index>` を付けています。

投稿カードのローカル削除導線には `clip_local_delete_open_<clipId>`、確認Dialogには `clip_local_delete_dialog_<clipId>`、実行/キャンセルには `clip_local_delete_confirm_<clipId>` / `clip_local_delete_cancel_<clipId>` を付け、E2Eで文言ではなく対象clip IDに紐づけて操作できます。

一覧の先頭へ戻るFloatingActionButtonには `scroll_to_top` を付け、検索条件やタグ選択状態がスクロール後も維持されることをE2Eで確認できます。
## 2026-07 追加

- タグ/グループの chip を `colorId` ベースのグラデーション表示にした。
- タグ一覧の行は、追加・編集・削除を常時見えるアイコンボタンに整理した。
- 画像グリッドと単体画像ビューは左右の余白を少し広げた。
- X で開くボタンは X ロゴのアイコンボタンにした。

## 2026-07-04 Update

- The group-add action now opens a `DropdownMenu` from the plus button.
- Tag rows now use a bubble-style icon for the bulk-add action.
- `EnhancedTagListScreen` keeps the `tag_list` test tag for scroll-targeted tests.

## 2026-07-05 Update

- The group-add menu continues to use a Google Material icon, and the bulk-add action uses an outlined chat-bubble style icon from the existing icon set.

## 2026-07-06 Update

- `EnhancedMediaGrid` now uses wider `16dp` side padding so the in-card image grid reads slightly smaller and matches the main feed image grid.
- 分類済み画面に view-state の並び替えを追加し、保存順・タグ順・ユーザー件数順・いいね数順・投稿時間順をフィルタ後に切り替えられるようにした。
- 並び替え条件の要約を分類済み画面の概要行へ出し、Dialog の apply / clear / cancel を UI テストで固定した。

## 2026-07 OCR update

- Enhanced tweet cards now share the same OCR menu, summary dialog, and save flow as the main list.
- OCR recognition only runs when requested from the menu, and reopening OCR with existing text reuses the saved text until `再検出` is confirmed.
- The tweet options menu exposes `tweet_options_ocr`, `tweet_options_summary`, and `tweet_options_local_delete`, and the OCR redetect confirmation uses `ocr_redetect_warning_dialog`.
- The like-count popup now carries `clip_like_popup_<id>` so instrumentation tests can wait for the popup itself instead of only waiting on its text.
## 2026-07 classified media grid

- `EnhancedClassifiedScreen` now switches between the existing card list and a new media-grid mode with a single icon toggle.
- `buildMediaGridEntries(clips)` flattens `uiState.classified` into `MediaGridEntry` rows so the grid can stay independent from direct `ClipWithDetails` nesting in the UI.
- Only `photo` and `video_thumbnail` assets are included, and the grid keeps `displayUrl = localPath ?: previewUrl ?: remoteUrl`.
- `classified_media_grid` uses `LazyVerticalGrid` with `GridCells.Fixed(columnCount)`, where `columnCount` is maintained from 2 through 12 by pinch gestures, with zero item spacing. Production classification-grid overscroll is explicitly disabled by a grid-local `CompositionLocalProvider(LocalOverscrollConfiguration provides null)` because the current Compose BOM does not expose the newer `overscrollEffect` LazyGrid parameter; no other scroll region inherits this setting.
- `TagFilterSummaryRow` and `MediaGridSelectionToolbar` use the same positive `zIndex`, higher than the grid, so their buttons remain in front of resident images. The grid itself remains the normal single `LazyVerticalGrid`; resident image clipping is handled by `MediaGridResidentCanvas`.
- Failed downloads, missing local files, and assets with no usable URL render as error cells via `media_grid_error_<assetId>`.
- The later follow-up adds headings and pinch-to-change columns while preserving the existing grid-mode filter and sort dialogs.
## 2026-07 media grid lightweight UI

- `EnhancedClassifiedScreen` now reads `ClassifiedMediaGridState.entries` instead of flattening `uiState.classified` directly.
- The grid still keeps the existing toggle, empty states, video thumbnail badge behavior, error-cell behavior, and `testTag` usage.
- `buildMediaGridEntries` now accepts the lightweight media-grid source and continues to emit one grid cell per asset.
- The card view still renders the existing `uiState.classified` list without sharing the grid entry expansion step.

## 2026-07 media grid section headers / like overlay

- `buildClassifiedMediaGridItems(entries, sort, columnCount)` inserts full-width header items only for `ClassifiedSortBase.PostTime` and `ClassifiedSortBase.LikeCount`.
- `columnCount` now controls bucket granularity: `2..4` uses day / 1000, `5..8` uses week / 5000, and `9..12` uses month / 10000.
- Unknown buckets are labeled `日付不明` and `いいね数不明`, and `likeCount >= 100000` collapses into `10万以上`.
- `ClassifiedMediaGridHeader` uses `GridItemSpan(maxLineSpan)` and `media_grid_header_*` / `media_grid_header_text_*` test tags.
- `ClassifiedMediaGridCell` shows `media_grid_like_count_<assetId>` only when `sort.baseOrder == ClassifiedSortBase.LikeCount` and the cell has a non-null like count.
- The card view and the existing lightweight media source stay separate.

## 2026-07-19 media-grid placeholder rendering

- The retired grid-wide brush, generator dispatcher, coordinator, and source model have been removed.
- The current cell-local placeholder behavior and direct candidate state are documented in the direct preview section above and `MediaGridPlaceholderRendering.kt.md`.
- `Failed`, unavailable media sources, and `downloadState == "failed"` render the theme's opaque single-color cell background and the existing error icon without a gradient. Image and error cells do not compose the placeholder layer. No shimmer, crossfade, infinite animation, border, spacing, or `SubcomposeAsyncImage` is used.
- Placeholder layers expose `media_grid_placeholder_<assetId>` only while active; existing cell, error, selection, badge, dialog, and pointer-input tags remain unchanged.

## 2026-07-14 final media-grid morph adjustment（履歴: 現行経路では不使用）

- Morph Header bands use one maximum-height node, clipped while Y and height interpolate from the start layout to the end layout. Changed titles crossfade with `1-progress` and `progress`; identical titles use one layer.
- The historical morph overlay drew only bounded Slot/Header backgrounds and used stable keys. This overlay and its image-source model are absent from the current product path.
- The historical overlay observation was deduplicated by Asset ID and did not start image generation, network work, or file checks. The overlay is absent from the current product path.
- Video, selection, card, and like-count badges use the slot width and the same Asset alpha as their image. `MediaGridMorphUiState` atomically manages the transaction, anchor, and handoff completion so correction is not cleared while the overlay is still visible.

## 2026-07 media grid pinch / anchor follow-up（履歴: 現行経路では不使用）

- `EnhancedClassifiedScreen` now keeps a saved media-grid column count in `MainScreen` and updates it from pinch gestures.
- Pinch-in increases the column count and pinch-out decreases it within the `2..12` range.
- When the column count changes, the grid restores the nearest visible media-cell anchor instead of jumping back to the top.

## 2026-07-14 media-grid structure stabilization（履歴: 現行経路では不使用）

- The final Header/Media item sequence is prepared on `Dispatchers.Default`; a new sequence replaces the old one only after it is complete, so a column change never exposes an empty grid.
- A stopped viewport prepares bounded `+1` and `-1` Morph candidates. Scrolling does not update candidates, and a pinch is ignored while the LazyGrid is still scrolling.
- Morph transaction state is separate from `MediaGridMorphOverlayMotion.progress`. Pointer movement updates only the stable motion holder read by overlay graphics layers; the parent, LazyGrid items, RenderModel, maps, and thumbnail requests are not rebuilt per pointer update.
- The RenderModel is remembered by the immutable plan only and resolves entries by stable `asset:<assetId>` keys. Overlay composition is withheld until all planned visual keys are present.
- Normal Media cells and headers have no placement/appearance animation. Placeholder gradients are static and cell-local; each cell caches its own size-dependent Brush only while its Placeholder state is active.
- Normal anchor restoration runs only in `Idle`; Morph handoff alone performs the anchor `scrollToItem` and one required `scrollBy`, then hides the overlay and completes in one state transition.

## 2026-07 continuous media-grid morph foundation（履歴: 現行経路では不使用）

- The media-grid detector remains `pointerInput(Unit)` and uses `rememberUpdatedState` for the latest column count, items, source revision, state, and callbacks.
- After the two-finger dead zone, one `MediaGridMorphSession` and one bounded from/to plan are created. During tracking the existing `LazyVerticalGrid` continues to use the from column count.
- Progress changes are state-only. The real column count callback is invoked once after `SettlingToTarget`; `SettlingToCurrent` returns to the current count without a callback.
- Source revision, item, sort, or display-mode changes cancel the old plan. Thumbnail viewport/source update structure remains outside progress updates.

## 2026-07 media-grid tweet dialog

- Media cells use tap detection without merging child semantics, so existing video, like-count, error, and cell tags remain available.
- A normal cell tap opens `MediaGridTweetDialog` for the cell's `clipId`; pinch gestures remain handled by the grid resize detector.
- The dialog reuses `EnhancedTweetCard`, keeps its existing author/X/image-viewer/tag/OCR/summary/delete actions, and exposes `media_grid_tweet_dialog`, `media_grid_tweet_dialog_close`, `media_grid_tweet_dialog_loading`, and `media_grid_tweet_dialog_error`.
- Author navigation and local deletion close the dialog before invoking their existing callbacks. Dialog close preserves the grid state because the grid LazyGridState remains owned by the classified screen.

## 2026-07 media-grid selection interaction fixes

## 廃止済み画像cache経路

実装6より前の独自生成cacheとviewport優先処理は、互換APIを残さず削除済みです。現在の256×256 JPEG仕様は`MediaGridPersistentPreviewStore.kt.md`、候補順とpreloadは`MediaGridDirectPreview.kt.md`だけを参照します。端末に残る廃止済みcacheへアクセスまたはcleanupするコードはありません。

- Media-grid selection mode is tracked independently from the selected clip set, so the toolbar remains visible at `0件選択中` until the close button or Android Back is used.
- The bulk tag button is disabled when no clip is selected. Cell taps in selection mode only toggle the clip; the card-dialog button is available only for 2–6 columns and does not propagate to the cell.
- The image itself is never overlaid or dimmed by selection. Selection is represented only by the top-left indicator: a white outer ring, a light-blue checkbox with a black check for single-asset clips, or a blue checkbox with a white check shared by all cells of a multi-asset clip.
- A single long-press that starts selection mode emits one `LongPress` haptic feedback; later selection changes, deselection, bulk selection, pinch, dialog, and mode close do not emit additional feedback.
- The card-dialog action keeps an appropriately small rounded-square surface inside its touch target for 2–6 columns and remains absent for 7–12 columns.

## Current simple column-change path

## 2026-07-22 steady image loading

- The session owns one `MediaGridSteadyLoadController` for the filter/sort session. Its `snapshotFlow` only overwrites a latest-value viewport anchor containing stable item bounds, visible media indices, measured viewport/cell size, and column count. The controller pauses while the session is hidden and resumes without startup Progress.
- A new session remains composed behind the full-area `classified_media_grid_progress` overlay while initial metadata and warm-up run. Column changes, source refreshes, and session reattach keep the published frame visible and never restore the overlay.
- Cells render Placeholder for `Pending` and `Loading`, the existing Error design for terminal failure, and an `AsyncImage` only for a controller-published `Ready` candidate already confirmed in the shared memory cache. Candidate fallback and recovery are controller-owned.
- Selection, tap, long press, badges, tweet dialog, headers, filtering, sorting, and pinch column changes retain their existing paths.

- The production grid keeps one normal `LazyVerticalGrid` visible during the entire two-pointer gesture. It does not render or update a morph overlay, motion progress, settle animation, or grid handoff.
- `mediaGridColumnCountAfterPinchRelease` uses the final accumulated distance ratio only when the gesture ends. A threshold miss, cancellation, source revision change, or 2/12 boundary leaves the count unchanged; a successful gesture changes exactly one adjacent column.
- Pinch-in increases columns and pinch-out decreases columns. Direction reversal is resolved from the final cumulative ratio rather than from an early locked direction.
- The pinch-start anchor prefers the visible media item below the pinch center and otherwise the nearest visible media item. The stable item key and relative center offset are restored after the normal grid rebuild, with finite layout retries and no retained overlay state.
- `MediaGridMorphSession` and the former `MediaGridMorphOverlay` path are not part of the current product source path.

## 2026-07-18 第2実装 viewport通知（廃止済み）

旧通知方式の詳細はGit履歴だけに残します。現行は安定した表示index・asset ID・cell sizeを使うprepared imageと、隣接1行のtargetless Coil preloadです。
# メディアグリッド高速化追補

グリッドのメタデータ処理はカード表示と分離し、ファイル存在確認・画像デコードを枠生成前に行わない。複数選択はCalculating中に解除せず、Ready結果で選択可能Clipとの交差を更新する。

## 2026-07-19 第4実装 viewport監視（廃止済み）

旧操作状態と生成停止方式の詳細はGit履歴だけに残します。現行の直接表示とpreloadの証跡は、冒頭のdirect preview節と`MediaGridDirectPreviewTest`にあります。
# `TagHierarchyUiV2.kt`

## 2026-07-29 viewport signature and shared ordinal index

- `buildMediaGridFrameData()`はframe itemの一回の走査でitem map、media ordinal配列、headerを`-1`とする逆引き配列、asset ID mapを構築し、`MediaGridFrameData.ordinalIndex`としてUI/controllerへ共有する。
- viewport `snapshotFlow`は`visibleItemsInfo`を一回直接走査し、item indexから`mediaOrdinalByItemIndex`を引いてprimitive境界を作る。pixel offsetはsignatureに含めず、visible境界・header境界・viewport寸法・cell size・列数・render keyの変更だけを通知する。
- 既存のresident Canvas、scroll anchor checkpoint、pointer input、列数変更、header/card/selection UIは変更しない。

## 2026-07-29 idle anchor persistence

- Classified media-grid anchor persistence is checkpoint-based. It observes only `isScrollInProgress` transitions and captures once on a real `true -> false`; the initial `false`, active scrolling, and restoration/handoff intermediate states are not saved.
- Explicit checkpoints cover `ON_STOP`, grid disposal/session replacement, and completed column handoff. Restoration completion also captures the final valid layout once.
- Capture directly scans visible items once and accepts only keys in `MediaGridFrameData.assetIdByItemKey`, preserving header exclusion and center/fallback selection without collection or per-item `Offset` allocations.
- The viewport `snapshotFlow` and all controller/scheduler/resident Canvas/pointer-input behavior remain unchanged.
