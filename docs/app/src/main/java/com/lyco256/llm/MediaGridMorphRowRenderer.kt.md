# `MediaGridMorphRowRenderer.kt`

## 2026-08-01 Phase 2 production renderer

- `MediaGridMorphRowRenderModel` is claim/plan keyed and resolves image endpoints and header text layouts before visual activation. It does not rebuild when the resident draw-index version changes during an active Morph.
- The model exposes the complete source/end asset-ID protection set. The pure draw routine uses only immutable cells/headers and progress, with Placeholder background, same-slot `1-p/p` crossfade, and right-edge image/Placeholder transitions.

Phase 1 TEST_HARNESS same-surface renderer. It is installed as a draw modifier on the real `LazyVerticalGrid`, suppresses normal cell/header visuals while active, and crossfades source/target content in each current row rect with Placeholder endpoints. It does not compose another grid, Box overlay, or z-index surface.

## 2026-08-01 uniform lattice draw path

The production draw loop calculates one `currentCellSize` per frame, derives every media cell from the fixed focal row and relative row/column, and clips the complete surface to the LazyGrid viewport. It draws full offscreen edge cells instead of clamping them to the right boundary. Header bands interpolate height/offset and title layers independently from media-cell geometry; image endpoints remain same-slot Crossfade layers.
