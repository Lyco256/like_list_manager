# `app/src/main/java/com/lyco256/llm/SettingsScreen.kt`

設定画面のCompose専用UIをまとめるファイルです。右上の設定アイコンから開く全画面の「設定」を描画し、X API設定、同期、使用量、データ管理の4セクションを縦スクロールで表示します。

## 主な責務

- `SettingsScreen`: 設定画面全体のScaffold、戻る導線、セクション順序、各種ダイアログ状態を管理する
- `SettingsSection`: 見出しと区切り線を持つ共通セクション枠
- `SegmentedStorageUsageBar`: ストレージ全体を「他のデータ」「アプリデータ」「空き容量」の3区分で可視化する
- `StorageUsageLegend`: ストレージバーの凡例を表示する

設定画面では Repository の業務ロジックは実行せず、`MainViewModel` が公開する状態とイベントだけを使って表示と操作を行います。Client ID の保存、ログイン、ログアウト、同期、いいね数再取得、保存先移動は ViewModel 経由で実行します。

## 関連ファイル

- `MainActivity.kt.md`: 設定画面の表示切り替え、ViewModel 経由の操作入口
- `data/ClipRepository.kt.md`: 設定画面から呼ぶ業務処理
- `data/PostStorageManager.kt.md`: 保存先と容量表示の元データ
- `data/Entities.kt.md`: 設定画面で表示するスナップショット

## UI調整メモ

- 項目ごとの間隔は広めに取り、セクション見出しから内容までも十分な余白を取る
- X API設定の主要ボタンは横並びにし、左から `保存`、`消去`、`Xからログアウト` の順で並べる
- `settings_login_logout` は未ログイン時に「保存してXにログイン」、ログイン中は「Xからログアウト」を表示する
- `消去` ボタンは赤背景・白文字で強調する
- `Xからログアウト` は折り返さないようにして、文字数に応じた自然な横幅で表示する
