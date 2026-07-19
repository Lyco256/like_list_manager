# `MediaGridDirectPreview.kt`

`app/src/main/java/com/lyco256/llm/data/MediaGridDirectPreview.kt`

The file contains the direct media-grid image pipeline. Candidate construction filters empty values, missing local files, and duplicate sources in local → preview → remote → display order. Local identities include absolute path, length, and last-modified time; URL identities include asset ID, media key, and URL. `mediaGridImageCacheKey` adds the requested display width and height.

`MediaGridPrefetchController` owns only targetless `ImageLoader.enqueue` requests for the adjacent row. It cancels requests that no longer belong to the current source revision, viewport, column count, or direction and never reports prefetch failures as cell errors.
