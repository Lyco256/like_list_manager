# 実機レベル統合テスト 要件カバレッジ

`実機レベル統合テスト強化 要件定義.md` に対する現在の自動テスト証跡と残件です。完了判定はテスト名、Gradle report、実機package情報で行い、スクリーンショット目視は使いません。

## 安全境界

| 要件 | 状態 | 証跡 |
|---|---|---|
| 本番/テストapplicationId、UID、DB、画像、Preferences分離 | 完了 | `verifyTestEnvironmentIsolation`、`TestEnvironmentIsolationTest`、`run-safe-integration-check.ps1` |
| 本番OAuth/X APIをテストから利用不能 | 完了 | `DisabledOAuthGateway`、`DisabledXApiGateway`、loopback-only network config、設定画面のlogin無効化 |
| 同一SC-56Cで本番・テスト共存、本番metadata不変 | 完了 | 2026-06-23の安全スクリプト実行結果。production UID 10413、test UID 10146 |
| 本番snapshotを片方向・一時コピーだけで検証 | 一部完了 | 実DB snapshotのhash不変は確認済み。実画像backupは未提供のためfixtureで機構のみ確認 |

## 大規模テスト構成

| # | 領域 | 状態 | 主な証跡 / 残件 |
|---:|---|---|---|
| 1 | テスト用アプリ分離 | 完了 | `TestEnvironmentIsolationTest` |
| 2 | バックアップ/コピー | 一部完了 | `SnapshotCompatibilityTest`。実画像backupでの最終確認が残る |
| 3 | DB/Repository整合性 | 一部完了 | Room migration、同期、タグ削除、保存先失敗。検索条件のDB一気通貫と全移動操作を追加予定 |
| 4 | データ破壊防止 | 主要項目完了 | 手動概要・タグ・分類保持、検索非変更、削除時投稿保護、画像失敗、保存先失敗 |
| 5 | Fake X API同期 | 主要項目完了 | pagination、401/403/429/500、refresh失敗、途中再開、欠落/不正JSON、mixed media |
| 6 | API料金/rate limit | 主要項目完了 | production URL遮断、呼出履歴、月間停止、429無限retry防止 |
| 7 | 検索/絞り込み | ロジック完了・UI残 | 全検索target、regex、期間、投稿者、タグ複合、件数summary、非変更。Filter dialog E2Eを追加予定 |
| 8 | 分類操作E2E | 一部完了 | 付与/解除、DB・件数、popup非伝播。同名タグの実付与と複数タグ付与が残る |
| 9 | タグ/グループ管理E2E | 一部完了 | 作成、同名子タグ、tag名称変更/削除、group削除、循環/順序ロジック。group名称変更・代表移動UIが残る |
| 10 | スクロール/大量表示 | 実装済み・実機未確認 | 1,000件末尾/先頭UI、10,000件Room。端末再接続後に実行 |
| 11 | Macrobenchmark | 未着手 | benchmark moduleと基準値の取得が残る |
| 12 | 画像保存/圧縮 | 一部完了 | WebP、重複download防止、失敗時投稿保持、snapshot。複数画像と破損画像表示を追加予定 |
| 13 | 設定/安全装置 | 一部完了 | 使用量表示、test login無効、暗号化設定roundtrip、無効保存先rollback。保存先cancel UIが残る |
| 14 | エラー表示/復旧 | 一部完了 | 空、未login、HTTP/timeout/JSON、画像/保存先、continuation再開。再起動復旧と権限不足UIが残る |
| 15 | UI状態/軽微バグ | 一部完了 | 主要tab、dialog、件数、loading、empty、popup、LazyColumn。画面再作成とfilter戻る操作が残る |
| 16 | 回帰テスト枠 | 運用開始 | popup外tap、再同期、continuation、保存先失敗を回帰化。発見ごとに追加 |
| 17 | Property-based候補 | 一部完了 | JUnit固定seedの250パターンでslot移動の一意性・集合保存・位置を検証。同期不変条件の追加を検討 |

## 合格ゲート

- 常時必須: `assembleDebug`、`testDebugUnitTest`、`lintDebug`
- 実機必須: `verifyTestEnvironmentIsolation`、`connectedIntegrationTestAndroidTest`
- 安全実行入口: `scripts/run-safe-integration-check.cmd`
- snapshot任意入口: `scripts/run-safe-snapshot-check.cmd`

実画像backup、Macrobenchmark、未網羅E2Eが残っている間は、要件定義全体を「完了」と判定しません。
