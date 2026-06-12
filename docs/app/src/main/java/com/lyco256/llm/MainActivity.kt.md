# `MainActivity.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/MainActivity.kt`

## 役割

Activity、ViewModel、UI state、Compose画面をまとめる現在のUI入口です。未分類、分類、タグ、同期、使用量、X API設定を提供します。

## 主な処理

- `MainActivity`: Compose起動とAppAuthのActivity Result受信
- `MainViewModel`: RepositoryのFlowをUI stateへ合成し、ユーザー操作をRepositoryへ渡す
- `MainUiState`: 未分類、分類済み、検索、タグ絞り込みを派生計算
- `TweetCard`: X風の投稿本文・画像、概要編集、タグ再割り当て、ローカル削除
- `TagListScreen`: タグ追加、名称変更、削除、別タグへの一括追加
- `ApiSettingsDialog`: Client ID保存、Xログイン、ログアウト
- `UsageDialog`: 月間件数、15分制限、最終同期、ログイン状態

## データの流れ

UI操作 → `MainViewModel` → `ClipRepository` → Room/X API/暗号化設定。RoomのFlow更新 → ViewModelの `uiState` → Compose再描画です。

## 関連ファイル

- `LikeListManagerApp.kt.md`: `AppContainer` の取得元です。
- `data/ClipRepository.kt.md`: UI操作の業務処理を実行します。
- `data/Entities.kt.md`: 画面で表示・編集するモデルです。
- `data/ApiSettingsStore.kt.md`: UIに表示するClient IDとログイン状態を保存します。
- `data/XOAuthManager.kt.md`: ログインIntentと認証結果交換を担当します。
- `../../../../AndroidManifest.xml.md`: MainActivityとcallback Activityを宣言します。

## 変更時の確認

UI項目を追加する場合は、対応するViewModel操作、Repository API、Entity/DAOの必要性を確認します。OAuth画面を変える場合はManifestと `XOAuthManager` も確認します。
