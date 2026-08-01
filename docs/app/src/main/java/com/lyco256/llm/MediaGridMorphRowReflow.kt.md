# `MediaGridMorphRowReflow.kt`

Production row-reflow planning selects the source row and focal media from the claim-time capture, then builds a bounded target row/header plan while keeping geometry independent from asset identity. Media geometry uses `sourceCellSize = viewport.width / N`, `targetCellSize = viewport.width / M`, a common interpolated cell size, a fixed initial pinch-center Y, and relative rows. Header heights and cumulative offsets are independent bands; target anchor information remains only for handoff. The renderer does not use per-row source/target rectangles or zero-height media rows.

## 2026-08-01 uniform lattice geometry

- The plan carries source/target cell size, fixed focal center, `focalV`, and the bounded relative-row range for both endpoint viewport coverage plus overscan.
- Each relative row/column keeps source and target content endpoints in the same screen slot. The current rectangle is derived at draw time from the common cell size, grid-left origin, relative row, column, and interpolated header offset.
- Right-edge additions/removals retain full square geometry and are hidden or revealed by the LazyGrid viewport clip. Header height interpolation never changes media-cell height.
