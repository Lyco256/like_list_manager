# 実機レベル統合テスト強化 カバレッジ

`実機レベル統合テスト強化 要件定義.md` に対する、現在の自動テスト・安全実行スクリプト・実機確認の対応状況です。

## 安全境界

| 要件 | 状態 | 証跡 |
|---|---|---|
| 本番/テスト applicationId、UID、DB、画像、preferences 分離 | 完了 | `verifyTestEnvironmentIsolation`、`TestEnvironmentIsolationTest`、`run-safe-integration-check.ps1` |
| 本番 OAuth/X API をテストから利用不可 | 完了 | `DisabledOAuthGateway`、`DisabledXApiGateway`、loopback-only network config、設定画面の login 無効化 |
| 同一 SC-56C で本番・テスト共存、本番 metadata 不変 | 完了 | 安全スクリプトの pre/post check。本番 UID 10413、テスト UID 分離 |
| 本番 snapshot を片方向・一時コピーだけで検証 | 一部完了 | 実DB snapshotのhash不変は確認済み。実画像backupは未提供のためfixtureで機構のみ確認 |

## 大規模テスト構成

| # | 項目 | 状態 | 主な証跡 / 残件 |
|---:|---|---|---|
| 1 | テスト用アプリ分離 | 完了 | `TestEnvironmentIsolationTest` |
| 2 | バックアップ/コピー | 一部完了 | `SnapshotCompatibilityTest`。実画像backupでの最終確認が残る |
| 3 | DB/Repository 整合性 | 主要項目完了 | Room migration、同期、タグ削除、保存先失敗、Repository Flow経由の複合検索DB一気通貫、Repository経由のタグ/グループ全移動操作 |
| 4 | データ破壊防止 | 主要項目完了 | 手動概要、タグ・分類保持、検索非変更、削除時投稿保護、画像失敗、保存先失敗 |
| 5 | Fake X API 同期 | 主要項目完了 | pagination、401/403/429/500、refresh失敗、中断再開、欠落/不正JSON、mixed media |
| 6 | API料金/rate limit | 主要項目完了 | production URL遮断、呼出履歴、月間停止、429無限retry防止 |
| 7 | 検索/絞り込み | ロジック完了・主要UI完了 | 全検索target、regex、期間、投稿者、タグ複合、件数summary、非変更。filter dialogのquery適用、日付条件適用/DatePicker内解除/クリア、投稿者Dialogクリア、タグ条件のみクリア、投稿者＋タグ条件の複合E2E、Cancel/Back破棄、Dialog内全クリア確認キャンセル、全クリアE2Eを確認 |
| 8 | 分類操作 E2E | 主要項目完了 | 付与・解除、DB・件数、popup非伝播、別グループ同名タグの複数同時付与を確認 |
| 9 | タグ/グループ管理 E2E | 主要項目完了 | 作成、タグ/グループ作成Dialogキャンセル、同名子タグ、tag名称変更/削除、group名称変更/削除、名称変更Dialogキャンセル、タグ/グループ削除Dialogキャンセル、タグの別グループ移動UI、タグ/グループ移動Dialogキャンセル、別タグへの一括追加、別タグへの一括追加Dialogキャンセル、循環/順序ロジックを確認 |
| 10 | スクロール/大量表示 | 主要項目完了 | SC-56C実機で1,000件末尾/先頭UI、10,000件Room、50件リスクseedを確認 |
| 11 | Macrobenchmark | 完了 | `scripts/run-safe-macrobenchmark-check.cmd` で SC-56C 上の `com.lyco256.llm.test.benchmark` cold/warm startupを測定し、本番metadata前後不変を確認 |
| 12 | 画像保存/圧縮 | 主要項目完了 | WebP、重複download防止、失敗時投稿保持、複数画像の別WebP保存、破損画像のfailed asset記録、snapshot |
| 13 | 設定/安全装置 | 主要項目完了 | 設定画面の使用量表示、test login無効、X API設定の保存/trim/消去UI、暗号化設定roundtrip、無効保存先rollback、設定画面の開閉とDB不変、保存先移動確認cancel UIを確認 |
| 14 | エラー表示/復旧 | 主要項目完了 | 空、未login、HTTP/timeout/JSON、権限不足UI、画像/保存先、continuation再開、Activity再作成後のタブ/フィルター復元、保存先移動copying中断とswitched切替先不可の起動時復旧を確認 |
| 15 | UI状態・軽微バグ | 主要項目完了 | 主要tab、設定画面、dialog、件数、loading、empty、popup、LazyColumn、画面再作成、filter Cancel/BackHandler破棄、filter Dialog内全クリア確認キャンセル、filter DatePicker内解除、filter 投稿者Dialogクリア、画像viewerの表示/閉じる操作/戻る閉じ/複数画像swipe、ローカル削除Dialogのcancel/soft delete、タグ/グループ作成Dialogキャンセル、名称変更Dialogキャンセル、タグ/グループ削除Dialogキャンセル、タグ/グループ移動Dialogキャンセル、別タグへの一括追加Dialogの実行/キャンセル、概要編集の保存と再作成後復元、投稿者クリックから分類済み投稿者フィルターへの遷移とDB不変、タグ条件のみクリア、いいね数ポップアップの詳細/暫定警告表示とDB不変、設定画面のX API設定保存/消去導線、設定画面/結果Dialogの安定操作、いいね数更新確認cancel、ローカル削除DialogのtestTag操作、スクロール後の検索条件維持、スクロール後の未確定タグ選択維持を確認 |
| 16 | 回帰テスト枠 | 運用開始 | popup外tap、二重同期、continuation、保存先失敗を回帰化。発見ごとに追加 |
| 17 | Property-based 候補 | 主要項目完了 | JUnit固定seedの250パターンでslot移動の一意性・集合保存・位置を検証。同期不変条件としてローカル削除済み投稿の非復活、固定seed複数ページ同期の投稿ID一意性・使用量加算・continuation消去を確認 |
| 18 | メディアグリッド複数選択 | 主要項目完了 | 0件維持、×／戻る終了、0件時タグ編集無効、2〜6列Dialogボタン、7〜12列非表示、同一clipId選択同期、選択画像本体非変更、単一／複数画像チェック色、開始時のみハプティックを実装・単体／Compose／隔離実機ゲートで確認 |

## 合格ゲート

- 通常必須: `assembleDebug`、`testDebugUnitTest`、`lintDebug`
- 実機必須: `verifyTestEnvironmentIsolation`、`connectedIntegrationTestAndroidTest`
- 安全実行入口: `scripts/run-safe-integration-check.cmd`
- wireless安全実行入口: `scripts/run-safe-integration-check.cmd -DebugMethod wireless`
- Macrobenchmark安全実行入口: `scripts/run-safe-macrobenchmark-check.cmd`
- snapshot任意入口: `scripts/run-safe-snapshot-check.cmd`

## 2026-07-01 追記

## 2026-07-12 メディアグリッド要件確認

| 対象 | 状態 | 証跡 |
|---|---|---|
| MIXEDタグ集約・pending遷移 | 完了 | `aggregateBulkTagStates`、`bulkTagPendingAfterToggle`、`TagHierarchyTest` |
| 一括タグ適用確認・選択維持 | 完了 | `UiStateRenderingTest`、`TagHierarchyUiV2.kt` |
| 見出し粒度・週期間表示 | 完了 | `TagHierarchyTest`、`buildClassifiedMediaGridItems` |
| 実データ確認 | 未実施 | ユーザー指定により対象外 |

- 設定/安全装置とUI状態系の追加E2Eとして、`top_settings_button` から `settings_screen` を開き、設定画面内の `settings_*` test tagでX API設定、同期、使用量、データ管理の導線を確認する。
- 未ログイン同期エラーの表示/閉じる操作前後でDB fingerprintが変わらないことを確認し、隔離テスト環境で同期エラー導線が実データへ影響しないことを補強する。
- いいね数更新の確認Dialogを `settings_like_refresh` から開き、`settings_like_refresh_cancel` で閉じる前後のDB fingerprint不変を確認する。
- 投稿カードのローカル削除Dialogを `clip_local_delete_*_<clipId>` で操作するよう固定し、キャンセル保持と確定時soft deleteのE2Eを文言依存から外す。
- `scroll_to_top` を使い、分類済み検索条件がスクロール後も維持されること、未分類の未確定タグ選択がスクロール後も分類確定まで維持されることを確認する。

実画像backupの最終確認が残っている間は、要件定義全体を「完了」と判定しません。
# メディアグリッド高速化の検証

## 2026-07-13 wide thumbnail preparation

- Manager receives the ordered source snapshot once per grid item revision; viewport updates do not rebuild the full source list.
- Serial priority is visible cells, adjacent UI rows, then the current viewport's 50-row local-file range. Selection is recomputed after each completion, with yield and 50 ms pacing for wide preparation.
- Wide preparation does not create Compose state, image requests, or per-asset Work objects, and never fetches preview/remote URLs. Application foreground/background callbacks allow the active item to finish, then pause.
- `run-safe-debug-check.cmd` passed Build, UnitTest, and Lint after the change. Isolation-device verification remains required before production overwrite.

隔離実機チェックは`run-safe-integration-check.cmd -DebugMethod wireless`で実施し、Build・UnitTest・Lint・IntegrationTestのSuccessを確認する。本命上書きは隔離チェック成功後に`run-safe-debug-check.cmd -InstallToDevice`で実施する。Paging、低解像度サムネイル、画像処理キューは対象外。
## 2026-07 single-step media-grid resize

- Pinch direction recognition changes the column count by exactly one within 2–12 and locks further changes until all fingers are released, including reverse movement.
- The animation starts at recognition time and applies only to currently composed media cells without fade or a second grid; the central Asset remains anchored across header changes.
- Column changes do not regenerate or refetch completed thumbnails or rebuild the ordered source snapshot; the Thumbnail Manager receives the new column count with the latest viewport.
- Wide preparation advances by cache identity, viewport updates carry direction/index/column count, stale source generations are discarded, and missing local paths fall back from preview URL to remote URL. A decoded JPEG display failure invalidates and retries once.

## 2026-07 media-grid thumbnail cache

- `run-safe-debug-check.cmd`: Build、UnitTest、Lint 成功。
- 実装: 256×256 JPEG quality 60、cacheDir再生成、inSampleSize縮小デコード、直列最新viewport優先、セル単位StateFlow、専用ImageLoader設定。
- `run-safe-integration-check.cmd -DebugMethod wireless`: Success。隔離packageでIntegrationTestまで完了。
- `run-safe-debug-check.cmd -InstallToDevice`: Success。本命packageへ安全に上書きし、スクリプトのpackage情報不変チェックを通過。
