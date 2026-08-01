# `MediaGridMorphTest.kt`

## 2026-08-01 claim identity coverage

- Unit coverage rejects a stale Compose viewport identity at claim and accepts the identity from the real capture snapshot.

## 2026-08-01 Phase 1 row reflow coverage

- Unit coverage covers 2↔3, 4↔5, 5↔4, 8↔9, and 11↔12 reflow, right-edge Placeholder behavior, fixed focal row/Y, distance-ratio progress, header bands, and ordinal-first target selection with ordinal-fraction fallback.

## 2026-07-31 production Morph correction coverage

- Unit coverage includes explicit content endpoints, fixed initial focal correction, progress-zero correction, frame-clock settle timing, pointer-release fallback, and bounded target column behavior.

## 2026-07-30 gesture tracking／settle

- 初期距離÷現在距離、無効距離、既存dead zone／progressの0／0.25／0.5／0.75／1を検証する。
- 同一gestureの増加→dead zone→減少pair切替とprogress 0／correction 0の連続性を検証する。
- 正規化2次元focal点、X／Y中心移動、slot外、非0 viewport originの開始時非jumpを検証する。
- release progress 0.25／0.5、0／45／90／135／180msのrelease基準線形settle、current／target終端、exactly-once handoff、Awaiting維持、complete、stale generation拒否を検証する。

## 2026-07-31 gesture arbitration／readiness

- candidateのdirection判定が既存DeadZone、touchSlop `0.35`、centroid移動比 `0.5`を全て満たす場合だけclaimすることを検証する。
- candidate開始時のinitial distanceを固定したまま、pure panをclaimせず、方向反転後も全prepared pairを使ってprogressを継続する契約を検証する。
- production readinessがviewportへ入り得る正寸法側だけを必須とし、overscan・zero-size側・null Assetを要求しない契約はCompose側で検証する。

## 対応ソース

`app/src/test/java/com/lyco256/llm/MediaGridMorphTest.kt`

## 役割

メディアグリッドの現行release列数変更と、後続描画で使用するbounded prepared pair基盤をJVM単体テストで検証します。productionと同じ`buildMediaGridMorphPreparedPairs()`を使用します。

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
