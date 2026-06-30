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
| 7 | 検索/絞り込み | ロジック完了・主要UI完了 | 全検索target、regex、期間、投稿者、タグ複合、件数summary、非変更。filter dialogのquery適用、日付条件適用/クリア、投稿者＋タグ条件の複合E2E、Cancel/Back破棄、全クリアE2Eを確認 |
| 8 | 分類操作 E2E | 主要項目完了 | 付与・解除、DB・件数、popup非伝播、別グループ同名タグの複数同時付与を確認 |
| 9 | タグ/グループ管理 E2E | 主要項目完了 | 作成、同名子タグ、tag名称変更/削除、group名称変更/削除、タグの別グループ移動UI、循環/順序ロジックを確認 |
| 10 | スクロール/大量表示 | 主要項目完了 | SC-56C実機で1,000件末尾/先頭UI、10,000件Room、50件リスクseedを確認 |
| 11 | Macrobenchmark | 完了 | `scripts/run-safe-macrobenchmark-check.cmd` で SC-56C 上の `com.lyco256.llm.test.benchmark` cold/warm startupを測定し、本番metadata前後不変を確認 |
| 12 | 画像保存/圧縮 | 主要項目完了 | WebP、重複download防止、失敗時投稿保持、複数画像の別WebP保存、破損画像のfailed asset記録、snapshot |
| 13 | 設定/安全装置 | 主要項目完了 | 使用量表示、test login無効、X API設定の保存/trim/消去UI、暗号化設定roundtrip、無効保存先rollback、保存先Dialogの閉じる操作とDB不変、保存先移動見積もりcancel UIを確認 |
| 14 | エラー表示/復旧 | 主要項目完了 | 空、未login、HTTP/timeout/JSON、権限不足UI、画像/保存先、continuation再開、Activity再作成後のタブ/フィルター復元、保存先移動copying中断とswitched切替先不可の起動時復旧を確認 |
| 15 | UI状態・軽微バグ | 一部完了 | 主要tab、dialog、件数、loading、empty、popup、LazyColumn、画面再作成、filter BackHandler破棄、画像viewerの表示/閉じる操作、ローカル削除Dialogのcancel/soft delete、概要編集の保存と再作成後復元、投稿者クリックから分類済み投稿者フィルターへの遷移とDB不変、いいね数ポップアップの詳細/暫定警告表示とDB不変、X API設定Dialogの保存/消去導線を確認。細部の追加E2Eを継続 |
| 16 | 回帰テスト枠 | 運用開始 | popup外tap、二重同期、continuation、保存先失敗を回帰化。発見ごとに追加 |
| 17 | Property-based 候補 | 主要項目完了 | JUnit固定seedの250パターンでslot移動の一意性・集合保存・位置を検証。同期不変条件としてローカル削除済み投稿の非復活、固定seed複数ページ同期の投稿ID一意性・使用量加算・continuation消去を確認 |

## 合格ゲート

- 通常必須: `assembleDebug`、`testDebugUnitTest`、`lintDebug`
- 実機必須: `verifyTestEnvironmentIsolation`、`connectedIntegrationTestAndroidTest`
- 安全実行入口: `scripts/run-safe-integration-check.cmd`
- Macrobenchmark安全実行入口: `scripts/run-safe-macrobenchmark-check.cmd`
- snapshot任意入口: `scripts/run-safe-snapshot-check.cmd`

実画像backup、本網羅E2Eが残っている間は、要件定義全体を「完了」と判定しません。
