package com.lyco256.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridSteadyLoadControllerTest {
    private fun frame(count: Int = 240, columns: Int = 4): MediaGridFrameData {
        val entries = (0 until count).map { index ->
            MediaGridEntry(
                entryId = index.toLong(), clipId = index.toLong(), assetId = index.toLong(),
                mediaKey = "asset-$index", mediaIndex = 0, type = "photo",
                displayUrl = "https://example.test/$index.jpg", downloadState = "downloaded",
                localPath = null, xCreatedAt = "2026-07-22T00:00:00Z", likeCount = null,
            )
        }
        return buildMediaGridFrameData(
            entries, ClassifiedSortState(), columns,
            MediaGridDataKey(1, 1, TweetFilterState(), ClassifiedSortState()),
        )
    }

    private fun anchor(frame: MediaGridFrameData, first: Int, last: Int, visible: IntArray, height: Int = 600) =
        MediaGridViewportAnchor(frame.key, first, last, visible, 400, height, 100, frame.key.columnCount)

    @Test
    fun startupOrderAndFixedBudgetsAreStable() {
        assertEquals(
            listOf("PreparingFrame", "PreparingInitialWindow", "WarmingInitialWindow", "Ready"),
            MediaGridStartupState.entries.map { it.name },
        )
        assertEquals(50L, MEDIA_GRID_CONTROLLER_TICK_MS)
        assertEquals(2, MEDIA_GRID_METADATA_PER_TICK)
        assertEquals(1, MEDIA_GRID_REQUESTS_PER_TICK)
        assertEquals(4, MEDIA_GRID_COMPLETIONS_PER_TICK)
        assertEquals(2, MEDIA_GRID_MAX_REQUESTS)
    }

    @Test
    fun initialWarmupUsesEstimatedRowsPlusSixEachSideAndCapsCountAndBytes() {
        val frame = frame()
        val indices = selectMediaGridInitialWarmupIndices(frame, anchor(frame, 80, 103, intArrayOf(80, 81, 82, 83)))
        assertEquals((kotlin.math.ceil(600.0 / 100).toInt() + 1 + 12) * 4, indices.size)
        assertTrue(indices.size <= 96)
        assertTrue(indices.size * 256L * 256L * 4L <= MEDIA_GRID_WARMUP_MAX_BYTES)
        assertTrue(kotlin.math.abs(indices[0] - 91) <= 2)
        assertTrue(kotlin.math.abs(indices[1] - indices[0]) <= 2)
        val capped = selectMediaGridInitialWarmupIndices(frame, anchor(frame, 80, 103, intArrayOf(80), height = 10_000))
        assertEquals(96, capped.size)
    }

    @Test
    fun activeBitmapWindowIsVisibleRowsPlusExactlyOneRowOnEachSide() {
        val frame = frame(80)
        val active = selectMediaGridActiveWindow(frame, anchor(frame, 20, 27, (20..27).toList().toIntArray()))
        assertEquals((16..31).toSet(), active)
    }

    @Test
    fun viewportAnchorEqualityIgnoresNoFieldsAndUsesContentForVisibleIndices() {
        val frame = frame(20)
        val first = anchor(frame, 4, 11, intArrayOf(4, 5, 6, 7))
        assertEquals(first, first.copy(visibleMediaItemIndices = intArrayOf(4, 5, 6, 7)))
        assertTrue(first != first.copy(firstVisibleItemIndex = 5))
    }

    @Test
    fun loadStateRetentionDropsAssetsOutsideTheActiveBitmapWindow() {
        assertEquals(
            mapOf(2L to "loading", 3L to "ready"),
            retainMediaGridActiveLoadStates(
                mapOf(1L to "old", 2L to "loading", 3L to "ready", 4L to "far"),
                setOf(2L, 3L),
            ),
        )
    }
}
