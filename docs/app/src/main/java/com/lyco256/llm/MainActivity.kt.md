# `app/src/main/java/com/lyco256/llm/MainActivity.kt`

## 2026-08-12 タグdraftの保存結果通知

未分類・分類済み通常カード・MediaGrid previewへ、`MainViewModel.setClipTags` の完了callback付き経路を渡します。各UIはchip操作ではこの経路を呼ばず、「適用」時だけ呼び、成功または失敗の結果をdraft状態へ反映します。

## 2026-08-12 投稿者の全保存件数

`MainUiState.authorOptions` が全保存クリップから集計した件数を `authorSavedCountByAuthor` として一度だけマップ化し、未分類・分類済み・MediaGridダイアログへ渡します。現在の検索・タグfilter結果では再集計しないため、同じauthor identityには常に同じ全保存件数が表示されます。

## 2026-08 重いローカル処理のTopAppBar表示

`MainViewModel.heavyLocalWorkActive` はRepositoryの重いローカル処理trackerをUIへ公開します。main `TopAppBar` は、このtrackerがactiveで、かつ初回未分類読込・保存先移行・いいね数更新Dialog・MediaGrid初期Progress・tweet読込Dialogなどの専用Progressが表示されていない場合だけ、設定ボタン左に小さい灰色のindeterminate `CircularProgressIndicator` を1つ表示します。MediaGrid処理やnetwork待機だけではtrackerがactiveにならないため、この表示は出ません。

## 2026-08 未分類画面の初回読込状態

`MainViewModel` は投稿一覧Flowの購読開始と初回emissionを区別し、`MainUiState.hasReceivedInitialClipEmission` に保持します。初回emission前の未分類画面では件数と空状態を出さず、中央Progressを表示します。初回emissionが空の場合は通常の0件表示へ移り、その後のDB更新で空になってもloadingへ戻りません。保存先移動中・利用不可は既存の専用画面を優先し、`isInitialClipLoading` はfalseになります。

## 2026-08 共通Undo通知

`MainViewModel` はRepositoryの永続Undo slotを `pendingUndo` として公開し、slot identity付きのUndo・finalize操作をUIへ渡します。`LikeListManagerUi` の最上位 `Box` に `UndoNotificationHost` を置くため、タブや設定画面の切替では通知が失われません。通知自体の表示、5秒timer、Swipe、失敗表示は `UndoNotificationUi.kt` に分離しています。

## 2026-07-10 media-grid selection state

`ClassifiedMediaGridState` exposes tag IDs by clip for the filtered lightweight grid source. `MainViewModel.applyClipTagChanges` sends add/remove pending sets only on Apply; drafts are not sent before Apply. The completion callback closes the editor only on success; failures leave selection and pending state visible.
Updated visible labels: `Xで開く`, `概要設定`, `文字起こし`, `ローカル削除`, `適用`.

`MainViewModel.setClipTags` accepts a completion callback that reports `null` on success or a user-facing error message on failure. This lets tag draft callers mark a draft as applied only after the repository transaction succeeds.

Activity、ViewModel、UI state、Compose画面の接続入口です。未分類、分類済み、タグ管理、本体の設定アイコンから開く全画面の `SettingsScreen` へ状態とイベントを流します。画面本体のタグ階層UIは `TagHierarchyUiV2.kt` に分離されています。

## 主な責務

- `MainActivity`: 通常Compose起動とAppAuthのActivity Result受信。benchmark Intent、snapshot import、計測State、frame/result出力は扱いません。
- `MainViewModel`: RepositoryのFlowをUI stateへ合成し、ユーザー操作をRepositoryへ渡す
- `MainViewModel`: `MediaGridSessionCoordinator`を所有し、分類済みメディアグリッドのframe/controller/anchorをComposableより長く保持する。session keyはfilter/sortだけで、列数・revision変更は同一sessionの更新として扱う。
- `MainUiState`: 未分類、分類済み、検索条件、並び替え、投稿者一覧、タグ階層、保存先状態、同期状態、設定画面用スナップショットをまとめる
- `TweetFilterState`: 分類済み画面の文字列検索、検索モード、検索対象、期間、投稿者条件、タグ条件、タグのみtoggleを表す
- `SettingsScreen`: X API設定、同期、使用量、データ管理、保存先候補、移動開始入口を全画面で表示する
- `StorageMoveEstimateDialog`: 保存先移動の最終確認内容と、移動開始/キャンセル操作を扱う
- `StorageProgressDialog`: 保存先見積もり中、移動開始準備中、移動中などの待機表示を行う

保存先移動中は投稿一覧の代わりに待機画面を表示し、編集や同期を行わせません。SDカード未装着時は投稿一覧と編集・同期を停止し、保存先確認を案内します。

## UI自動テスト

主要画面、設定アイコン、設定画面、空状態には安定したCompose `testTag` を設定しています。表示テキストとtestTagを使って画面遷移をassertし、スクリーンショット比較は行いません。

`SettingsScreen` は隔離テスト環境で本番OAuthを開始しないことに加え、Client IDの保存、trim、消去、ログイン可否の切り替え、同期、使用量、保存先移動をUI操作から検証できるよう、入力欄や各ボタンに `settings_*` のtest tagを付けています。

`StorageMoveEstimateDialog` は保存先移動の最終確認UIを、`StorageProgressDialog` は待機表示を直接renderできるinternal composableです。

投稿カードの三点メニューからは `文字起こし`、`概要設定`、`ローカル削除` を開きます。概要はカード内直書きではなくダイアログで編集します。OCRはカード表示・未分類・分類済み・MediaGrid投稿Dialogの全経路で共通の未保存セッションを使い、保存済み結果がある場合は自動検出せずその文字列をdraftへ表示します。保存済み結果が空の場合だけ自動検出し、再検出は確認Dialogなしで直接開始します。検出結果はasset/path対応を持つ構造化結果としてセッションに入り、保存成功時だけ既存のOCR保存経路を実行して画面を閉じます。保存失敗、dismiss、古い非同期callbackではDBの値や新しいセッションを変更しません。

`SyncResultDialog` は同期成功/失敗の結果表示を直接renderできるinternal composableです。権限不足などのエラーメッセージ表示と閉じる操作をUIテストで固定します。

分類済み画面の並び替えは `ClassifiedSortState` と `sortClipsForDisplay` で扱います。フィルタ後の `ClipWithDetails` 一覧だけを画面表示用に並べ替え、保存順を基準にタグ順・ユーザー件数順・いいね数順・投稿時間順を切り替えます。設定は ViewModel の画面状態にのみ置き、Room や SharedPreferences へ永続化しません。

タグ/グループ移動Dialogの移動先には `move_node_target_root` と `move_node_target_group_<groupId>`、キャンセルには `move_node_cancel` のtest tagを付け、E2Eから表示テキストだけに依存せず移動先選択と閉じる操作を検証できます。

`AddAllTagsDialog` は `TagHierarchy` を受け取り、source tagを除外した階層Treeを縦スクロール表示します。groupは追加先にせず展開/折りたたみにだけ使い、rootまたは深い階層のtagを1件押すと既存の一括追加callbackへ渡します。Treeには `add_all_tree_list`、groupには `add_all_group_<groupId>` / `add_all_expand_group_<groupId>`、追加先タグには `add_all_target_tag_<tagId>`、閉じる操作には `add_all_cancel` のtest tagを付けています。

`CreateNodeDialog` は入力欄、追加、閉じる操作に `create_node_*` のtest tagを付け、作成とキャンセルをE2Eで安定して検証できます。

`RenameNodeDialog` は入力欄、保存、閉じる操作に `rename_node_*` のtest tagを付け、名称変更の保存とキャンセルをE2Eで安定して検証できます。

`ClassifiedSortState` のUIは `sort_open`、`sort_dialog`、`sort_options_list`、`sort_clear_all_open`、`sort_apply` などの test tag で操作します。分類済み画面の概要行には `filterConditionSummary` と並んで現在の並び替え条件も表示します。

メディアグリッドの`LazyGridState`は`MainScreen`でsaveableに保持し、タブ・設定・カード表示でComposableが破棄されてもsession anchorとともに復元します。

## 変更時の確認

UI項目を追加する場合は、対応するViewModel操作、Repository API、Entity/DAOの必要性を確認します。OAuth画面を変える場合はManifestと `XOAuthManager`、保存先画面を変える場合は `PostStorageManager` と安全な実機上書き手順も合わせて確認します。

## 関連ファイル

- `LikeListManagerApp.kt.md`: `AppContainer` の取得口
- `TagHierarchyUiV2.kt.md`: 未分類、分類済み、タグ管理のCompose画面
- `data/ClipRepository.kt.md`: UI操作の業務処理
- `data/Entities.kt.md`: UIで表示・編集するモデル
- `data/ApiSettingsStore.kt.md`: Client IDとログイン状態の保存
- `data/PostStorageManager.kt.md`: 保存先状態、移動見積もり、移動結果
- `data/XOAuthManager.kt.md`: ログインIntentと認証結果交換
- `../../../../AndroidManifest.xml.md`: MainActivityとcallback Activityの宣言

## 2026-07-01 追記: 設定画面/結果DialogのE2E安定化

右上導線は `top_settings_button` から全画面の `settings_screen` を開き、`settings_sync_now`、`settings_like_refresh`、`settings_client_id_*`、`settings_storage_move_*` などのtest tagで設定画面内の操作を安定して検証します。

`SyncResultDialog` は設定画面上の同期操作から表示し、結果の閉じる操作とDB不変をUIテストで検証します。使用量表示は独立Dialogではなく `settings_usage_section` に統合しています。

いいね数更新は `settings_like_refresh` から確認Dialogを開き、`settings_like_refresh_confirm` / `settings_like_refresh_cancel` で再取得の実行・キャンセル導線をE2Eで安定して確認できます。

共通 `ConfirmDialog` は呼び出し側が `dialogTestTag`、`confirmTestTag`、`dismissTestTag` を任意指定でき、文言に依存せず確認/キャンセル操作をE2Eから固定できます。
## 2026-07 追加

- タイトルの未分類件数を `未分類 12件` の形で出すようにした。
- タグ/グループの作成・名称変更ダイアログに色パレットを追加した。
- `Xで開く` はテキストボタンではなく X ロゴのアイコンボタンに変えた。
- 旧カード表示の画像まわりは左右に少し余白を入れた。

## 2026-07-06 Update

- `MediaGrid` の画像カードは左右 `16dp` の余白に広げ、見た目を少し小さくした。
- 画像セルに test tag を付け、Compose テストからカードの幅と並びを測定しやすくした。
- 分類済み画面に view-state の並び替えを追加し、保存順・タグ順・ユーザー件数順・いいね数順・投稿時間順をフィルタ後に切り替えられるようにした。
- 並び替え条件の要約を分類済み画面の概要行へ出し、Dialog の apply / clear / cancel を UI テストで固定した。

## 2026-07-05 Update

- The X logo buttons now preserve the black/white vector colors instead of being tinted by the theme, and the icon is rendered larger so it reads clearly in the button.

## 2026-07 OCR update

- Added OCR search support via `SearchTarget.OcrText`.
- Tweet cards can open the OCR dialog, run OCR, and save `ocrText`/`ocrUpdatedAt` through the ViewModel.
- Local delete now uses the repository hard-delete path, matching the updated repository behavior.

## 2026-07 classified media grid

- `ClassifiedDisplayMode` is saved with `rememberSaveable` in `MainScreen`, so the classified tab keeps card/grid mode across tab switches and activity recreation.
- The classified toolbar now has a single icon toggle next to filter/sort controls; it does not open a dropdown and does not show the text `MediaGrid`.
- Grid mode stays separate from the existing card view and uses the lightweight `classifiedMediaGridState` source described below.
- Classified media-grid support now includes divider headings, like-count overlays, pinch-to-change columns from 2 through 12, card popups, tweet-level multi-select, and bulk tag editing; the existing card-list path remains separate.
- The benchmark-only startup state and frame/result handling live in `app/src/benchmark`; the normal Activity keeps saveable tab/display/column state without build-type mode checks.
## 2026-07 media grid lightweight state

- `MainViewModel` now exposes `classifiedMediaGridState`, which combines the lightweight media source with filters, sort config, and tag hierarchy.
- `filterClipsForSearch` and `sortClipsForDisplay` are shared by the card path and the media-grid path through the common `ClassifiedClipItem` contract.
- `uiState.classified` remains the source for the existing card display.
- The media-grid state tracks both the matching clip count and the rendered media count so the UI can distinguish the zero-clip and no-media empty states.

## 2026-07 media grid headers / overlay follow-up

- `MainActivity` still passes the lightweight media-grid state and the selected `ClassifiedSortState` into `EnhancedClassifiedScreen`; the header grouping and like overlay now live in `TagHierarchyUiV2.kt`.
- The media-grid path continues to stay separate from the card path and does not change DB schema, entity tables, migrations, or DAO queries.

## 2026-07 media grid column count

- `MainScreen` now saves the classified media-grid column count with `rememberSaveable`, so the value survives tab switches and activity recreation.
- `EnhancedClassifiedScreen` receives the saved count, updates it from pinch gestures, and keeps the media-grid anchor cell in view after a column change.
- The current pinch path keeps the normal grid visible, evaluates the final cumulative scale only on release, and never creates the retained Morph session or overlay.

## 2026-07 media-grid tweet dialog

- `MainViewModel` holds the selected media-grid `clipId` and exposes `Closed`, `Loading`, `Loaded`, and `NotFound` dialog states through a clip-scoped repository Flow.
- Selecting a cell opens the existing tweet-card UI in a dialog; closing or destroying the Activity clears the selection so the dialog is not restored automatically.
# メディアグリッド高速化追補

メディアグリッドは`transformLatest`相当のlatest-wins処理で`Calculating`から`Ready`へ遷移する。Calculating中は旧セルを描画せずProgressを表示し、Ready後は静的グラデーション枠を描画して次フレーム以降に画像要求を開始する。
