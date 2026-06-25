# 実機レベル統合テスト強化 カバレッジ

`実機レベル統合テスト強化 要件定義.md` に対する、現在の自動テスト・安全実行スクリプト・実機確認の対応状況です。

## 安全境界

| 要件 | 状態 | 証跡 |
|---|---|---|
| 本番/テスト applicationId、UID、DB、画像、Preferences 分離 | 完了 | `verifyTestEnvironmentIsolation`、`TestEnvironmentIsolationTest`、`run-safe-integration-check.ps1` |
| 本番 OAuth/X API をテストから利用不能 | 完了 | `DisabledOAuthGateway`、`DisabledXApiGateway`、loopback-only network config、設定画面の login 無効化 |
| 同一 SC-56C で本番・テスト共存、本番 metadata 不変 | 完了 | 2026-06-23 の安全スクリプト実行結果。production UID 10413、test UID 10146 |
| 本番 snapshot を片方向・一時コピーだけで検証 | 一部完了 | 実 DB snapshot の hash 不変は確認済み。実画像 backup は未提供のため fixture で機構のみ確認 |

## 大規模テスト構成

| # | 項目 | 状態 | 主な証跡 / 残件 |
|---:|---|---|---|
| 1 | テスト用アプリ分離 | 完了 | `TestEnvironmentIsolationTest` |
| 2 | バックアップ/コピー | 一部完了 | `SnapshotCompatibilityTest`。実画像 backup での最終確認が残る |
| 3 | DB/Repository 整合性 | 一部完了 | Room migration、同期、タグ削除、保存先失敗。検索条件の DB 一気通貫と全移動操作を追加予定 |
| 4 | データ破壊防止 | 主要項目完了 | 手動概要・タグ・分類保持、検索非変更、削除時投稿保護、画像失敗、保存先失敗 |
| 5 | Fake X API 同期 | 主要項目完了 | pagination、401/403/429/500、refresh 失敗、中断再開、欠落/不正 JSON、mixed media |
| 6 | API 料金/rate limit | 主要項目完了 | production URL 遮断、呼出履歴、月間停止、429 無限 retry 防止 |
| 7 | 検索/絞り込み | ロジック完了・UI残 | 全検索 target、regex、期間、投稿者、タグ複合、件数 summary、非変更。filter dialog E2E を追加予定 |
| 8 | 分類操作 E2E | 主要項目完了 | 付与・解除、DB・件数、popup 非伝播、別グループ同名タグの複数同時付与を確認 |
| 9 | タグ/グループ管理 E2E | 一部完了 | 作成、同名子タグ、tag 名称変更/削除、group 削除、循環/順序ロジック。group 名称変更・代表移動 UI が残る |
| 10 | スクロール/大量表示 | 実装済み・実機未確認 | 1,000 件末尾/先頭 UI、10,000 件 Room。端末再接続後に実行 |
| 11 | Macrobenchmark | 完了 | `scripts/run-safe-macrobenchmark-check.cmd` で SC-56C 上の `com.lyco256.llm.test.benchmark` cold/warm startup を測定し、本番 metadata 前後不変を確認 |
| 12 | 画像保存/圧縮 | 主要項目完了 | WebP、重複 download 防止、失敗時投稿保持、複数画像の別WebP保存、破損画像のfailed asset記録、snapshot |
| 13 | 設定/安全装置 | 一部完了 | 使用量表示、test login 無効、暗号化設定 roundtrip、無効保存先 rollback。保存先 cancel UI が残る |
| 14 | エラー表示/復旧 | 一部完了 | 空、未 login、HTTP/timeout/JSON、画像/保存先、continuation 再開。再起動復旧と権限不足 UI が残る |
| 15 | UI 状態・軽微バグ | 一部完了 | 主要 tab、dialog、件数、loading、empty、popup、LazyColumn、画面再作成と filter 戻る操作が残る |
| 16 | 回帰テスト枠 | 運用開始 | popup 外 tap、再同期、continuation、保存先失敗を回帰化。発見ごとに追加 |
| 17 | Property-based 候補 | 一部完了 | JUnit 固定 seed の 250 パターンで slot 移動の一意性・集合保存・位置を検証。同期不変条件の追加を検討 |

## 合格ゲート

- 通常必須: `assembleDebug`、`testDebugUnitTest`、`lintDebug`
- 実機必須: `verifyTestEnvironmentIsolation`、`connectedIntegrationTestAndroidTest`
- 安全実行入口: `scripts/run-safe-integration-check.cmd`
- Macrobenchmark 安全実行入口: `scripts/run-safe-macrobenchmark-check.cmd`
- snapshot 任意入口: `scripts/run-safe-snapshot-check.cmd`

実画像 backup、未網羅 E2E が残っている間は、要件定義全体を「完了」と判定しません。
