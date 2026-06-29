# `app/src/androidTest/java/com/lyco256/llm/data/RepositoryIntegrationTest.kt`

隔離されたRoom DB、Fake OAuth、Fake X API、MockWebServerを使って `ClipRepository` の同期・保存・保護動作を検証するInstrumentationテストです。本番package、実X API、実OAuth tokenは使いません。

主な検証内容:

- 再同期時に手動概要・タグ・分類を保持し、新規投稿だけを追加すること
- ローカル削除済み投稿がAPIから再登場しても復活せず、新規投稿だけを追加すること
- pagination、continuation再開、重複投稿防止、月間使用量/rate limit保存
- 401時の1回refreshと、refresh失敗時に旧sessionを保持すること
- 403/429/500や空ページで部分投稿や無限retryを発生させないこと
- photo保存をWebP化し、既存投稿の画像を重複downloadしないこと
- 複数photoを別WebPとして保存し、破損画像だけを `failed` asset として記録すること
- 画像download失敗、保存先移動失敗、タグ/空グループ削除時にも投稿を保護すること

変更時は `run-safe-integration-check.cmd` で実機統合テストを実行し、production metadataが前後不変であることを確認します。
