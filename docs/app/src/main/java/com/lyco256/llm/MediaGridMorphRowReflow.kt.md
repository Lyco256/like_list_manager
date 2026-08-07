# `MediaGridMorphRowReflow.kt`

## 2026-08-07 exact target sequence

- Source canonical rows and target rows are materialized from `MediaGridMorphExactTargetLayoutIndex` row IDs and row-media ordinals. Exact asset IDs complete a row beyond the bounded captured resources, so a 4-to-5 endpoint cannot silently lose its rightmost cells.
- The current-column exact index replaces bounded preceding-window modulo alignment for source canonical rows. Long date/like-count sections therefore use the same row boundaries as the real LazyGrid instead of rejecting the Morph claim.
- Canonical row identity uses the section bucket key on every row; the full-span header remains a separate `headerBefore` identity attached only to its immediately following row.
- A captured source header maps only to the canonical row containing its exact first media ordinal.
- Scroll-bound target-row adjustment is multiplied by Morph progress. It no longer appears as a terminal-only displacement at progress 1.

## 2026-08-05 current source/target row contract

- Only rows marked as actual visible source rows select a focal center or participate in validation. Each such row is matched one-to-one by `MediaGridMorphSourceRowKey`; every visible cell must match the canonical row at the same column, media ordinal, and asset ID. Extra canonical overscan rows are allowed and do not reject the claim.
- `MediaGridMorphExactTargetLayoutIndex` supplies exact target row IDs, first item indexes, media ordinals, header positions, content height, and clamped achievable row tops for the adjacent target column count.
- All plan geometry is viewport-local. The target-row adjustment is interpolated through Morph progress so the animation reaches the achievable target row continuously; the handoff then verifies the real LazyGrid viewport.

## 2026-08-02 aligned source/target rows

- Source and target use the same `buildMediaGridMorphRowsForColumnCount()` alignment rule. The bounded source offset is preserved instead of packing the first media into column 0.
- The actual visible row list supplies geometry and focal selection only. Its ordinals must map to one canonical source row; row content and relative-row correspondence use the canonical current-column rows, while target rows use the same aligned ordinal stream.
- A partial first row has no left-side empty cells, bucket changes flush and restart at column 0, and only the right side may be short at the bounded end.

## 2026-08-02 canonical release decision

Morph, fallback, and release of an unprepared reverse direction share `mediaGridMorphCanonicalReleaseDecision()`. It derives direction from the initial/release pointer-distance ratio, selects at most one adjacent column, computes progress from the source/target cell-width ratio, and applies the existing `0.5` release threshold.

Production row-reflow planning selects the source row and focal media from the claim-time capture, then uses the exact adjacent-column layout index for the target row ID, first item index, row top, content height, and scroll bounds. Media geometry uses `sourceCellSize = viewport.width / N`, `targetCellSize = viewport.width / M`, a common interpolated cell size, a fixed initial pinch-center Y, and relative rows. Header heights and cumulative offsets are independent bands; ordinal-fraction and bounded/global target approximations are not used. The renderer does not use per-row source/target rectangles or zero-height media rows.

## 2026-08-01 uniform lattice geometry

- The plan carries source/target cell size, fixed focal center, `focalV`, and the bounded relative-row range for both endpoint viewport coverage plus overscan.
- Each relative row/column keeps source and target content endpoints in the same screen slot. The current rectangle is derived at draw time from the common cell size, grid-left origin, relative row, column, and interpolated header offset.
- Right-edge additions/removals retain full square geometry and are hidden or revealed by the LazyGrid viewport clip. Header height interpolation never changes media-cell height.
