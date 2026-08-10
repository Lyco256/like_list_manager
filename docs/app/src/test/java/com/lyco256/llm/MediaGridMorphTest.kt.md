# `MediaGridMorphTest.kt`

## 2026-08-10 Phase 3 Ready Snapshot contract

- pure coverageはdistinct visible row一件ごとのfocal entry、pinch Y primitive lookup、plan／RequiredRenderSet identity再利用、bounded required Asset unionを固定する。
- resource readiness copy後もcapture、direction snapshot、focal entry、plan、RequiredRenderSetが同一objectであることを確認し、membership更新がgeometryを再構築しない契約を固定する。
- Stable-idle anchorからscroll offsetだけを1px変えたclaimはresource処理前にfast pathを拒否し、live fallback対象になる。

## 2026-08-09 Phase 1 preparation cache contract

- The preparation-cache test verifies urgent asset requests are stable-set deduplicated per preparation identity and reissued only when the asset set or identity changes.

## 2026-08-07 immediate reverse handoff

- A fixed synthetic 4-to-5 claim verifies that successful handoff completion sends the protected asset union to carryover rather than release.
- The same fixed bundle verifies pointer cancellation sends the union to normal release and never to carryover. This reproduces the timing contract without device data or waiting for image workers.

## 2026-08-07 section-header appearance/disappearance

- The row-reflow header test now selects an actual appearing or disappearing date/like-count section header and checks progress `0`, `0.25`, `0.5`, `0.75`, and `1`. Height must be the exact linear interpolation between zero and the measured endpoint, while text alpha follows the same progress in the appropriate direction.
- Every available target row in that plan must use the exact target index's media ordinals, and the scroll-bound adjustment must remain continuous between progress `0.999` and `1`.

## 2026-08-07 viewport and restore regressions

- Pure coverage verifies that the local range includes the complete wider adjacent viewport and that an exact Morph handoff cannot be overwritten by the legacy fallback anchor restore.

## 2026-08-05 regressions

- Pure tests cover one focal center per actual visible source row, RequiredRenderSet swept cell/header boundaries, visible-row canonical matching without global row-count equality, the full header-sequence target index, and exact viewport-local target validation.

## 2026-08-02 row alignment and direction lock coverage

- Unit coverage checks every start offset for 2..12 columns, same-bucket preceding metadata, bucket-boundary reset, and right-sided bounded-row completion using the common source/target row builder.
- A headerless four-column bounded range beginning at offset 2 covers the former `0011 / 1100` phase error and verifies that all visible source image endpoints remain present at progress 0.01, 0.1, 0.25, 0.5, and 0.75.
- Controller coverage fixes one gesture to its first claimed direction, keeps plan/model/Morph through dead-zone and opposite-side travel, resumes distance-ratio progress when returning, and does not release protection before physical up.

## 2026-08-02 claim/release coverage

- Unit coverage confirms a complete selected direction can claim while the opposite direction is incomplete; reversing into the incomplete direction does not replace the active plan/model or leave Morph draw mode.
- Release assertions use the canonical initial/release distance ratio and the `0.5` threshold for both increase and decrease.

## 2026-08-01 uniform lattice coverage

- Unit coverage checks 2↔3, 4↔5, 5↔4, 8↔9, and 11↔12 at representative progress values for equal square cell size and grid-left X coordinates.
- Dedicated assertions keep the removed 5th column square and offscreen in 5→4, keep edge additions Placeholder-backed, and verify that header interpolation does not alter media-cell height or create an interior zero-height row.

## 2026-08-01 Phase 2 coverage

- Row-plan geometry covers 2↔3, 4↔5, 5↔4, 8↔9, and 11↔12, including right-edge Placeholder transitions, focal-row clamping, header bands, and distance/cell-width progress.
- Controller tests cover the one-frame `RevealCurrent` barrier and exactly-once handoff request behavior.

## 2026-08-01 claim identity coverage

- Unit coverage rejects a stale Compose viewport identity at claim and accepts the identity from the real capture snapshot.

## 2026-08-01 Phase 1 row reflow coverage

- Unit coverage covers 2↔3, 4↔5, 5↔4, 8↔9, and 11↔12 reflow, right-edge Placeholder behavior, fixed focal row/Y, distance-ratio progress, header bands, and exact-index target selection without ordinal-fraction fallback.

## 2026-07-31 production Morph correction coverage

- Unit coverage includes explicit content endpoints, fixed initial focal correction, progress-zero correction, frame-clock settle timing, pointer-release fallback, and bounded target column behavior.

## 2026-07-30 gesture tracking／settle

- 初期距離÷現在距離、無効距離、既存dead zone／progressの0／0.25／0.5／0.75／1を検証する。
- 同一gestureのclaim方向→dead zone→反対側→claim方向でpair/model/draw modeを固定し、progress 0から距離比へ連続復帰する。
- 正規化2次元focal点、X／Y中心移動、slot外、非0 viewport originの開始時非jumpを検証する。
- release progress 0.25／0.5、設定durationの0／1/4／1/2／3/4／完了時点におけるrelease基準線形settle、current／target終端、exactly-once handoff、Awaiting維持、complete、stale generation拒否を検証する。

## 2026-07-31 gesture arbitration／readiness

- candidateのdirection判定が既存DeadZone、touchSlop `0.35`、centroid移動比 `0.5`を全て満たす場合だけclaimすることを検証する。
- candidate開始時のinitial distanceを固定したまま、pure panをclaimせず、claim後の方向反転でも最初のprepared pair/modelを使ってprogress 0を維持し、元方向で継続する契約を検証する。
- production readinessがviewportへ入り得る正寸法側だけを必須とし、overscan・zero-size側・null Assetを要求しない契約はCompose側で検証する。

## 対応ソース

`app/src/test/java/com/lyco256/llm/MediaGridMorphTest.kt`

## 役割

メディアグリッドの現行release列数変更と、後続描画で使用するbounded prepared pair基盤をJVM単体テストで検証します。row reflow系はproductionと同じ`buildMediaGridMorphRowPreparedPairs()`を使用し、legacy slot系は互換性テストとして分離しています。

## 主な確認

- `mediaGridColumnCountAfterPinchRelease` の閾値未満、最終累積比率、方向反転、キャンセル、2〜12列境界、1回の操作で最大1列という契約
- 2↔3、4↔5、8↔9、11↔12における行・column位置slotと、同一Assetを別slotへ追跡しない契約
- 右端幅0、Asset ID重複禁止、Rect連続補間、同一／異なるAssetの画像layerとalpha
- 2〜12列すべてのtarget正方形、start visible実測維持、start overscan正方形、代表的な増減方向の0／0.5／1での非交差
- 負／正のviewport originをCanvasローカル座標へ変換し、correctionを一度加える式
- media ordinal境界を優先するheader対応、日↔週↔月、いいね数bucket、追加・削除、文字Crossfade、全幅geometry
- 10,000件でもvisible＋上下2行に制限されるordinal範囲、bucket途中の偽header防止、2列／12列の片方向pair
- 初期identity一回、pixel offset同一時skip、idle境界更新、scroll／pointer中の抑止、frame／column／viewport／sort／revision更新
- generation tokenによるstale結果拒否、prepared templateを再構築しないgesture-time anchor選択、state machineのrevision cancelとhandoff一回性

現行productionはprepared pairを描画へ使わず、ピンチ経路は列数変更ヘルパーだけを使用します。
