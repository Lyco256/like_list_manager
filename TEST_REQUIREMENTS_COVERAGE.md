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
| 11 | Macrobenchmark | 構成更新・実機再確認待ち | `app/src/benchmark` の専用Activity/importer/metricsと `scripts/run-safe-macrobenchmark-check.cmd`。本命 `app/src/main` から計測依存を除去し、実機測定は再確認が必要 |
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

## 2026-07-13 メタデータ最終整理

| 対象 | 状態 | 証跡 |
|---|---|---|
| sourceのTagEntity除去、3 Flow、ClipTag一括LongArray、revision境界 | 完了 | `ClipRepository.kt`、`RepositoryIntegrationTest`、`TagHierarchyTest` |
| 実効sort判定とsort準備の分離 | 完了 | `MediaGridMetadata.kt`、`TagHierarchyTest` |
| 投稿者キー前計算、正規化cache key、cache hit即Ready | 完了 | `MediaGridMetadata.kt`、`MainActivity.kt` |
| Asset展開とmatchingMediaCount/メディア有無の一括確定、hasLocalFile除去 | 完了 | `TagHierarchyUiV2.kt`、`UiStateRenderingTest` |
| 通常安全検証 | 完了 | `run-safe-debug-check.cmd` Success |

## 2026-07-13 wide thumbnail preparation

## 2026-07-13 card/grid duplicate-work removal

- ローカル安全確認: `run-safe-debug-check.cmd` のBuild / UnitTest / Lint / Successを確認。
- 実装確認対象: MainUiState遅延評価、カード経路限定scroll key、明示的source revision、タグ構造revision、source更新時前計算、LongArrayタグ保持、グリッドmatchingClipCount表示。
- 隔離実機: `run-safe-integration-check.cmd -DebugMethod wireless` を本命上書き前に実行する。

- Manager receives the ordered source snapshot once per grid item revision; viewport updates do not rebuild the full source list.
- Serial priority is visible cells, adjacent UI rows, then the current viewport's 50-row local-file range. Selection is recomputed after each completion, with yield and 50 ms pacing for wide preparation.
- Wide preparation does not create Compose state, image requests, or per-asset Work objects, and never fetches preview/remote URLs. Application foreground/background callbacks allow the active item to finish, then pause.
- `run-safe-debug-check.cmd` passed Build, UnitTest, and Lint after the change. Isolation-device verification remains required before production overwrite.

隔離実機チェックは`run-safe-integration-check.cmd -DebugMethod wireless`で実施し、Build・UnitTest・Lint・IntegrationTestのSuccessを確認する。本命上書きは隔離チェック成功後に`run-safe-debug-check.cmd -InstallToDevice`で実施する。Paging、低解像度サムネイル、画像処理キューは対象外。
## 2026-07 single-step media-grid resize（履歴: 現行経路では不使用）

- Pinch direction recognition changes the column count by exactly one within 2–12 and locks further changes until all fingers are released, including reverse movement.
- The animation starts after a small direction dead zone and applies only to currently composed media cells without a second grid; the central Asset remains anchored across header changes.
- Column changes do not regenerate or refetch completed thumbnails or rebuild the ordered source snapshot; the Thumbnail Manager receives the new column count with the latest viewport.
- Wide preparation advances by cache identity, viewport updates carry direction/index/column count, stale source generations are discarded, and missing local paths fall back from preview URL to remote URL. A decoded JPEG display failure invalidates and retries once.

## 2026-07 media-grid thumbnail cache

## 2026-07-13 thumbnail state transitions and pinch lock（履歴: 現行経路では不使用）

- Revision is `Long` from repository snapshot through UI and Thumbnail Manager; no `Int` conversion remains.
- One Asset keeps the same StateFlow across source changes. A generation token prevents stale Ready/Failed publication, and the latest source is rescheduled from Waiting or known Failed.
- Wide preparation only selects sources with `localPath` and calls the store with `allowRemote=false`; missing local files are recorded as completed for that cache identity and remain eligible for URL fallback when visible.
- Pinch detection uses a stable pointer-input key, reads the latest column count/callback, commits at most one column, and remains locked through recomposition and reverse motion until all pointers are released. Boundary gestures at 2 and 12 columns also lock.
- The grid creates the static skeleton gradient once and passes the shared Brush to cells.

## 2026-07 continuous media-grid morph foundation（履歴: 現行経路では不使用）

- `MediaGridMorphTest` covers the 2..12 adjacent-column bound, extreme-scale clamping, reversible progress, the 0.5 release threshold, one-shot target handoff, 4→5 zero-width right-edge slot, Asset correspondence, Header add/remove/title change, and bounded planning for 10,000 items.
- `MediaGridMorphPlan` keeps only viewport-neighborhood rows and related headers; progress updates reuse the immutable plan and do not touch the item list, thumbnail manager, image requests, files, or DB.
- The Compose path now slices the current viewport plus two rows before creating the target window, and applies the planned Asset anchor during target handoff.
- `TagHierarchyUiV2` uses a stable `pointerInput(Unit)`, keeps the actual grid column count unchanged during tracking, suppresses two-finger cell actions after morph start, and cancels stale plans when source revision or items change.
- Required device order was completed: isolated integration check succeeded, then production-package debug overwrite succeeded with package metadata invariance.

## 2026-07 seamless media-grid morph overlay（履歴: 現行経路では不使用）

- `MediaGridMorphTest` now covers linear Rect interpolation, bounded crossfade alpha, same-Asset single-layer rendering, and finite zero-width slots.
- `MediaGridMorphOverlay` remains absent in Idle and is composed only for the four active morph/handoff phases above the same normal grid. The overlay is viewport-plan bounded and does not create a second `LazyVerticalGrid`.
- The RenderModel and its distinct Asset map are remembered by the immutable session plan and stable Asset keys. Progress does not rebuild item lists, maps, thumbnail sources, or ImageRequests; the overlay observes only existing Thumbnail Manager StateFlows and is withheld until all planned slot visuals/placeholders are available.
- Target handoff dispatches the column callback once, keeps Overlay at progress 1, performs one anchor `scrollToItem` and one necessary `scrollBy`, then completes without a post-handoff anchor restore.
- `animateItem()` and the former cell `Animatable` resize scale are suppressed/removed for morph and handoff. Header background, height, and Y are interpolated in the overlay; video, like-count, selection, and error visuals are crossfaded with their Asset.

- `run-safe-debug-check.cmd`: Build、UnitTest、Lint 成功。

## 2026-07-14 final media-grid morph adjustment（履歴: 現行経路では不使用）

- `MediaGridMorphTest` covers continuous Header Y/height interpolation, added/deleted Header height endpoints, changed-title crossfade at progress 0.5, and the one-layer identical-title rule.
- The overlay uses only bounded Slot/Header backgrounds, maximum-height clipped Header nodes, stable Slot/Header keys, slot-width-derived badge metrics, and one existing Thumbnail StateFlow observation per Asset ID. The normal grid remains visible until preparation is complete.
- `MediaGridMorphUiState` applies handoff completion and overlay removal in one state update after the target grid has laid out; normal anchor restoration is blocked in all non-Idle phases.
- Safety verification completed: wireless isolated integration check, wireless production-package debug overwrite, and wireless Macrobenchmark all succeeded; the macrobenchmark PostCheck preserved production package metadata and kept benchmark UID separate.
- 実装: 256×256 JPEG quality 60、cacheDir再生成、inSampleSize縮小デコード、直列最新viewport優先、セル単位StateFlow、専用ImageLoader設定。
- `run-safe-integration-check.cmd -DebugMethod wireless`: Success。隔離packageでIntegrationTestまで完了。
- `run-safe-debug-check.cmd -InstallToDevice`: Success。本命packageへ安全に上書きし、スクリプトのpackage情報不変チェックを通過。

## 2026-07-14 実装22 coverage

| Requirement | Implementation / evidence | Status |
|---|---|---|
| Five modes and startup-only resolution | `app/src/benchmark/java/com/lyco256/llm/data/MediaGridBenchmark.kt`, `BenchmarkMainActivity` | Implemented only in benchmark source set; normal builds do not resolve modes |
| run-as-only read-only snapshot | `run-safe-macrobenchmark-check.ps1` | Implemented; unavailable run-as fails without fallback |
| No credentials/preferences | whitelist of DB/WAL/SHM, JPEG cache, and `files/images` | Implemented |
| target-only import and localPath rewrite | `BenchmarkSnapshotImporter` | Implemented |
| no network | benchmark network config, disabled gateways, `allowRemote=false` | Implemented |
| Trace/counters and separated paths | `app/src/benchmark/.../MediaGridBenchmark.kt`; no references from main manager/store/UI/Morph | App-side high-frequency Trace/counter hooks removed; benchmark output fields are empty/N/A |
| identical 5-iteration scroll/pinch scenarios | `MediaGridPerformanceMacrobenchmark` | Implemented in test source; runtime measurement not reached because snapshot precondition failed |
| report and deltas | safe macrobenchmark summary writer | Report generated; values are N/A because the target did not receive a snapshot/metric export |
| production invariance | safe script PostCheck metadata and data hashes | Verification path implemented; runtime DB/media hash comparison was not reached because production `run-as` is unavailable |

実装22では本番最適化（worker数、Coil/cache容量、生成間隔、viewport間引き、画像反映延期）を変更していない。

## 2026-07-17 simple column-change stabilization

- Unit: `MediaGridMorphTest` covers threshold miss, pinch-in `+1`, pinch-out `-1`, final cumulative ratio after direction reversal, cancellation, 2..12 bounds, and the one-step limit through `mediaGridColumnCountAfterPinchRelease`.
- Integration: `MainActivityComposeTest.classifiedDisplayToggleSwitchesBetweenCardAndMediaGridAndSurvivesActivityRecreation` uses real two-pointer input to cover 4→5→4, threshold-miss no-op, repeated round trips, anchor position, immediate cell dialog interaction, post-change scroll, and absence of `media_grid_morph_overlay`.
- The product path no longer creates morph sessions or overlays during pinch. `MediaGridMorph.kt` and `MediaGridMorphOverlay.kt` remain retained components for later animation work.

## 2026-07-18 メディアグリッド スクロール改善 第2実装

| 対象 | 状態 | 証跡 |
|---|---|---|
| pixel単位viewport再処理の抑止、安定ID/source index順による重複抑止 | 完了 | `MediaGridViewportSnapshot.sameStructureAs`、`TagHierarchyUiV2.kt`、`MediaGridViewportDispatchTest` |
| source/viewport/foreground/生成完了/表示結果を単一coordinatorで直列管理 | 完了 | `MediaGridThumbnailManager.kt`、`MediaGridViewportDispatchTest` |
| 生成中conflate、完了後の最新viewportからの単一候補選択、stale/dispose破棄 | 完了 | `MediaGridViewportDispatchTest` |
| 非待機holder状態取得、source map/work stateコピー抑止、直接候補走査 | 完了 | `MediaGridThumbnailManager.kt` |
| 高速fling、画像追従、filter revision、即時セル操作、既存列数変更 | 完了 | `MainActivityComposeTest.kt`、wireless `run-safe-integration-check.cmd` |
| Macrobenchmark・計測処理 | 今回未実行・未変更 | 要件指定により対象外 |

検証順はwireless実機で隔離統合チェックを先に実行し、続いて本番package安全上書きチェックを実行する。Macrobenchmarkは実行しない。

## 2026-07-19 メディアグリッド スクロール改善 第3実装

| 対象 | 状態 | 証跡 |
|---|---|---|
| Waiting / Generating / Ready未成功のPlaceholder判定 | 実装済み | `mediaGridCellVisualState`、`MediaGridPlaceholderRenderingTest` |
| 現在画像モデルのonSuccess後のPlaceholder消去・モデル変更・onError復帰 | 実装済み | `MediaGridImageModelKey`、`MediaGridPlaceholderRenderingTest`、`media_grid_placeholder_<assetId>` |
| Failed / 画像元なし / download失敗のError判定 | 実装済み | `MediaGridPlaceholderRenderingTest`、既存 `media_grid_error_<assetId>` |
| セル単位・静的・テーマ対応グラデーション | 実装済み | `MediaGridPlaceholderRendering.kt`、`drawWithCache`、ライト/ダークCompose確認 |
| 高速fling、触れ直し、セル操作、選択、列数・filter・sort・詳細表示 | 既存回帰確認対象 | `MainActivityComposeTest`、`UiStateRenderingTest` |
| Macrobenchmark | 未実行 | 今回の要件で対象外 |

## 2026-07-19 第5実装: viewport cache hydration

| Requirement | Status | Evidence |
|---|---|---|
| canonical key shared by generation and lookup; local/preview/remote source paths | Implemented | `MediaGridThumbnailStore.kt`, `MediaGridThumbnailStoreKeyTest.kt` |
| no generation/network/decode/resize/output creation during cache check | Implemented | `findCached()`, cache hydration unit tests |
| one IO job/result event; visible and adjacent hits restored together | Implemented | `MediaGridThumbnailManager.kt`, `MediaGridViewportDispatchTest` |
| miss-only generation and same-revision miss memoization | Implemented | `MediaGridViewportDispatchTest` |
| Dragging/Flinging/revision/foreground/dispose stale-result cancellation | Implemented | manager cancellation guards and unit coverage |
| multiple cached cells, mixed cache/non-cache, recreation/filter/sort/fast fling/retouch | Implemented | `MainActivityComposeTest.kt` |
| Macrobenchmark unchanged and not run | Unchanged | benchmark source set untouched |

## 2026-07-19 メディアグリッド スクロール改善 第4実装

| 対象 | 状態 | 証跡 |
|---|---|---|
| source登録前viewportの拒否後再送、空layout後の初回非空viewport | 実装・単体確認 | `MediaGridViewportDispatchTest.viewportRejectedBeforeSourceRegistrationCanBeResent`、`emptyInitialViewportDoesNotPreventFirstNonEmptyGeneration`、`TagHierarchyUiV2.kt` |
| dispatch成功時だけのUI送信済み記録 | 実装 | `ClassifiedMediaGridContent` の`dispatchViewport()`成功条件付き更新 |
| Idle / Dragging / Flingingの状態判定と変化時通知 | 実装 | `LazyGridState.interactionSource`、`isScrollInProgress`、`distinctUntilChanged()`、`MediaGridScrollOperationState` |
| 操作中の表示中・隣接・wide新規生成抑制 | 実装・単体確認 | `draggingAndFlingingAllowOneNormalCompletionButDoNotStartTheNextCandidate`、`operationStartCancelsWideWithoutRecordingCompletion` |
| 最新viewport保持、100ms Idle再開、候補優先順維持 | 実装・単体確認 | `latestViewportIsUsedAfterOperationStops`、managerの`IdleResumeReady`、既存優先順テスト |
| 再ドラッグ、revision、dispose、foreground離脱による古い再開無効化 | 実装・単体確認 | `idleResumeIsCancelledByRedragRevisionChangeAndDispose`、coordinator token/job |
| HolderObserved、表示エラー、生成完了経路の操作中抑制 | 実装・単体確認 | `holderObservedAndDisplayErrorDoNotRestartGenerationDuringOperation`、生成完了分岐 |
| 無操作初回表示、カード→グリッド、drag/fling、停止後追従 | 既存Compose/実機回帰対象 | `MainActivityComposeTest.classifiedDisplayToggleSwitchesBetweenCardAndMediaGridAndSurvivesActivityRecreation`、`classifiedMediaGridSingleFlingKeepsLatestImageAndImmediateCellActionAfterFilter` |
| placeholder、形式、範囲、列数変更、Macrobenchmark | 変更なし | `MediaGridPlaceholderRendering.kt`、`MediaGridThumbnailStore.kt`、既存列数テスト、Macrobenchmark source setを変更していない |

検証順は第4実装要件に従い、隔離統合テストを先に実行し、その成功後に本番packageの安全上書きチェックを実行する。Macrobenchmarkは変更・実行しない。
