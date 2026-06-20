# `MainActivity.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/MainActivity.kt`

## 保存先変更の進捗表示

- 保存容量の取得前は「計算中」と表示し、取得中はスピナーと説明を表示して移動操作を無効化する
- 「ここへ移動」の直後、見積もり中、移動開始準備中、コピー・検証・旧データ削除中の各段階で待機表示を出す

## 役割

Activity、ViewModel、UI state、Compose画面の接続入口です。未分類、分類済み、タグ管理、同期/使用量、X API設定、投稿データ保存先設定へ状態とイベントを流します。画面本体のタグ階層UIは `TagHierarchyUiV2.kt` に分離されています。

## 主な処理

- `MainActivity`: Compose起動とAppAuthのActivity Result受信
- `MainViewModel`: RepositoryのFlowをUI stateへ合成し、ユーザー操作をRepositoryへ渡す
- `MainUiState`: 未分類、全ツイート検索、検索条件、投稿者一覧、タグ／グループの含むOR・必須AND・排除条件を派生計算
- `TweetFilterState`: 分類済み画面の検索文字列、検索モード、検索対象、投稿日範囲、ユーザー条件、タグ条件、タグのみトグルをまとめます。
- `MainViewModel.applyFilters`: 絞り込みDialogで確定した `TweetFilterState` 全体を一括反映します。Dialog内の編集中はViewModelの確定条件を変更しません。
- `filterClipsForSearch`: 分類済み画面の共通フィルター関数です。タグのみ、日付、ユーザー、タグ、文字列検索を適用し、正規表現が不正な場合は0件扱いにします。
- `screenTitle`: 現在のタブやDialog状態からTopAppBar表示名を生成し、未分類では総未分類件数を表示します。
- `EnhancedClipListScreen` / `EnhancedClassifiedScreen` / `EnhancedTagListScreen` を呼び出して、未分類、全ツイート検索、タグ管理の画面へ接続する
- 未分類画面の投稿者タップは分類済み画面へ移動し、タグのみOFFのユーザー絞り込みを開始します。
- 画面内の重複見出しは出さず、現在画面名はTopAppBarへ集約します。
- タグリストのドラッグ並び替えは `moveTagNodeToIndex` から `ClipRepository.moveNodeToParentAtSlot` へ渡し、UI側placeholderIndexとRepository側indexの意味を揃える
- `TagListScreen` 系の旧Composableは履歴として残しているが、実際の表示は `TagHierarchyUiV2.kt` 側が担当する
- `ApiSettingsDialog`: Client ID保存、Xログイン、ログアウト
- `PostStorageDialog`: 内部/SDカードの一覧、現在地、使用量、空き容量、移動開始
- 保存先移動中は投稿一覧の代わりに待機画面を表示し、編集や同期を行わせない
- SDカード未装着時は投稿一覧と編集・同期を停止し、保存先確認を案内
- `UsageDialog`: 月間件数、15分制限、最終同期、ログイン状態

## データの流れ

UI操作 → `MainViewModel` → `ClipRepository` → Room/X API/暗号化設定。RoomのFlow更新 → ViewModelの `uiState` → Compose再描画です。

## 関連ファイル

- `LikeListManagerApp.kt.md`: `AppContainer` の取得元です。
- `TagHierarchyUiV2.kt.md`: 未分類、分類済み、タグ管理のCompose画面です。
- `data/ClipRepository.kt.md`: UI操作の業務処理を実行します。
- `data/Entities.kt.md`: 画面で表示・編集するモデルです。
- `data/ApiSettingsStore.kt.md`: UIに表示するClient IDとログイン状態を保存します。
- `data/PostStorageManager.kt.md`: 保存先状態、移動見積もり、移動結果を提供します。
- `data/XOAuthManager.kt.md`: ログインIntentと認証結果交換を担当します。
- `../../../../AndroidManifest.xml.md`: MainActivityとcallback Activityを宣言します。

## 変更時の確認

UI項目を追加する場合は、対応するViewModel操作、Repository API、Entity/DAOの必要性を確認します。OAuth画面を変える場合はManifestと `XOAuthManager` も確認します。
