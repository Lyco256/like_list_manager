# `MediaGridRenderingContractTest.kt`

現在位置ピルの契約として、既存の軽量viewport ordinalとbucket生成だけを使い、frame全走査、Repository／File／画像処理を現在位置算出へ追加していないことも静的に確認します。

## 2026-08-10 Phase 2 claim/draw contract

- Production claimがcached selected pairを使い、複数pair builderを呼ばないことを固定する。
- Template selectionから`filter/groupBy/indexOfFirst/toMap/associate/mapNotNull`を排除し、draw loopからRect/blend helperを排除したことを静的に検査する。
- frame全header title収集の再導入を拒否する。
- classified filter toolbarはactive filterで無効化せず、`highlighted = filters.hasActiveFilters` で適用状態だけを表す静的契約を固定する。

## 2026-08-09 Phase 1 scroll contract

- Static contracts keep normal scroll on the anchor-only path, reject full-dataset visible-index filtering for fallback preload, and keep detailed Morph capture out of the scroll observer.

## 2026-08-08 normal draw allocation contract

- production single surfaceのNormal／Reveal branchが`LazyGridLayoutInfo.visibleItemsInfo`を直接走査し、scrollごとの`MediaGridResidentDrawCommand` list／objectを生成しないことをsource contractで固定する。
- Morph endpoint protectionとresident prepared image lookupは従来どおり維持する。

## 2026-08-07 shared renderer helper

- The uniform-lattice static contract follows the shared cell-geometry helper used by both production drawing and endpoint observation.

## 2026-08-01 Phase 2 coverage

- Static contracts assert one production gesture path, one resident/Morph surface, cache-phase resident command preparation, frozen row rendering, full endpoint protection, and removal of the legacy production pinch/overlay route.

## 2026-08-01 Phase 1 renderer contract

- The production source path is checked for legacy one-step pinch and absence of the old production Morph host/translation.
- TEST_HARNESS is checked for the same-surface row renderer on the real `LazyVerticalGrid`, with no separate Morph overlay surface or second grid.

## Current production contract

The contract test fixes the shared Production gesture path, the production handoff effects on the unified LazyGrid surface, absence of `ProductionVisible` and `mediaGridPinchToResize`, and isolation of the TEST_HARNESS Canvas host while preserving resident draw, scheduler, queue, and publication contracts.

## 2026-07-30 Morph interaction production境界（履歴: 現行経路では不使用）

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
