package com.lyco256.llm

import com.lyco256.llm.data.MediaGridPreparedImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridPreparedRenderTest {
    private fun key(sourceRevision: Long = 1L, columnCount: Int = 4): MediaGridRenderKey =
        MediaGridRenderKey(
            dataKey = MediaGridDataKey(
                sourceRevision = sourceRevision,
                hierarchyRevision = 2L,
                filter = TweetFilterState(),
                sort = ClassifiedSortState(),
            ),
            columnCount = columnCount,
        )

    @Test
    fun renderKeyMismatchDoesNotReuseOldFrame() {
        val current = key(sourceRevision = 2L)
        assertFalse(mediaGridFrameMatches(key(sourceRevision = 1L), current))
        assertFalse(mediaGridFrameMatches(null, current))
        assertTrue(mediaGridFrameMatches(current, current))
    }

    @Test
    fun framePublishesHeadersKeysAndMediaIndexesWithoutImageMetadata() {
        val dataKey = key().dataKey
        val frame = buildMediaGridFrameData(
            entries = listOf(
                MediaGridEntry(1L, 10L, 1L, "one", 0, "photo", null, "failed", null, "2026-01-01T00:00:00Z", null),
                MediaGridEntry(2L, 10L, 2L, "two", 1, "photo", null, "failed", null, "2026-01-01T00:00:00Z", null),
            ),
            sort = dataKey.sort,
            columnCount = 4,
            dataKey = dataKey,
        )

        assertTrue(frame.items.isNotEmpty())
        assertTrue(frame.itemByKey.containsKey("media_grid_item_1"))
        assertTrue(frame.itemByKey.containsKey("media_grid_item_2"))
        assertEquals(1L, frame.assetIdByItemKey["media_grid_item_1"])
        assertEquals(2L, frame.assetIdByItemKey["media_grid_item_2"])
        assertTrue(frame.assetIdByItemKey.keys.all { frame.itemByKey[it] is MediaGridCellItem })
        assertArrayEquals(intArrayOf(0, 1), frame.mediaCellIndices)
        assertArrayEquals(intArrayOf(0, 1), frame.ordinalIndex.itemIndexByMediaOrdinal)
        assertArrayEquals(intArrayOf(0, 1), frame.ordinalIndex.mediaOrdinalByItemIndex)
        assertArrayEquals(longArrayOf(1L, 2L), frame.ordinalIndex.assetIdByMediaOrdinal)
    }

    @Test
    fun frameOrdinalIndexSkipsHeadersAndKeepsFirstDuplicateAsMapRepresentative() {
        val frame = buildMediaGridFrameData(
            entries = listOf(
                MediaGridEntry(1L, 10L, 7L, "one", 0, "photo", null, "ready", null, "2026-01-01T00:00:00Z", 1L),
                MediaGridEntry(2L, 11L, 7L, "two", 0, "photo", null, "ready", null, "2026-01-02T00:00:00Z", 100L),
            ),
            sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount),
            columnCount = 4,
            dataKey = key().dataKey,
        )

        assertTrue(frame.items.any { it is MediaGridHeaderItem })
        assertEquals(0, frame.ordinalIndex.mediaOrdinalByAssetId[7L])
        assertEquals(frame.mediaCellIndices.toList(), frame.ordinalIndex.itemIndexByMediaOrdinal.toList())
        assertTrue(frame.items.indices.filter { frame.items[it] is MediaGridHeaderItem }
            .all { frame.ordinalIndex.mediaOrdinalByItemIndex[it] == -1 })
        assertEquals(listOf(7L, 7L), frame.ordinalIndex.assetIdByMediaOrdinal.toList())
    }

    @Test
    fun viewportSignatureChangesOnlyAtBoundaryOrGeometryAndNeverContainsPixelOffset() {
        val key = key()
        val sameRange = MediaGridViewportSignature(key, 4, 11, 2, 5, 400, 600, 100, 4)
        assertEquals(sameRange, sameRange.copy())
        assertTrue(sameRange != sameRange.copy(firstVisibleMediaOrdinal = 3))
        assertTrue(sameRange != sameRange.copy(viewportWidthPx = 420))
        assertTrue(sameRange != sameRange.copy(cellSizePx = 101))
        assertTrue(sameRange != sameRange.copy(renderKey = key(sourceRevision = 2)))
    }

    @Test
    fun stalePreparedImageIsRejectedByRenderKey() {
        val oldKey = key(sourceRevision = 1L)
        val newKey = key(sourceRevision = 2L)
        val prepared = MediaGridPreparedImage(oldKey, 1L, emptyList())
        assertFalse(com.lyco256.llm.data.mediaGridPreparedImageMatches(prepared, newKey))
        assertTrue(com.lyco256.llm.data.mediaGridPreparedImageMatches(prepared, oldKey))
    }
}
