# `app/src/androidTest/java/com/lyco256/llm/data/RepositoryIntegrationTest.kt`

## 2026-08 cross-feature Undo coverage

tag Apply後に内部sync stateが更新されてもpending Undo slotが維持され、Undoがtag relationだけを戻してsync stateを巻き戻さないことを検証します。

## 2026-08 heavy local work tracker coverage

大量clipの一括tag更新がtrackerをactiveにし、完了後に解除することを検証します。またFake X APIの応答待ちではinactiveのままで、取得済み投稿のDB保存区間でだけactiveになることを確認します。

## 2026-08 tag/group move and reorder Undo coverage

全てのRepository移動APIと兄弟並べ替えが操作開始時に直前のUndo slotを破棄し、成功後に移動・並べ替え用slotを作らないことを検証します。不正な移動先と不正な並べ替え対象で操作が失敗しても、旧slotが復活しないことも確認します。内部DB更新がslotを保持する検証は `UndoCoordinatorIntegrationTest` で維持します。

## 2026-08 clip deletion durable Undo coverage

複数asset・複数tagを持つclipがDBから即時削除され、DB close/reopen後もslotとstagingが残り、Undoで同じID・`localPath`以外のmetadata・relation timestamps・画像bytesを復元することを検証します。復元pathはAndroidが同じapp dataを `/data/user/0` と `/data/data` の別表記で返し得るため文字列一致にせず、現在の画像保存先のcanonical境界内に元のファイル名で存在することを確認します。staging copy失敗時にDBと元画像を保護すること、別操作によるslot上書きで対象stagingだけを消すこと、Undo失敗時にslotとstagingを保持することも確認します。canonical path境界、保存先変更後の復元、同名ファイル非上書きは `DurableClipDeleteUndoStoreTest` が隔離filesystemで検証します。

## 2026-08 OCR Undo coverage

OCR手動保存からUndoすると `ocrText` と `ocrUpdatedAt` がともに変更前へ戻り、初回保存のnull timestampも復元することを検証します。保存後に変わった `likeCount`、summary、タグrelationは維持されます。また、OCR認識だけではslotを作らないこと、同一文字列の保存ではtimestampを更新せず旧slotだけをinvalidateすること、保存失敗時に新slotを残さないことを確認します。

## 第14実装

新規画像同期がWebPと有効raw slotの両方を同期完了前に生成し、既存JPEG enqueueも維持することを確認します。raw publishを3回ともIO失敗させた場合も、DB record・WebP・`downloadState=downloaded`を維持し、raw repairだけを追加予約することを隔離test DB/filesDirで検証します。

## 2026-07-10 bulk tag coverage

The repository integration suite verifies that `applyClipTagChanges` applies pending additions/removals to every selected clip in one operation while leaving an unselected clip unchanged, and rejects overlapping add/remove sets without changing the database.

`addAllFromTagToTag` の共通Undoについて、source 10件・target既存3件から新規7件だけを追加してUndoすること、sourceと既存targetのrelationおよび`createdAt`を保持すること、差分0件でslotを作らないこと、大きめのfixtureでもrelationの件数と一意性を維持することを検証します。

隔離されたRoom DB、Fake OAuth、Fake X API、MockWebServerを使って `ClipRepository` の同期・保存・保護動作を検証するInstrumentationテストです。本番package、実X API、実OAuth tokenは使いません。

主な検証内容:

- 再同期時に手動概要・タグ・分類を保持し、新規投稿だけを追加すること
- hard DELETE済み投稿がAPIの現在データに再登場した場合は新しい行として取り込み、旧row IDを復活させないこと
- pagination、continuation再開、重複投稿防止、月間使用量/rate limit保存、月別API使用量履歴と累計使用量の保存
- いいね数更新成功時に保存件数を増やさずAPI使用量だけを加算し、失敗時はAPI使用量を加算しないこと
- 複数月の `api_usage_months.billableReadCount` 合計が設定画面向け累計API使用量になること
- 設定画面向けスナップショットで、現存投稿を保存件数に含め、実在する管理対象画像だけを画像枚数に含めること
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

## 2026-08 OCR structured post result

The OCR repository test verifies that locally stored `photo` and `video_thumbnail` assets are returned in asset order with their asset IDs, local paths, and image-level structured results. Non-OCR asset types are ignored, while the compatibility `fullText` keeps the existing non-blank image order and `\n\n` separator. Detection still leaves the database and Undo slot unchanged; only the explicit OCR save updates `ocrText` and `ocrUpdatedAt`.

認識失敗時も、保存済み`ocrText`、`ocrUpdatedAt`、Undo slotを変更しないことを確認します。

ML KitとPP-OCRv6 smallを別fake gatewayで注入し、要求したengineだけが1回呼ばれること、共通の構造化結果経路を通ること、比較検出後もDBのOCR本文・更新時刻・Undo slotが不変であることを確認します。

## 2026-07 media thumbnail coverage

- Added repository tests for `photo` WebP saves, `video` / `animated_gif` preview-image saves on non-Wi-Fi contexts, preview decode failure, and mixed legacy local paths.
- The OCR and trash-deletion fixtures now use legacy `.jpg` / `.png` local paths to keep the non-WebP path covered.

## 2026-07 media-grid tweet dialog

- `observeClipWithDetailsTracksOnlyTheSelectedClipAndUpdates` verifies clip-scoped detail construction, asset ID ordering, tag selection, update re-emission, and the missing-clip `null` result.
