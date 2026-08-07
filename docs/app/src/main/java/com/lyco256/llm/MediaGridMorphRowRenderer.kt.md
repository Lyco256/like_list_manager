# `MediaGridMorphRowRenderer.kt`

## 2026-08-07 shared endpoint geometry

- Cell and header rectangle calculation is shared by production drawing and TEST_HARNESS source/target viewport observations, preventing the regression assertion from using a separate geometry formula.
- `mediaGridMorphHeaderBlend` is the shared production/test rule for date and like-count section headers. Appearing or disappearing header height lerps between `0` and the measured header height at the same progress as the grid morph; its text alpha fades between `0` and `1`. Changed titles crossfade, while an unchanged title remains a single opaque layer.
- The achievable-target scroll-bound adjustment is applied continuously as `adjustment * progress` to both media rows and section headers, preventing a final-frame position jump.

## 2026-08-05 current required render set

- `MediaGridMorphPlan.requiredRenderSet()` is shared by completeness, render-model filtering, protected assets, and claim reports. It includes only cells and headers whose swept endpoint geometry intersects the viewport; offscreen overscan is optional.
- A selected render model contains required cells plus header geometry for the full bounded plan; only swept headers resolve text before claim, so optional offscreen header backgrounds remain independent from text readiness. It reports required/resolved counts plus optional offscreen count.

## 2026-08-02 selected-direction readiness

The claim bundle contains both adjacent directions, but completeness is evaluated for the direction that claims first. A complete selected direction publishes Morph; movement into the opposite direction keeps the same source/target model and Morph draw mode at progress 0 rather than switching models.

## 2026-08-01 Phase 2 production renderer

- `MediaGridMorphRowRenderModel` is claim/plan keyed and resolves image endpoints and header text layouts before visual activation. It does not rebuild when the resident draw-index version changes during an active Morph.
- The model exposes the complete source/end asset-ID protection set and explicit completeness counts for required/resolved source and target images, unresolved Asset ID, header text, and final readiness. The pure draw routine uses only immutable cells/headers and progress. It paints a Placeholder background only for `NoMedia`/compatibility Placeholder endpoints; a missing required `Media` image is not silently converted to Placeholder.

Phase 1 TEST_HARNESS same-surface renderer. It is installed as a draw modifier on the real `LazyVerticalGrid`, suppresses normal cell/header visuals while active, and crossfades source/target content in each current row rect with Placeholder endpoints. It does not compose another grid, Box overlay, or z-index surface.

## 2026-08-01 uniform lattice draw path

The production draw loop calculates one `currentCellSize` per frame, derives every media cell from the fixed focal row and relative row/column, and clips the complete surface to the LazyGrid viewport. It draws full offscreen edge cells instead of clamping them to the right boundary. Header bands interpolate height/offset and title layers independently from media-cell geometry; image endpoints remain same-slot Crossfade layers.
