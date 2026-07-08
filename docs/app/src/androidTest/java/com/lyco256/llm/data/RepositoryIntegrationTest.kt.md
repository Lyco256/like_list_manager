# `app/src/androidTest/java/com/lyco256/llm/data/RepositoryIntegrationTest.kt`

隔離されたRoom DB、Fake OAuth、Fake X API、MockWebServerを使って `ClipRepository` の同期・保存・保護動作を検証するInstrumentationテストです。本番package、実X API、実OAuth tokenは使いません。

主な検証内容:

- 再同期時に手動概要・タグ・分類を保持し、新規投稿だけを追加すること
- ローカル削除済み投稿がAPIから再登場しても復活せず、新規投稿だけを追加すること
- pagination、continuation再開、重複投稿防止、月間使用量/rate limit保存、月別API使用量履歴と累計使用量の保存
- いいね数更新成功時に保存件数を増やさずAPI使用量だけを加算し、失敗時はAPI使用量を加算しないこと
- 複数月の `api_usage_months.billableReadCount` 合計が設定画面向け累計API使用量になること
- 設定画面向けスナップショットで、有効投稿だけを保存件数に含め、実在する管理対象画像だけを画像枚数に含めること
- Repository経由のログアウトではClient IDを残してsessionだけを消し、revoke失敗時もローカルsessionを消すこと
- Repository経由のClient ID消去ではClient IDとOAuth sessionを同時に消すこと
- 固定seedで生成した複数ページ・ページ内重複でも、投稿ID集合の一意性、取得数加算、continuation消去が成立すること
- 401時の1回refreshと、refresh失敗時に旧sessionを保持すること
- liked posts同期の401/403/429/500/parse失敗や空ページで、部分投稿、無限retry、API使用量の誤加算を発生させないこと
- photo保存をWebP化し、既存投稿の画像を重複downloadしないこと
- 複数photoを別WebPとして保存し、破損画像だけを `failed` asset として記録すること
- Repository経由のタグ/グループ移動API全種で、親と兄弟順がRoomへ永続化されること
- 画像download失敗、保存先移動失敗、タグ/空グループ削除時にも投稿を保護すること

変更時は `run-safe-integration-check.cmd` で実機統合テストを実行し、production metadataが前後不変であることを確認します。

## 2026-07 OCR update

- Added repository tests for OCR detection, OCR persistence, and hard delete of assets and local files.

## 2026-07 media thumbnail coverage

- Added repository tests for `photo` WebP saves, `video` / `animated_gif` preview-image saves on non-Wi-Fi contexts, preview decode failure, and mixed legacy local paths.
- The OCR and trash-deletion fixtures now use legacy `.jpg` / `.png` local paths to keep the non-WebP path covered.
