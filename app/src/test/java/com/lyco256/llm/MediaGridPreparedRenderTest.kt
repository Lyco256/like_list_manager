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
