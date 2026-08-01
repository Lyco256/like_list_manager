# `MediaGridRenderingContractTest.kt`

## 2026-08-01 Phase 1 renderer contract

- The production source path is checked for legacy one-step pinch and absence of the old production Morph host/translation.
- TEST_HARNESS is checked for the same-surface row renderer on the real `LazyVerticalGrid`, with no separate Morph overlay surface or second grid.

## Current production contract

The contract test fixes the Production gesture/host connection, explicit `ProductionVisible` Canvas mode, absence of `mediaGridPinchToResize`, and isolation of the TEST_HARNESS host while preserving resident draw, scheduler, queue, and publication contracts.

## 2026-07-30 Morph interaction production境界

`MediaGridMorphInteraction.kt`がTEST_HARNESS guardと`withFrameNanos` runnerを持つ一方、production `TagHierarchyUiV2.kt`がinteractive layer、controller、handoff requestを参照せず、既存`mediaGridPinchToResize`とrelease時列数callbackを維持することを静的に固定する。

## 対応ソース

`app/src/test/java/com/lyco256/llm/MediaGridRenderingContractTest.kt`

## 役割

メディアグリッドの軽量化済みproduction経路について、広範囲処理や廃止済み描画経路が再導入されていないことをJVM上の静的契約として検証します。

## Morph準備基盤の確認

- captureが`itemIndexByMediaOrdinal`とbounded ordinal loopを使用し、frame全件走査と`itemByKey`を使わないこと
- pure builderが`Dispatchers.Default`で実行され、pointer処理では現行release helperだけを使用すること
- `MediaGridMorphOverlay`、TEST_HARNESS用Morph Canvas、`TextMeasurer`、旧`MediaGridMorphWindow`／builderがproduction UIに存在しないこと
- TEST_HARNESS Canvasのdraw範囲がprogress／correctionだけを読み、resident Map、crop、TextMeasurer、collection変換、ImageRequest、draw indexへ触れないこと

既存のviewport ordinal境界、scheduler／publication定数、resident Canvas draw hot path、prepared index keyの静的契約も継続して確認します。
