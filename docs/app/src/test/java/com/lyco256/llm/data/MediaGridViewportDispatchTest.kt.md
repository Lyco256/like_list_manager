# `MediaGridViewportDispatchTest.kt`

## 2026-07-19 第5実装: cache hydration unit coverage

- Verifies visible and adjacent cache hits become `Ready` without generation, while only misses enter the existing generation sequence.
- Verifies one cache identity is checked once per hydration, known misses are not restatted within a source revision, and hydration completes before generation starts.
- Verifies Dragging cancellation and stale-result rejection, followed by a fresh Idle hydration using the latest viewport.
- `MediaGridThumbnailStoreKeyTest.kt` covers the canonical local/preview/remote key paths and local size/modified-time identity changes.
- The same key test rejects zero-length, structurally corrupt JPEG, and temporary files as cache files.
- The same key test rejects zero-length, structurally corrupt JPEG, and temporary files as cache files.

2026-07-18 coverage:

- pixel-only movement is suppressed while visible order, columns, and revision changes are accepted;
- consecutive viewport notifications during one generation use the latest viewport after completion;
- stale revision and dispose notifications are rejected;
- holder state reads remain available after intentionally stopping the coordinator;
- visible, adjacent, and wide candidates keep their priority and bounded range;
- source identity changes reset the stable holder, and display failure retries once before final failure;
- the existing one-adjacent-row preparation range remains unchanged.

2026-07-19 第4実装 coverage:

- rejected viewport resend after source registration and first non-empty viewport after an empty layout;
- duplicate accepted snapshots are suppressed without starting duplicate generation;
- Dragging/Flinging suppression, one normal completion without chaining, wide cancellation without completion bookkeeping;
- latest viewport selection after operation stop, 100ms Idle resume, and invalidation by re-drag, revision, dispose, or foreground loss;
- HolderObserved and display-error paths remain suppressed during operation.
