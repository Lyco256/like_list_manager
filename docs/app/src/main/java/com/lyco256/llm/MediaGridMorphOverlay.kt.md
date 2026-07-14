# MediaGridMorphOverlay.kt

`MediaGridMorphOverlay` is a bounded, non-interactive overlay drawn above the one normal `LazyVerticalGrid` while a `MediaGridMorphSession` is in `Tracking`, either settle phase, or `AwaitingGridHandoff`.

- `MediaGridMorphRenderModel` is created once from the session plan and the planned item indexes. It retains the slot list and distinct Asset visual map; progress does not scan the full item list or rebuild requests.
- Slot Rects interpolate all four edges. A slot with a zero-width start or end grows/shrinks from the right edge without a second grid or `AnimatedContent`.
- The same Asset renders once. Different Assets crossfade their thumbnail and badges with `1-progress` / `progress`; a missing side remains a static gradient.
- Only an already-present Thumbnail Manager StateFlow is observed through `stateIfPresent`. The overlay never creates thumbnail work, checks files, fetches URLs, or decodes images. Ready files are shown through the existing 256px image loader.
- Video, like-count, selection, and error visuals are included in the same crossfade layer. The overlay has no input handlers.
- Header bands interpolate background, Y, and occupied height; the normal grid remains underneath and supplies the eventual handoff layout.
