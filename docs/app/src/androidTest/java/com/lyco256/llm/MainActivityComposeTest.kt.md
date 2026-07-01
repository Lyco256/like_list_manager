# `app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt`

隔離された `com.lyco256.llm.test` 上で主要Compose画面を検証するInstrumentationテストです。各テストの前に隔離DBだけを初期化し、seedデータを投入します。本番 `com.lyco256.llm` のDBや画像には触れません。

主な検証内容:

- 主要タブ、設定/使用量ダイアログ、隔離環境でのXログイン無効化、X API設定の保存/trim/消去UI
- 投稿データ保存先Dialogの閉じる操作とDB fingerprint不変
- 検索/絞り込みの適用、日付条件、DatePicker内解除、投稿者条件とタグ条件の複合E2E、投稿者Dialogクリア、タグ条件のみクリア、投稿者クリックによる分類済み投稿者フィルター遷移、キャンセル、BackHandler破棄、Dialog内全クリア確認キャンセル、全クリアとDB fingerprint不変
- 投稿カードのいいね数ポップアップが詳細と暫定警告を表示し、開閉でDB fingerprintを変えないこと
- 未分類から分類済みへの移動、分類解除、Roomの `clip_tags` 更新
- 別グループに同名の子タグがある場合の複数タグ同時付与
- 投稿カードのローカル削除Dialogで、キャンセル時は保持、確定時は一覧から消えつつDB上はsoft deleteとして残ること
- 投稿カードの概要編集がDBへ保存され、Activity再作成後も入力内容が残ること
- 保存済みPhotoの画像viewerが戻る操作で閉じること、複数画像をswipeで移動できること、開閉やページ移動でDB fingerprintを変えないこと
- タグ/グループ作成、タグ/グループ作成Dialogキャンセル、同名子タグ、タグ名称変更、グループ名称変更、名称変更Dialogキャンセル、タグの別グループ移動、タグ/グループ移動Dialogキャンセル、別タグへの一括追加、別タグへの一括追加Dialogキャンセル、削除、タグ/グループ削除Dialogキャンセル
- popup外tapがカードへ伝播せず、タグ関係も変化しないこと
- 空状態、同期エラー表示、Activity再作成後のタブ復元と分類済みフィルター復元

変更時は `scripts/run-safe-integration-check.cmd` で、同じ実機上の本番package metadataが前後不変であることも合わせて確認します。

## 2026-07-01 追記: メインメニュー/結果Dialogの安定操作

主要メニュー導線は `main_menu_*` のtest tagで開きます。使用量Dialogの閉じる操作は `usage_close`、同期未ログインエラーの結果Dialogは `sync_result_close` で閉じ、同期エラー表示前後のDB fingerprintが変わらないことも確認します。

いいね数再取得の見積もりDialogは、隔離DBに数値post IDの対象clipを追加して `main_menu_like_refresh` から開き、`like_refresh_estimate_cancel` で閉じた前後のDB fingerprintが変わらないことを確認します。

投稿カードのローカル削除Dialogは `clip_local_delete_*_<clipId>` のtest tagで開閉/実行し、キャンセル時は保持、確定時は一覧から消えつつDB上はsoft deleteとして残ることを確認します。

分類済み検索では、フィルタ適用後に一覧をスクロールして `scroll_to_top` で戻っても検索条件summaryと対象clipが残りDB fingerprintが変わらないことを確認します。未分類一覧では、タグchip選択後に一覧をスクロールし、対象clipへ戻ってから分類確定できることを確認し、スクロールで未確定選択状態が失われないことを固定します。
