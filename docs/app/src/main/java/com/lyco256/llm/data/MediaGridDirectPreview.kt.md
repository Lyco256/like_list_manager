# `MediaGridDirectPreview.kt`

`app/src/main/java/com/lyco256/llm/data/MediaGridDirectPreview.kt`

The file contains the single background `MediaGridImagePreparer`. Candidate construction now preserves persistent JPEG → local → preview → remote → display order, removes invalid local files and duplicate sources, and calculates file metadata, identities, and cache keys away from composition.

The preparer receives the current keyed frame and visible media-cell indexes, then prepares visible cells first and at most one adjacent row using the frame's prebuilt media-cell index column. Persistent JPEG metadata is checked in the IO preparer, including the canonical preview path, 256×256 JPEG dimensions, non-empty output, and source freshness. `collectLatest` cancellation discards stale viewport/direction work; the frame key on each result prevents stale results from being applied after a render-key change.

Persistent JPEG candidates always use 256×256 requests, the `media-grid-preview-v1` memory-key namespace, and disabled Coil disk cache. Local, preview, remote, and display candidates keep their existing size-aware cache behavior. `MediaGridPreviewPreloader` deduplicates memory-cache keys and cancels obsolete next-row requests; it never preloads fallback URLs. `MediaGridPreviewNotifier` is process-local and lets the current visible asset be invalidated and prepared again after worker publication or deletion.
