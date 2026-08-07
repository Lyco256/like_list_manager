# `MediaGridPreparedRenderTest.kt`

## Current prepared-frame contract

- `renderKeyMismatchDoesNotReuseOldFrame` rejects a null or stale frame and accepts only the current `MediaGridRenderKey`.
- `framePublishesHeadersKeysAndMediaIndexesWithoutImageMetadata` verifies that frame publication provides header/item keys, asset IDs, and ordinal indexes before image metadata is ready.
- `frameOrdinalIndexSkipsHeadersAndKeepsFirstDuplicateAsMapRepresentative` keeps headers out of media ordinals and preserves the first duplicate asset as the map representative.
- `viewportSignatureChangesOnlyAtBoundaryOrGeometryAndNeverContainsPixelOffset` fixes the viewport identity to row/geometry boundaries and excludes pixel-only movement.
- `stalePreparedImageIsRejectedByRenderKey` rejects prepared images belonging to a previous source revision.
