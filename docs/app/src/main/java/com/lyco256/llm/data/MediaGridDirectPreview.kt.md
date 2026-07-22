# `MediaGridDirectPreview.kt`

`app/src/main/java/com/lyco256/llm/data/MediaGridDirectPreview.kt`

The file contains the background `MediaGridImagePreparer`. Candidate construction preserves persistent JPEG → local → preview → remote → display order, removes invalid local files and duplicate sources, and calculates file metadata, identities, and cache keys away from composition.

The preparer is stateless across frames. `MediaGridSteadyLoadController` owns the current frame's indexed prepared metadata and calls `prepareCell` within its fixed startup/tick budgets. Persistent JPEG metadata is checked on IO, including the canonical preview path, 256×256 JPEG dimensions, non-empty output, and source freshness. The frame key on each result prevents stale results from being applied after a render-key change.

Persistent JPEG candidates always use 256×256 requests, the `media-grid-preview-v1` memory-key namespace, and disabled Coil disk cache. Local, preview, remote, and display candidates keep their existing size-aware cache behavior. `MediaGridPreviewNotifier` is process-local and lets the controller invalidate only the changed asset after worker publication or deletion. The former viewport-driven `MediaGridPreviewPreloader` is not used by the product path.
