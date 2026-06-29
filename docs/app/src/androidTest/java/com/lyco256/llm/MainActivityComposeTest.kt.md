# `app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt`

隔離された `com.lyco256.llm.test` 上で主要Compose画面を検証するInstrumentationテストです。各テストの前に隔離DBだけを初期化し、seedデータを投入します。本番 `com.lyco256.llm` のDBや画像には触れません。

主な検証内容:

- 主要タブ、設定/使用量ダイアログ、隔離環境でのXログイン無効化
- 投稿データ保存先Dialogの閉じる操作とDB fingerprint不変
- 検索/絞り込みの適用、日付条件、投稿者条件とタグ条件の複合E2E、キャンセル、BackHandler破棄、全クリアとDB fingerprint不変
- 未分類から分類済みへの移動、分類解除、Roomの `clip_tags` 更新
- 別グループに同名の子タグがある場合の複数タグ同時付与
- タグ/グループ作成、同名子タグ、タグ名称変更、グループ名称変更、削除
- popup外tapがカードへ伝播せず、タグ関係も変化しないこと
- 空状態、同期エラー表示、Activity再作成後のタブ復元と分類済みフィルター復元

変更時は `scripts/run-safe-integration-check.cmd` で、同じ実機上の本番package metadataが前後不変であることも合わせて確認します。
