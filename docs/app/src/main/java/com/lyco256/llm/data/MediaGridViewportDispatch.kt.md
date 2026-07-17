# `MediaGridViewportDispatch.kt`

Defines the immutable viewport snapshot and the latest-value dispatcher used by the media-grid thumbnail manager.

- `MediaGridViewportSnapshot` contains source revision, column count, and visible media items.
- `LatestValueDispatcher` uses one serial worker and replaces an unprocessed value with the newest value.
- `MediaGridViewportRevisionGate` rejects stale revisions and duplicate snapshots.
- `mediaGridViewportUiIndices` preserves the existing visible-range plus one adjacent row rule.
