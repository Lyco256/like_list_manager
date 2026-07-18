# `MediaGridViewportDispatch.kt`

- `MediaGridViewportSnapshot` contains source revision, column count, and visible media items.
- `sameStructureAs` compares only stable asset ID/source index order, column count, and revision; pixel-only distance changes are ignored.
- `MediaGridViewportRevisionGate` retains latest-value and stale-revision rejection.
- `mediaGridViewportUiIndices` preserves the existing visible-range plus one adjacent-row rule.
- Viewport conflate and coordinator event ownership are implemented by `MediaGridThumbnailManager`; this file contains no scheduler worker.
