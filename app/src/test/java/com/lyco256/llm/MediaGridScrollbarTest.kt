package com.lyco256.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridScrollbarTest {
    @Test
    fun scrollbarIsHiddenWhenViewportCannotScroll() {
        assertFalse(calculateMediaGridScrollbarGeometry(0, -1, -1, 1000f, 32f).isScrollable)
        assertFalse(calculateMediaGridScrollbarGeometry(1, 0, 0, 1000f, 32f).isScrollable)
        assertFalse(calculateMediaGridScrollbarGeometry(12, 0, 11, 1000f, 32f).isScrollable)
    }

    @Test
    fun thumbUsesMediaOrdinalRangeAndReachesBothEnds() {
        val first = calculateMediaGridScrollbarGeometry(100, 0, 9, 1000f, 32f)
        val middle = calculateMediaGridScrollbarGeometry(100, 45, 54, 1000f, 32f)
        val last = calculateMediaGridScrollbarGeometry(100, 90, 99, 1000f, 32f)

        assertEquals(0f, first.thumbTopPx, 0.001f)
        assertEquals(first.maxThumbTopPx / 2f, middle.thumbTopPx, 0.001f)
        assertEquals(last.maxThumbTopPx, last.thumbTopPx, 0.001f)
        assertEquals(10, first.visibleMediaCount)
    }

    @Test
    fun minimumThumbHeightIsMaintainedForLargeDatasets() {
        val geometry = calculateMediaGridScrollbarGeometry(100_000, 50_000, 50_009, 1000f, 32f)

        assertTrue(geometry.isScrollable)
        assertEquals(32f, geometry.thumbHeightPx, 0.001f)
        assertTrue(geometry.thumbTopPx in 0f..geometry.maxThumbTopPx)
    }

    @Test
    fun dragFractionClampsToValidFirstMediaOrdinal() {
        assertEquals(0, mediaGridScrollbarTargetOrdinal(-1f, 100, 10))
        assertEquals(45, mediaGridScrollbarTargetOrdinal(0.5f, 100, 10))
        assertEquals(90, mediaGridScrollbarTargetOrdinal(2f, 100, 10))
        assertEquals(null, mediaGridScrollbarTargetOrdinal(0.5f, 10, 10))
    }

    @Test
    fun ordinalTargetMapsOnlyToMediaItemIndexesForEveryColumnCount() {
        val entries = (0 until 40).map { index ->
            MediaGridEntry(
                entryId = index.toLong() + 1,
                clipId = index.toLong() + 1,
                assetId = index.toLong() + 1,
                mediaKey = "media-$index",
                mediaIndex = 0,
                type = "photo",
                displayUrl = null,
                downloadState = "downloaded",
                localPath = null,
                xCreatedAt = "2026-08-19T12:00:00Z",
                likeCount = index.toLong(),
            )
        }
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        for (columns in 2..12) {
            val key = MediaGridDataKey(1L, 1L, TweetFilterState(), sort)
            val frame = buildMediaGridFrameData(entries, sort, columns, key)
            val targetIndex = mediaGridScrollbarTargetItemIndex(
                fraction = 1f,
                totalMediaCount = frame.ordinalIndex.assetIdByMediaOrdinal.size,
                visibleMediaCount = 8,
                itemIndexByMediaOrdinal = frame.ordinalIndex.itemIndexByMediaOrdinal,
            )
            assertNotNull(targetIndex)
            assertTrue(frame.items[targetIndex!!] is MediaGridCellItem)
        }
    }

    @Test
    fun dragSessionKeepsItsFrameMetricsAndLatestTargetOnly() {
        val frame = testFrame(100)
        val geometry = calculateMediaGridScrollbarGeometry(100, 20, 29, 1000f, 32f)
        val state = MediaGridScrollbarState()

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx + 4f))
        repeat(50) { index ->
            state.updateDrag(geometry.maxThumbTopPx * (index / 49f) + 4f)
        }

        assertTrue(state.dragSnapshot.isDragging)
        assertEquals(frame.key, state.dragSnapshot.frameKey)
        assertEquals(state.currentTargetItemIndex, state.targetRequests.value?.targetItemIndex)
        assertEquals(90, state.dragSnapshot.targetMediaOrdinal)
        state.cancelIfFrameChanged(frame.key.copy(columnCount = 8))
        assertFalse(state.dragSnapshot.isDragging)
        assertEquals(null, state.targetRequests.value)
    }

    @Test
    fun pointerCancelClearsDraggingState() {
        val frame = testFrame(50)
        val geometry = calculateMediaGridScrollbarGeometry(50, 0, 9, 1000f, 32f)
        val state = MediaGridScrollbarState()

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.cancelDrag()

        assertFalse(state.isDragging)
        assertEquals(null, state.currentTargetItemIndex)
    }

    @Test
    fun finishEndsPointerImmediatelyButKeepsFinalTargetPending() {
        val frame = testFrame(50)
        val geometry = calculateMediaGridScrollbarGeometry(50, 0, 9, 1000f, 32f)
        val state = MediaGridScrollbarState()

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.updateDrag(900f)
        val target = state.currentTargetItemIndex
        val ordinal = state.dragSnapshot.targetMediaOrdinal
        val dragRequest = checkNotNull(state.targetRequests.value)
        state.finishDrag()
        val finalRequest = checkNotNull(state.targetRequests.value)

        assertFalse(state.dragSnapshot.isDragging)
        assertFalse(state.hasActivePointer)
        assertTrue(state.dragSnapshot.isFinalTargetPending)
        assertEquals(target, state.currentTargetItemIndex)
        assertEquals(ordinal, state.dragSnapshot.targetMediaOrdinal)
        assertEquals(target, finalRequest.targetItemIndex)
        assertEquals(MediaGridScrollbarRequestKind.Drag, dragRequest.kind)
        assertEquals(MediaGridScrollbarRequestKind.Final, finalRequest.kind)
        assertTrue(finalRequest.requestId > dragRequest.requestId)
        assertTrue(state.shouldProcessRequest(finalRequest))

        assertTrue(state.completeFinalTarget(finalRequest))
        assertFalse(state.dragSnapshot.isDragging)
        assertFalse(state.dragSnapshot.isFinalTargetPending)
        assertEquals(MediaGridScrollbarDragEnd.Completed, state.dragSnapshot.endReason)
        assertTrue(state.dragSnapshot.completionId > 0L)
        assertEquals(null, state.targetRequests.value)
        assertEquals(0f, state.thumbTopPx(geometry), 0.001f)
    }

    @Test
    fun finalRequestIsUniqueWhenDragAndReleaseUseTheSameItem() {
        val frame = testFrame(50)
        val geometry = calculateMediaGridScrollbarGeometry(50, 0, 9, 1000f, 32f)
        val state = MediaGridScrollbarState()

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.updateDrag(900f)
        val dragRequest = checkNotNull(state.targetRequests.value)
        state.updateDrag(900f)
        val repeatedDragRequest = checkNotNull(state.targetRequests.value)
        state.finishDrag()
        val finalRequest = checkNotNull(state.targetRequests.value)

        assertEquals(dragRequest.targetItemIndex, repeatedDragRequest.targetItemIndex)
        assertEquals(repeatedDragRequest.targetItemIndex, finalRequest.targetItemIndex)
        assertTrue(dragRequest.requestId < repeatedDragRequest.requestId)
        assertTrue(repeatedDragRequest.requestId < finalRequest.requestId)
        assertEquals(MediaGridScrollbarRequestKind.Final, finalRequest.kind)
    }

    @Test
    fun staleFinalCompletionCannotFinishANewDragSession() {
        val frame = testFrame(50)
        val geometry = calculateMediaGridScrollbarGeometry(50, 0, 9, 1000f, 32f)
        val state = MediaGridScrollbarState()

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.updateDrag(900f)
        state.finishDrag()
        val staleFinalRequest = checkNotNull(state.targetRequests.value)

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.updateDrag(450f)
        assertTrue(state.dragSnapshot.isDragging)
        assertFalse(state.completeFinalTarget(staleFinalRequest))
        assertTrue(state.dragSnapshot.isDragging)
        assertFalse(state.dragSnapshot.isFinalTargetPending)
        assertTrue(checkNotNull(state.targetRequests.value).requestId > staleFinalRequest.requestId)
    }

    @Test
    fun finalCompletionAndCancellationReturnThumbToViewportGeometry() {
        val frame = testFrame(50)
        val geometry = calculateMediaGridScrollbarGeometry(50, 0, 9, 1000f, 32f)
        val fallback = geometry.copy(thumbTopPx = 123f)
        val state = MediaGridScrollbarState()

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.updateDrag(900f)
        state.finishDrag()
        val finalRequest = checkNotNull(state.targetRequests.value)
        assertEquals(geometry.maxThumbTopPx, state.thumbTopPx(fallback), 0.001f)
        assertTrue(state.completeFinalTarget(finalRequest))
        assertEquals(123f, state.thumbTopPx(fallback), 0.001f)

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.updateDrag(900f)
        state.cancelDrag()
        assertFalse(state.isDragging)
        assertFalse(state.hasPendingFinalTarget)
        assertEquals(123f, state.thumbTopPx(fallback), 0.001f)
    }

    @Test
    fun cancelledRequestConsumerClearsOnlyItsOwnSession() {
        val frame = testFrame(50)
        val geometry = calculateMediaGridScrollbarGeometry(50, 0, 9, 1000f, 32f)
        val state = MediaGridScrollbarState()
        val oldConsumer = state.beginRequestConsumer()

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.finishDrag()
        val newConsumer = state.beginRequestConsumer()
        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.updateDrag(450f)
        state.cancelForCoroutine(frame.key, oldConsumer)
        assertTrue(state.dragSnapshot.isDragging)
        state.cancelForCoroutine(frame.key, newConsumer)
        assertFalse(state.dragSnapshot.isDragging)
        assertFalse(state.hasPendingFinalTarget)
        assertEquals(null, state.targetRequests.value)
    }

    @Test
    fun cancelAfterPointerReleaseDoesNotLookLikeCompletionAndClearsTarget() {
        val frame = testFrame(50)
        val geometry = calculateMediaGridScrollbarGeometry(50, 0, 9, 1000f, 32f)
        val state = MediaGridScrollbarState()

        assertTrue(state.beginDrag(frame, geometry, geometry.thumbTopPx))
        state.updateDrag(900f)
        state.finishDrag()
        state.cancelIfFrameChanged(frame.key.copy(columnCount = 8))

        assertFalse(state.dragSnapshot.isDragging)
        assertEquals(MediaGridScrollbarDragEnd.Cancelled, state.dragSnapshot.endReason)
        assertEquals(null, state.currentTargetItemIndex)
        assertEquals(null, state.targetRequests.value)
    }

    private fun testFrame(count: Int): MediaGridFrameData {
        val entries = (0 until count).map { index ->
            MediaGridEntry(
                entryId = index.toLong() + 1,
                clipId = index.toLong() + 1,
                assetId = index.toLong() + 1,
                mediaKey = "media-$index",
                mediaIndex = 0,
                type = "photo",
                displayUrl = null,
                downloadState = "downloaded",
                localPath = null,
                xCreatedAt = "2026-08-19T12:00:00Z",
                likeCount = index.toLong(),
            )
        }
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        return buildMediaGridFrameData(
            entries,
            sort,
            4,
            MediaGridDataKey(1L, 1L, TweetFilterState(), sort),
        )
    }
}
