# `UiStateRenderingTest.kt`

## 2026-08-13 integration semantics stabilization

- MediaGrid preview verifies the active loading, error, and loaded state together with the shared dialog root, scrim, and outside close control after each recomposition. Internal scroll children are not used as evidence for the state-independent outer layer.
- The scrim dismissal is injected at a coordinate outside the card instead of clicking the scrim node center, which is covered by the card by design.
- The unclassified and classified list-card surfaces are composed independently, derive the tail from `TagHierarchy.children(null)` (the actual sorted display order), scroll the `LazyRow` to that index, and then verify that the Apply button stays fixed and no callback runs during scrolling. The classified fixture starts with one persisted tag because the production default filter intentionally excludes untagged clips.
- Child controls inside the platform Dialog are queried from the unmerged semantics tree, matching the list-card and classified-card checks.
- Each list surface owns its own draft fixture, so the geometry assertion does not depend on replacing one root composition with another.
- MediaGrid preview Apply/draft/callback behavior is covered by `mediaGridTweetDialogReusesCardActionsAndClosesBeforeLocalDelete`, `closingMediaGridPreviewDiscardsAnUnappliedTagDraft`, and `mediaGridPreviewApplyKeepsDialogOpenForTheSameTagChange`; it is not duplicated through a platform-window transition in the list geometry test.

## 2026-08-13 一括追加先Tree

- source tagが候補から除外され、root tagを1回選ぶとcallbackが1回だけ呼ばれてDialogが閉じることを検証します。
- group本体tapでは追加せず階層を展開し、深い階層のtagを選択できることを検証します。
- 60件のroot tagを含むfixtureでTree末尾までscrollして最後のtagを選べることと、Cancelでは選択callbackを呼ばないことを検証します。

## 2026-08-13 絞り込み状態の丸点凡例

- `含む`、`必須`、`排除` の3つの丸点とlabelが存在し、丸点の描画色が共通の `tagFilterColors` mappingと一致することを検証します。
- 84dp幅でも凡例が折り返し、全labelが凡例bounds内に表示され、旧色名説明文字列が存在しないことを検証します。分類済みの絞り込みDialogでは、凡例が選択済み条件の横スクロールRowより上に配置され、グループの制約説明文は表示しません。

## 2026-08-12 絞り込みの選択済みタグ条件Row

- 多数の選択済タグ・グループ条件が一段の横スクロール一覧になり、末尾条件まで到達できることを検証します。
- 同名タグがbreadcrumbで区別され、タグとグループの本体タップで条件が消えず、独立した削除ボタンでのみ消えることを検証します。

## 2026-08-12 MediaGrid preview overlay

MediaGrid previewにカード内titleがないこと、閉じるボタンがカード外右上にあることをsemanticsの存在・状態とboundsで検証します。カード内tapでは閉じず、scrim、閉じるボタン、Android Backで閉じること、タグchipと画像viewerの操作が外側dismissへ誤伝播しないことも確認します。Dialogの別windowと実機viewportの差に依存しないよう、loading/errorは画面全体との交差ではなくsemantics stateの構成を固定します。

## 2026-08-12 カードのタグ横スクロールと固定適用ボタン

未分類・分類済み・MediaGrid preview の全カード経路で、タグselectorが横スクロールsemanticsだけを持つこと、未構成の末尾タグがindex scroll後に構成されることを検証します。複数タグを末尾まで横スクロールしても `適用` ボタンの座標が変わらず、スクロール操作だけでは適用callbackが呼ばれないことを、実機viewportとの交差に依存せず確認します。あわせてdirty=false/trueのdisabled/enabled切替、ボタンtap、タグなし時の空状態とdisabled表示を確認します。

## 2026-08-12 タグdraft／適用UI

未分類カードは変更前disabled、変更後enabled、元へ戻すとdisabledになることを検証します。分類済み通常カードとMediaGrid previewではchip tapだけでは保存callbackを呼ばず、「適用」で初めて選択集合を渡すことを検証します。分類済みカードはApply失敗後にdraftと再試行可能なbuttonを保持します。previewは適用後も開いたままで、未適用draftを閉じた場合はcallbackを呼びません。

## 2026-08 重いローカル処理のTopAppBar表示

- tracker activeかつ専用Progressなしの場合だけ `top_heavy_work_indicator` が1個表示され、inactiveでは消えることを確認します。
- 未分類初回中央Progress、MediaGrid初期Progress、Dialog等をまとめた専用Progress可視状態では、TopAppBar表示判定がfalseになることを確認します。
- indicatorの実装は `onSurfaceVariant`、18dp、2dp strokeに固定し、設定buttonの直前に配置しています。

## 2026-08-01 async error-state assertion

- `classifiedMediaGridShowsDateHeadersVideoBadgeAndErrorCells` waits for both terminal error tags before asserting them. The missing-local/remote candidate path is asynchronous and must not be asserted synchronously after `setContent`.

## 2026-08-13 classified toolbar state coverage

- default、filter適用、sort変更、sortのdefault復帰を順にrenderし、filter/sortの適用状態semanticsが独立して切り替わることを確認します。
- CardからMediaGridへ表示を切り替えてもdisplay buttonの背景色が変わらず、toolbar用の`filter_clear`が存在しないことを確認します。

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

## 2026-08-19 media-grid current-position pill

- The large-dataset header regression verifies that the initial grid has no current-position pill and that a real grid scroll shows `media_grid_position_pill` and its label without changing the existing header/column assertions.
