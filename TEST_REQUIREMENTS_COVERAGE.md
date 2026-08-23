# 実機レベル統合テスト強化 カバレッジ

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
| 3 | DB/Repository 整合性 | 主要項目完了 | Room 7→8→9 migration、単一Undo slot永続、同期、タグ削除、保存先移動時のUndo slot保持、Repository Flow経由の複合検索DB一気通貫、Repository経由のタグ/グループ全移動操作 |
| 4 | データ破壊防止 | 主要項目完了 | 概要/OCR/tag relationのfield・差分Undo、タグ・グループ作成/編集/削除Undo、投稿hard DELETEの画像staging・復元・失敗rollback、検索非変更、画像失敗、保存先失敗 |
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
| 15 | UI状態・軽微バグ | 主要項目完了 | 主要tab、初回loading/empty、カード内タグdraftの適用・破棄、投稿者/保存数/いいね表示、画像高さ/preview、filter Treeと条件cycle、compactタグrow/縦guide、classified toolbar状態、共通Undo通知の5秒timeout・Swipe・失敗再試行・再作成を単体/Compose/交差回帰テストでカバー |
| 16 | 回帰テスト枠 | 運用開始 | popup外tap、二重同期、continuation、保存先失敗を回帰化。発見ごとに追加 |
| 17 | Property-based 候補 | 主要項目完了 | JUnit固定seedの250パターンでslot移動の一意性・集合保存・位置を検証。同期不変条件としてhard DELETE済み投稿がAPIに再登場した際の新規row取り込み、固定seed複数ページ同期の投稿ID一意性・使用量加算・continuation消去を確認 |
| 18 | メディアグリッド複数選択 | 主要項目完了 | 0件維持、×／戻る終了、0件時タグ編集無効、2〜6列Dialogボタン、7〜12列非表示、同一clipId選択同期、選択画像本体非変更、単一／複数画像チェック色、開始時のみハプティックを実装・単体／Compose／隔離実機ゲートで確認 |
| 19 | 共通Undo | 自動テスト実装済み | `UndoPayloadCodecTest`、`UndoDaoIntegrationTest`、`UndoCoordinatorIntegrationTest`、`RepositoryIntegrationTest`、`UndoNotificationUiTest`、`DurableClipDeleteUndoStoreTest` でcodec・DB・transaction・各逆操作・永続画像staging・UI競合をカバー |
| 20 | 要件20〜35交差回帰 | 自動テスト実装済み | `CrossFeatureRegressionUiTest` と各unit/Compose testでApply draft、author/like、media表示、filter Tree/状態色、compact row/縦guide、toolbar、Undo通知の組み合わせをカバー |
| 21 | メディアグリッド高速スクロールバー | 自動・隔離実機確認待ち | `MediaGridScrollbarTest` でordinal位置、端点、最小thumb、clamp、列数2〜12、drag session固定、frame無効化、cancel、最新target、正常完了/取消終了をカバー。request IDとdrag/final種別、UP直後の非dragging、同一target final、古いfinalの新session無効化、完了/取消/consumer cancel後のthumb復帰、全header boundaryのlabel・開始ordinal・fractionを確認。`MediaGridScrollPositionTest` で投稿日の日/週/月、特殊bucket、保存順非表示、全見出しピル位置のordinal順を確認。`MediaGridSessionCoordinatorTest` でscrollbar完了checkpointとscroll終了checkpointの重複を防止。`MediaGridScrollbarUiTest` で投稿日順の全見出しlabel同時表示、実pointer移動中の固定位置、いいね数順の全boundary、保存順非表示、旧single label／12dp×4dp UI削除、track右端配置、UP消去を確認する。既存の`MainActivityComposeTest.mediaGridScrollbarJumpsToTheEndAndBackWithoutTakingTheWholeRightEdge` と`MainActivityComposeTest.slowScrollbarReleaseThenNormalGridScrollKeepsThumbFollowingViewport` は維持する。`run-safe-integration-check.cmd` のSuccessが残件。 |
| 22 | OCR3/4全画面ビューア／polygon選択・個別編集／Fit／asset ID／polygon／zoom-pan | 完了 | `OcrViewerGeometryTest` でFit、letterbox、共通zoom/pan座標、viewport再計算、境界clip後形状のhit test、不正polygon除外、asset ID対応、内部優先・最小面積、12dp最近傍hit testを確認。`OcrSessionTest` でregion単位の再構成、空region（先頭・途中・空asset区切り）、polygonなしregion、asset ID＋region index編集、structured全文編集禁止、再構成済みdraftの保存経路伝達、再検出成功／失敗、保存失敗保持を確認。`OcrSessionDialogComposeTest` で単一region入力欄、別polygonの現在値、選択中overlay、全文再構成、選択解除、ページスワイプ、実際のpinch/pan終了の誤選択防止、拡大後短タップ、再検出失敗後の再選択、再検出時解除、保存／失敗／再オープンを確認。OCRと表示は同じraw Bitmapデコードを使い、EXIF自動回転差を排除。`run-safe-integration-check.cmd` と`run-safe-debug-check.cmd -InstallToDevice` がSuccess。 |

| 23 | OCR7 PP-OCRv6正式採用・高速／高精度モード | 完了 | 本命`codex/ocr-structured-result`へsmall/mediumのdetector・recognizer・configを同梱し、ML Kit・tile・比較metadataを撤去。品質モードのsession初期化・自動検出・非永続性・切替／再検出・共通結果変換・単一engine release/reuseをunit／Composeで固定。`PaddleOcrRuntimeSmokeTest`で実機offline small→medium→small、`OcrPaddleFlowIntegrationTest`で実機の高速自動検出→高精度再検出→polygon編集→キャンセル後の再オープン時高速初期化を確認。`run-safe-integration-check.cmd`、`run-safe-debug-check.cmd`、`run-safe-debug-check.cmd -InstallToDevice`がSuccess。 |
| 24 | OCR8 reading order／text group／全文range→polygon選択 | 自動テスト実装・統合実機確認済み。手動目視は未実施 | `OcrReadingOrderTest`で1.20倍方向判定、横／縦優勢、2×2順、隣接行／列group、alignment・サイズ差・10%上限・介在region veto、polygonなしregion、CJK／Latin連結を固定。`OcrStructuredTextSelectionTest`でpost range更新とseparator／polygonless非選択、`OcrViewerRevealTest`で最小pan／1倍下限、`OcrSessionDialogComposeTest`で全文タップ→既存region editorを確認。`run-safe-integration-check.cmd`と`run-safe-debug-check.cmd -InstallToDevice`はSuccess。横書き／縦書き／複数assetの手動目視は安全な自動入口がないため未実施。 |

## 合格ゲート

- 通常必須: debug／integration共通Build task集合、`testDebugUnitTest`、`lintDebug`。phase別入力と成功stateにより自動省略し、main入力不変時の変更unit test classだけは限定実行できる。判断不能時はphase全体へ戻る
- 実機必須: `verifyTestEnvironmentIsolation`、`connectedIntegrationTestAndroidTest`
- 安全実行入口: `scripts/run-safe-integration-check.cmd`
- USB／wireless共通の安全実行入口: `scripts/run-safe-integration-check.cmd`
- Macrobenchmark安全実行入口: `scripts/run-safe-macrobenchmark-check.cmd`
- snapshot任意入口: `scripts/run-safe-snapshot-check.cmd`
