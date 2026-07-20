# `MediaGridDirectPreview.kt`

`app/src/main/java/com/lyco256/llm/data/MediaGridDirectPreview.kt`

The file contains the single background `MediaGridImagePreparer`. Candidate construction preserves local → preview → remote → display order, removes invalid local files and duplicate sources, and calculates local path/length/mtime identities and size-aware cache keys away from composition.

The preparer receives the current keyed frame and visible media-cell indexes, then prepares visible cells first and at most one adjacent row using the frame's prebuilt media-cell index column. `collectLatest` cancellation discards stale viewport/direction work; the frame key on each result prevents stale results from being applied after a render-key change. Prepared models contain the Coil request data and cache keys, so cells only construct requests from prepared metadata.
