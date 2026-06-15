# `MainActivity.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/MainActivity.kt`

## 保存先変更の進捗表示

- 保存容量の取得前は「計算中」と表示し、取得中はスピナーと説明を表示して移動操作を無効化する
- 「ここへ移動」の直後、見積もり中、移動開始準備中、コピー・検証・旧データ削除中の各段階で待機表示を出す

## 役割

Activity、ViewModel、UI state、Compose画面をまとめる現在のUI入口です。未分類、分類、タグ、同期、使用量、X API設定、投稿データ保存先設定を提供します。

## 主な処理

- `MainActivity`: Compose起動とAppAuthのActivity Result受信
- `MainViewModel`: RepositoryのFlowをUI stateへ合成し、ユーザー操作をRepositoryへ渡す
- `MainUiState`: 未分類、分類済み、検索、タグ絞り込みを派生計算
- `ClipListScreen`: 未分類投稿ごとのタグ選択を画面内に一時保持し、1件以上選択した状態で「分類」を押したときだけ保存
- `TweetCard`: X風の投稿本文・画像、概要編集、複数タグ選択、分類確定、ローカル削除
- 分類済み画面のタグ変更は即時保存し、全タグを外した投稿は未分類へ戻す
- `TagListScreen`: タグ追加、名称変更、削除、別タグへの一括追加
- `ApiSettingsDialog`: Client ID保存、Xログイン、ログアウト
- `PostStorageDialog`: 内部/SDカードの一覧、現在地、使用量、空き容量、移動開始
- 保存先移動中は投稿一覧の代わりに待機画面を表示し、編集や同期を行わせない
- SDカード未装着時は投稿一覧と編集・同期を停止し、保存先確認を案内
- `UsageDialog`: 月間件数、15分制限、最終同期、ログイン状態

## データの流れ

UI操作 → `MainViewModel` → `ClipRepository` → Room/X API/暗号化設定。RoomのFlow更新 → ViewModelの `uiState` → Compose再描画です。

## 関連ファイル

- `LikeListManagerApp.kt.md`: `AppContainer` の取得元です。
- `data/ClipRepository.kt.md`: UI操作の業務処理を実行します。
- `data/Entities.kt.md`: 画面で表示・編集するモデルです。
- `data/ApiSettingsStore.kt.md`: UIに表示するClient IDとログイン状態を保存します。
- `data/PostStorageManager.kt.md`: 保存先状態、移動見積もり、移動結果を提供します。
- `data/XOAuthManager.kt.md`: ログインIntentと認証結果交換を担当します。
- `../../../../AndroidManifest.xml.md`: MainActivityとcallback Activityを宣言します。

## 変更時の確認

UI項目を追加する場合は、対応するViewModel操作、Repository API、Entity/DAOの必要性を確認します。OAuth画面を変える場合はManifestと `XOAuthManager` も確認します。
