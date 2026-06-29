# `app/src/main/java/com/lyco256/llm/MainActivity.kt`

Activity、ViewModel、UI state、Compose画面の接続入口です。未分類、分類済み、タグ管理、同期/使用量、X API設定、投稿データ保存先設定へ状態とイベントを流します。画面本体のタグ階層UIは `TagHierarchyUiV2.kt` に分離されています。

## 主な責務

- `MainActivity`: Compose起動とAppAuthのActivity Result受信
- `MainViewModel`: RepositoryのFlowをUI stateへ合成し、ユーザー操作をRepositoryへ渡す
- `MainUiState`: 未分類、分類済み、検索条件、投稿者一覧、タグ階層、保存先状態、同期状態をまとめる
- `TweetFilterState`: 分類済み画面の文字列検索、検索モード、検索対象、期間、投稿者条件、タグ条件、タグのみtoggleを表す
- `PostStorageDialog`: 内部/SDカードの一覧、現在地、使用量、空き容量、移動開始入口を表示する
- `StorageMoveEstimateDialog`: 保存先移動見積もりの内容確認と、移動開始/キャンセル操作を扱う
- `StorageProgressDialog`: 保存先見積もり中、移動開始準備中、移動中などの待機表示を行う

保存先移動中は投稿一覧の代わりに待機画面を表示し、編集や同期を行わせません。SDカード未装着時は投稿一覧と編集・同期を停止し、保存先確認を案内します。

## UI自動テスト

主要画面、ナビゲーション、メニュー、空状態には安定したCompose `testTag` を設定しています。表示テキストとtestTagを使って画面遷移をassertし、スクリーンショット比較は行いません。

`PostStorageDialog` は保存先移動を開始しない閉じる操作をE2Eで確認できるよう、Dialog本体に `post_storage_dialog`、閉じるボタンに `post_storage_close` を付けています。

`StorageMoveEstimateDialog` は移動開始を伴わないキャンセルUIをandroidTestから直接renderできます。`StorageProgressDialog` はandroidTestからloading表示を直接renderできるinternal composableです。

タグ移動Dialogの移動先には `move_node_target_root` と `move_node_target_group_<groupId>` のtest tagを付け、E2Eから表示テキストだけに依存せず移動先を選べます。

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
