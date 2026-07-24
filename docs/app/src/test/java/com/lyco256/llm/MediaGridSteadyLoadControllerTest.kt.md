# `MediaGridSteadyLoadControllerTest.kt`

## 2026-07-24 starvation regression coverage

- The isolated controller integration harness uses fake metadata and bitmap boundaries, including cache-hit request counting, metadata retry/failure, controller snapshots, and invariant checks.
- It covers a 1,000-asset cache-hit run and a fixed-seed 1,000-operation state-machine sequence across viewport changes, pause/resume, invalidation, and completion races.

`MediaGridSteadyLoadController`とsession keyの固定契約をローカルUnit Testで検証します。

- startup状態の順序
- 50ms tick、metadata 2件、request 1件、completion 4件、同時request 2件の固定値
- viewport、下方向1画面、下方向2画面のwarm-up順序
- 先頭の上方向除外、128件・32MiB上限
- 列数・source revisionを除外したsession identityと明示条件変更時のidentity分離
- 表示範囲＋前後1行のactive bitmap window
- viewport anchorのcontent equality
- active window外のUI load state破棄

実request、Compose表示、スクロール、pause/resume、候補fallback、preview回復は既存の隔離Integration Test群とともに安全スクリプトから検証します。
## 2026-07-24 第16実装

## 2026-07-24 ordinal background queues

- media ordinal indexのmedia-cell対応、asset／ordinal／item indexの双方向参照、anchor snapshotのcenter ordinalを確認する。
- BitSet pending setのadd/remove/clear、nearest順、同距離の下方向優先、watermark前のpending保持を確認する。
- controllerの既存固定契約に加え、urgent FIFO、invalidation、frame更新、cache hit、fallback、画面外task継続は隔離Integration Test群と静的確認で回帰検証する。

- 固定tick/request-per-tick前提を削除し、metadata/Bitmapの総並列数、background上限、urgent予約、hidden時Bitmap上限を固定契約として確認する。
- memory cache 75% high watermarkと65%未満の再開境界を純粋関数で確認する。
- 既存の初回warmup範囲、前後1行active window、frame key、scroll anchor、load state retentionの回帰確認を維持する。
