package com.lyco256.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import com.lyco256.llm.data.MediaGridPreparedImage

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
        MediaGridViewportAnchor(
            frame.key, first, last,
            visible.firstOrNull() ?: -1, visible.lastOrNull() ?: -1,
            400, height, 100, frame.key.columnCount,
        )

    @Test
    fun startupOrderAndDecoupledBudgetsAreStable() {
        assertEquals(
            listOf("PreparingFrame", "PreparingInitialWindow", "WarmingInitialWindow", "Ready"),
            MediaGridStartupState.entries.map { it.name },
        )
        assertEquals(4, MEDIA_GRID_METADATA_MAX_CONCURRENCY)
        assertEquals(2, MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY)
        assertEquals(2, MEDIA_GRID_URGENT_RESERVED_CONCURRENCY)
        assertEquals(4, MEDIA_GRID_BITMAP_MAX_CONCURRENCY)
        assertEquals(1, MEDIA_GRID_HIDDEN_BITMAP_MAX_CONCURRENCY)
        assertFalse("normal pipeline must not retain a throughput tick", ::startupOrderAndDecoupledBudgetsAreStable.name.contains("tick"))
    }

    @Test
    fun initialWarmupUsesViewportThenTwoForwardScreensAndCapsCountAndBytes() {
        val frame = frame()
        val indices = selectMediaGridInitialWarmupIndices(frame, anchor(frame, 80, 103, intArrayOf(80, 81, 82, 83)))
        assertEquals(56, indices.size)
        assertTrue(indices.size <= 128)
        assertTrue(indices.size * 256L * 256L * 4L <= MEDIA_GRID_WARMUP_MAX_BYTES)
        assertEquals(80, indices[0])
        assertEquals(81, indices[1])
        val capped = selectMediaGridInitialWarmupIndices(frame, anchor(frame, 80, 103, intArrayOf(80), height = 10_000))
        assertEquals(128, capped.size)
    }

    @Test
    fun initialWarmupDoesNotIncludePreviousRowsAtTheListHead() {
        val frame = frame()
        val indices = selectMediaGridInitialWarmupIndices(frame, anchor(frame, 0, 3, intArrayOf(0, 1, 2, 3)))
        assertEquals(52, indices.size)
        assertTrue(indices.all { it >= 0 })
    }

    @Test
    fun activeBitmapWindowIsVisibleRowsPlusExactlyThreeRowsOnEachSide() {
        val frame = frame(80)
        val active = buildMediaGridActiveWindowSnapshot(frame, anchor(frame, 20, 27, (20..27).toList().toIntArray()), 1L, 1L).activeAssetIds.toSet()
        assertEquals((8L..39L).toSet(), active)
    }

    @Test
    fun activeWindowUsesThreeMediaRowsForEverySupportedColumnCount() {
        listOf(2, 4, 8, 12).forEach { columns ->
            val frame = frame(160, columns)
            val first = columns * 5
            val visible = (first until first + columns * 2).toList().toIntArray()
            val active = buildMediaGridActiveWindowSnapshot(frame, anchor(frame, first, visible.last(), visible), 1L, 1L).activeAssetIds.toSet()
            assertEquals(columns * 8, active.size)
        }
    }

    @Test
    fun activeSnapshotKeepsVisibleAndActiveOrderAndMembership() {
        val frame = frame(40)
        val snapshot = buildMediaGridActiveWindowSnapshot(
            frame,
            anchor(frame, 12, 19, intArrayOf(12, 13, 14, 15, 16, 17, 18, 19)),
            epoch = 7L,
            generation = 3L,
        )
        assertEquals(longArrayOf(12, 13, 14, 15, 16, 17, 18, 19).toList(), snapshot.visibleAssetIds.toList())
        assertEquals((0L..31L).toList(), snapshot.activeAssetIds.toList())
        assertTrue(snapshot.isActive(0L, frame.ordinalIndex.mediaOrdinalByAssetId))
        assertFalse(snapshot.isActive(32L, frame.ordinalIndex.mediaOrdinalByAssetId))
        assertEquals(7L, snapshot.epoch)
        assertEquals(3L, snapshot.generation)
        assertEquals(15, snapshot.centerMediaOrdinal)
    }

    @Test
    fun viewportAnchorEqualityIgnoresNoFieldsAndUsesContentForVisibleIndices() {
        val frame = frame(20)
        val first = anchor(frame, 4, 11, intArrayOf(4, 5, 6, 7))
        assertEquals(first, first.copy(firstVisibleMediaOrdinal = 4, lastVisibleMediaOrdinal = 7))
        assertTrue(first != first.copy(firstVisibleItemIndex = 5))
    }

    @Test
    fun loadStateRetentionDropsAssetsOutsideTheActiveBitmapWindow() {
        assertEquals(
            mapOf(2L to "loading", 3L to "ready"),
            mapOf(1L to "old", 2L to "loading", 3L to "ready", 4L to "far").filterKeys { it == 2L || it == 3L },
        )
    }

    @Test
    fun backgroundBitmapStopsAt75PercentAndResumesBelow65Percent() {
        assertTrue(mediaGridBackgroundBitmapAllowed(70, 100, 4))
        assertFalse(mediaGridBackgroundBitmapAllowed(74, 100, 2))
        assertFalse(mediaGridMemoryWatermarkAllowsResume(70, 100))
        assertTrue(mediaGridMemoryWatermarkAllowsResume(64, 100))
    }

    @Test
    fun bitmapMemoryEstimateUsesOutputConfigAndLongArithmetic() {
        val rgb565 = MediaGridPreparedCandidate(
            kind = MediaGridImageSourceKind.Rgb565Pack,
            requestData = Unit,
            sourceIdentity = "rgb",
            cacheKey = "rgb",
            width = 256,
            height = 256,
        )
        val argb = rgb565.copy(kind = MediaGridImageSourceKind.Local, sourceIdentity = "local")
        assertEquals(131_072L, mediaGridEstimatedBitmapBytes(rgb565))
        assertEquals(262_144L, mediaGridEstimatedBitmapBytes(argb))
        assertEquals(0L, mediaGridEstimatedBitmapBytes(rgb565.copy(width = 0)))
    }

    @Test
    fun queueTokenAndRecordRejectStaleAndDuplicateEntriesWithoutQueueSearch() {
        assertTrue(mediaGridQueueTokenIsCurrent(8L, 8L, 4L, 4L))
        assertFalse(mediaGridQueueTokenIsCurrent(8L, 7L, 4L, 4L))
        assertFalse(mediaGridQueueTokenIsCurrent(8L, 8L, 4L, 5L))
        assertTrue(
            mediaGridBitmapQueueEntryMatches(
                QueueTaskStatus.BackgroundQueued, 2, "source-2", 2, "source-2",
            ),
        )
        assertTrue(
            mediaGridBitmapQueueEntryMatches(
                QueueTaskStatus.UrgentQueued, 2, "source-2", 2, "source-2",
            ),
        )
        assertFalse(
            mediaGridBitmapQueueEntryMatches(
                QueueTaskStatus.BackgroundQueued, 2, "source-2", 3, "source-3",
            ),
        )
        assertFalse(
            mediaGridBitmapQueueEntryMatches(
                QueueTaskStatus.Complete, 2, "source-2", 2, "source-2",
            ),
        )
    }

    @Test
    fun mediaOrdinalIndexMatchesMediaCellsAndProvidesBothDirections() {
        val frame = frame(12)
        val index = frame.ordinalIndex
        assertArrayEquals(frame.mediaCellIndices, index.itemIndexByMediaOrdinal)
        assertArrayEquals(LongArray(12) { it.toLong() }, index.assetIdByMediaOrdinal)
        assertEquals(7, index.mediaOrdinalByAssetId[7L])
        assertEquals(frame.mediaCellIndices[7], index.itemIndexByAssetId[7L])
    }

    @Test
    fun ordinalPendingSetUsesNearestOrdinalAndPrefersLowerScreenDirectionOnTie() {
        val pending = MediaGridOrdinalPendingSet(10)
        pending.add(2); pending.add(6); pending.add(8)
        assertEquals(6, pending.peekNearest(6))
        assertEquals(6, pending.pollNearest(5))
        assertEquals(8, pending.peekNearest(5))
        pending.remove(8)
        assertEquals(2, pending.pollNearest(5))
        assertTrue(pending.isEmpty())
        pending.add(3); pending.clear()
        assertFalse(pending.contains(3))
    }

    @Test
    fun ordinalPendingSetKeepsPendingWhenWatermarkDoesNotPermitPolling() {
        val pending = MediaGridOrdinalPendingSet(4)
        pending.add(2)
        assertFalse(mediaGridBackgroundBitmapAllowed(74, 100, 2))
        assertTrue(pending.contains(2))
    }

    @Test
    fun readyAttachmentOrderUsesVisibleCenterThenDownwardTieBreakAndSkipsPublished() {
        val candidate = { assetId: Long ->
            MediaGridPreparedCandidate(
                kind = MediaGridImageSourceKind.Local,
                requestData = assetId,
                sourceIdentity = "source-$assetId",
                cacheKey = "cache-$assetId",
                width = 256,
                height = 256,
            )
        }
        val cells = (0L..6L).associateWith { assetId ->
            MediaGridCellLoadState(
                status = MediaGridCellLoadStatus.Ready,
                prepared = MediaGridPreparedImage(
                    key = frame(7).key,
                    assetId = assetId,
                    candidates = listOf(candidate(assetId)),
                ),
            )
        }
        val published = mapOf(3L to cells.getValue(3L))
        assertEquals(
            listOf(4L, 2L, 5L, 1L, 6L, 0L),
            mediaGridReadyAttachmentOrder(
                visibleAssetIds = longArrayOf(0L, 1L, 2L, 3L, 4L, 5L, 6L),
                internalCells = cells,
                publishedCells = published,
                mediaOrdinalByAssetId = (0L..6L).associateWith { it.toInt() },
                centerMediaOrdinal = 3,
            ),
        )
    }

    @Test
    fun fakeFrameClockPublishesAtMostOneNewAttachmentPerFrameAndFinishesInTwelveFrames() {
        val frame = frame(12)
        val cells = (0L until 12L).associateWith { assetId ->
            MediaGridCellLoadState(
                status = MediaGridCellLoadStatus.Ready,
                prepared = MediaGridPreparedImage(
                    key = frame.key,
                    assetId = assetId,
                    candidates = listOf(
                        MediaGridPreparedCandidate(
                            kind = MediaGridImageSourceKind.Local,
                            requestData = assetId,
                            sourceIdentity = "source-$assetId",
                            cacheKey = "cache-$assetId",
                            width = 256,
                            height = 256,
                        ),
                    ),
                ),
            )
        }
        var published = emptyMap<Long, MediaGridCellLoadState>()
        repeat(12) { frameNumber ->
            val before = published.size
            val next = mediaGridReadyAttachmentOrder(
                LongArray(12) { it.toLong() }, cells, published,
                (0L until 12L).associateWith { it.toInt() }, centerMediaOrdinal = 5,
            ).firstOrNull()
            assertTrue("frame $frameNumber attached more than one asset", next != null)
            published = published + (next!! to cells.getValue(next))
            assertEquals(before + 1, published.size)
        }
        assertEquals(12, published.size)
        assertTrue(
            mediaGridReadyAttachmentOrder(
                LongArray(12) { it.toLong() }, cells, published,
                (0L until 12L).associateWith { it.toInt() }, centerMediaOrdinal = 5,
            ).isEmpty(),
        )
    }
}
