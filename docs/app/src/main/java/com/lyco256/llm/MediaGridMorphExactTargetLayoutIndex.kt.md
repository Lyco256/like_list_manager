# MediaGridMorphExactTargetLayoutIndex.kt

## 2026-08-07 complete row materialization

- The compact index now carries one primitive asset ID per media ordinal. This lets a target row materialize every exact endpoint cell even when some cells fall outside the bounded source capture.
- The index still does not retain Bitmaps, prepared images, URLs, or full media entries.

## 2026-08-07 row/header identity correction

- `rowHeaderIndex` identifies a header only for the first media row immediately after that full-span item. Later rows in the same date/like-count bucket no longer inherit the section header.
- The exact index remains the source of target row IDs, media ordinals, item indexes, row tops, headers, and scroll bounds used by Morph planning and handoff.

## 2026-08-05

The exact target index reconstructs only the target LazyGrid item sequence for one adjacent column count. It records exact item indexes, row IDs, row-first indexes, media ordinals, row tops, full-span headers, content height, and max scroll. The pure viewport validator compares every visible target row, cell, and header within one pixel.
