# MediaGridMorphOverlay.kt

`MediaGridMorphOverlay` is a bounded, non-interactive overlay drawn above the one normal `LazyVerticalGrid` while a `MediaGridMorphSession` is in `Tracking`, either settle phase, or `AwaitingGridHandoff`.

- `MediaGridMorphRenderModel` is created once from the session plan and the planned item indexes. It retains the slot list and distinct Asset visual map; progress does not scan the full item list or rebuild requests.
- Slot Rects interpolate all four edges. A slot with a zero-width start or end grows/shrinks from the right edge without a second grid or `AnimatedContent`.
- The same Asset renders once. Different Assets crossfade their thumbnail and badges with `1-progress` / `progress`; a missing side remains a static gradient. Badge metrics are derived from the maximum slot width and the slot layer continuously scales them with the interpolated Rect.
- Only one already-present Thumbnail Manager StateFlow is observed per Asset ID, even when several slots refer to that Asset. The overlay never creates thumbnail work, checks files, fetches URLs, or decodes images. Ready files are shown through the existing 256px image loader.
- Video, like-count, selection, and error visuals are included in the same crossfade layer. The overlay has no input handlers.
- An opaque surface is drawn behind all morph content. Header bands are composed at maximum height, interpolate background/Y/occupied height in a clipped graphics layer, and crossfade changed titles in two layers (one for identical titles).
- Progress and handoff correction are read by graphics layers through a stable motion holder; the overlay model, slot/header keys, thumbnail sources, and Asset map are not recreated for each progress update.
