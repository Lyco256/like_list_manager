# `MediaGridMorphRowReflow.kt`

## 2026-08-05 current source/target row contract

- Visible source rows are matched one-to-one by `MediaGridMorphSourceRowKey`; every visible cell must match the canonical row at the same column, media ordinal, and asset ID. Extra bounded canonical rows are allowed.
- `MediaGridMorphExactTargetLayoutIndex` supplies exact target row IDs, first item indexes, media ordinals, header positions, content height, and clamped achievable row tops for the adjacent target column count.
- All plan geometry is viewport-local. The target-row adjustment is applied at the terminal target geometry so the focal Y remains fixed during the ordinary Morph progress; the handoff then verifies the real LazyGrid viewport.

## 2026-08-02 aligned source/target rows

- Source and target use the same `buildMediaGridMorphRowsForColumnCount()` alignment rule. The bounded source offset is preserved instead of packing the first media into column 0.
- The actual visible row list supplies geometry and focal selection only. Its ordinals must map to one canonical source row; row content and relative-row correspondence use the canonical current-column rows, while target rows use the same aligned ordinal stream.
- A partial first row has no left-side empty cells, bucket changes flush and restart at column 0, and only the right side may be short at the bounded end.

## 2026-08-02 canonical release decision

Morph, fallback, and release of an unprepared reverse direction share `mediaGridMorphCanonicalReleaseDecision()`. It derives direction from the initial/release pointer-distance ratio, selects at most one adjacent column, computes progress from the source/target cell-width ratio, and applies the existing `0.5` release threshold.

Production row-reflow planning selects the source row and focal media from the claim-time capture, then builds a bounded target row/header plan while keeping geometry independent from asset identity. Media geometry uses `sourceCellSize = viewport.width / N`, `targetCellSize = viewport.width / M`, a common interpolated cell size, a fixed initial pinch-center Y, and relative rows. Header heights and cumulative offsets are independent bands; target anchor information remains only for handoff. The renderer does not use per-row source/target rectangles or zero-height media rows.

## 2026-08-01 uniform lattice geometry

- The plan carries source/target cell size, fixed focal center, `focalV`, and the bounded relative-row range for both endpoint viewport coverage plus overscan.
- Each relative row/column keeps source and target content endpoints in the same screen slot. The current rectangle is derived at draw time from the common cell size, grid-left origin, relative row, column, and interpolated header offset.
- Right-edge additions/removals retain full square geometry and are hidden or revealed by the LazyGrid viewport clip. Header height interpolation never changes media-cell height.
