# `MediaGridMorphTest.kt`

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
