# `app/src/main/java/com/lyco256/llm/MainActivity.kt`

Activity、ViewModel、UI state、Compose画面の接続入口です。未分類、分類済み、タグ管理、本体の設定アイコンから開く全画面の `SettingsScreen` へ状態とイベントを流します。画面本体のタグ階層UIは `TagHierarchyUiV2.kt` に分離されています。

## 主な責務

- `MainActivity`: Compose起動とAppAuthのActivity Result受信
- `MainViewModel`: RepositoryのFlowをUI stateへ合成し、ユーザー操作をRepositoryへ渡す
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

`SyncResultDialog` は同期成功/失敗の結果表示を直接renderできるinternal composableです。権限不足などのエラーメッセージ表示と閉じる操作をUIテストで固定します。

分類済み画面の並び替えは `ClassifiedSortState` と `sortClipsForDisplay` で扱います。フィルタ後の `ClipWithDetails` 一覧だけを画面表示用に並べ替え、保存順を基準にタグ順・ユーザー件数順・いいね数順・投稿時間順を切り替えます。設定は ViewModel の画面状態にのみ置き、Room や SharedPreferences へ永続化しません。

タグ/グループ移動Dialogの移動先には `move_node_target_root` と `move_node_target_group_<groupId>`、キャンセルには `move_node_cancel` のtest tagを付け、E2Eから表示テキストだけに依存せず移動先選択と閉じる操作を検証できます。

`AddAllTagsDialog` は一括追加先タグに `add_all_target_tag_<tagId>`、閉じる操作に `add_all_cancel` のtest tagを付け、E2Eで「別タグへ一括追加」の対象選択とキャンセルを安定して操作できます。

`CreateNodeDialog` は入力欄、追加、閉じる操作に `create_node_*` のtest tagを付け、作成とキャンセルをE2Eで安定して検証できます。

`RenameNodeDialog` は入力欄、保存、閉じる操作に `rename_node_*` のtest tagを付け、名称変更の保存とキャンセルをE2Eで安定して検証できます。

`ClassifiedSortState` のUIは `sort_open`、`sort_dialog`、`sort_options_list`、`sort_clear_all_open`、`sort_apply` などの test tag で操作します。分類済み画面の概要行には `filterConditionSummary` と並んで現在の並び替え条件も表示します。

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
