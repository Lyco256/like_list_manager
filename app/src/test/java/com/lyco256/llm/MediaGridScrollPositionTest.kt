package com.lyco256.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridScrollPositionTest {
    @Test
    fun defaultSortHasNoCurrentPosition() {
        val frame = frame(
            sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.Default),
            entries = listOf(entry(1L, "2026-08-19T12:00:00Z", 123L)),
        )

        assertNull(currentMediaGridPosition(frame, anchor(frame, 0), frame.key.dataKey.sort, 4))
    }

    @Test
    fun postTimeUsesExistingDayWeekMonthBucketsAndUnknownLabel() {
        val entry = entry(1L, "2026-08-19T12:00:00Z", 123L)
        val dayFrame = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry), 4)
        val weekFrame = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry), 5)
        val monthFrame = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry), 9)
        assertEquals("2026/08/19", position(dayFrame, 4).label)
        assertEquals("2026/08/17 ~ 2026/08/23", position(weekFrame, 5).label)
        assertEquals("2026/8", position(monthFrame, 9).label)

        val unknownFrame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
            listOf(entry(2L, "not-a-date", 123L)),
            4,
        )
        assertEquals("日付不明", position(unknownFrame, 4).label)
    }

    @Test
    fun likeCountUsesExistingColumnGranularityAndSpecialBuckets() {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount)
        assertEquals("0〜199", position(frame(sort, listOf(entry(1L, date(), 199L)), 4), 4).label)
        assertEquals("0〜499", position(frame(sort, listOf(entry(2L, date(), 499L)), 5), 5).label)
        assertEquals("0〜999", position(frame(sort, listOf(entry(3L, date(), 999L)), 9), 9).label)
        assertEquals("10万以上", position(frame(sort, listOf(entry(4L, date(), 100_000L)), 4), 4).label)
        assertEquals("いいね数不明", position(frame(sort, listOf(entry(5L, date(), null)), 4), 4).label)
    }

    @Test
    fun targetOrdinalUsesTheSameHeaderBucketForEverySupportedSort() {
        val postTimeEntries = listOf(
            entry(1L, "2026-08-19T12:00:00Z", 1L),
            entry(2L, "2026-08-20T12:00:00Z", 2L),
        )
        val postTimeFrame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
            postTimeEntries,
            4,
        )
        assertEquals(
            "2026/08/20",
            mediaGridPositionForOrdinal(
                postTimeFrame,
                mediaOrdinal = 1,
                sort = postTimeFrame.key.dataKey.sort,
                columnCount = 4,
            )?.label,
        )

        val likeFrame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount),
            listOf(entry(3L, date(), 100_000L)),
            9,
        )
        assertEquals(
            "10万以上",
            mediaGridPositionForOrdinal(
                likeFrame,
                mediaOrdinal = 0,
                sort = likeFrame.key.dataKey.sort,
                columnCount = 9,
            )?.label,
        )
        assertNull(
            mediaGridPositionForOrdinal(
                frame(ClassifiedSortState(), listOf(entry(4L, date(), 1L)), 4),
                mediaOrdinal = 0,
                sort = ClassifiedSortState(),
                columnCount = 4,
            ),
        )
    }

    @Test
    fun targetOrdinalsWithinOneBucketKeepTheSameLabel() {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val frame = frame(
            sort,
            listOf(
                entry(1L, "2026-08-19T00:00:00Z", 1L),
                entry(2L, "2026-08-19T08:00:00Z", 2L),
            ),
            4,
        )

        assertEquals(
            mediaGridPositionForOrdinal(frame, 0, sort, 4)?.label,
            mediaGridPositionForOrdinal(frame, 1, sort, 4)?.label,
        )
    }

    @Test
    fun frameBuildIndexesEachHeaderStartAndFindsTheContainingBucket() {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val frame = frame(
            sort,
            listOf(
                entry(1L, "2026-08-19T00:00:00Z", 1L),
                entry(2L, "2026-08-19T08:00:00Z", 2L),
                entry(3L, "2026-08-20T00:00:00Z", 3L),
                entry(4L, "2026-08-20T08:00:00Z", 4L),
                entry(5L, "2026-08-21T00:00:00Z", 5L),
            ),
            4,
        )

        assertEquals(
            listOf(
                MediaGridHeaderBoundary("post_time_day_2026-08-19", "2026/08/19", 0),
                MediaGridHeaderBoundary("post_time_day_2026-08-20", "2026/08/20", 2),
                MediaGridHeaderBoundary("post_time_day_2026-08-21", "2026/08/21", 4),
            ),
            frame.headerBoundaryIndex.boundaries,
        )
        assertEquals(0, frame.headerBoundaryIndex.boundaryAtOrBefore(1)?.startMediaOrdinal)
        assertEquals(2, frame.headerBoundaryIndex.boundaryAtOrBefore(3)?.startMediaOrdinal)
        assertEquals(4, frame.headerBoundaryIndex.boundaryAtOrBefore(4)?.startMediaOrdinal)
    }

    @Test
    fun headerBoundaryIndexUsesExistingGranularityAndSpecialBuckets() {
        val postTime = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val weekFrame = frame(
            postTime,
            listOf(
                entry(1L, "2026-08-17T00:00:00Z", 1L),
                entry(2L, "2026-08-23T12:00:00Z", 2L),
                entry(3L, "2026-08-24T00:00:00Z", 3L),
            ),
            5,
        )
        assertEquals(listOf(0, 2), weekFrame.headerBoundaryIndex.boundaries.map { it.startMediaOrdinal })

        val monthFrame = frame(
            postTime,
            listOf(
                entry(4L, "2026-08-01T00:00:00Z", 1L),
                entry(5L, "2026-08-31T12:00:00Z", 2L),
                entry(6L, "2026-09-01T00:00:00Z", 3L),
            ),
            9,
        )
        assertEquals(listOf(0, 2), monthFrame.headerBoundaryIndex.boundaries.map { it.startMediaOrdinal })

        val likeFrame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount),
            listOf(
                entry(7L, date(), null),
                entry(8L, date(), null),
                entry(9L, date(), 100_000L),
                entry(10L, date(), 100_000L),
            ),
            4,
        )
        assertEquals(listOf(0, 2), likeFrame.headerBoundaryIndex.boundaries.map { it.startMediaOrdinal })
    }

    @Test
    fun headerBoundaryLabelsFollowColumnGranularityForDateAndLikeSorts() {
        val postTime = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val weekFrame = frame(
            postTime,
            listOf(
                entry(1L, "2026-08-17T00:00:00Z", 1L),
                entry(2L, "2026-08-23T12:00:00Z", 2L),
                entry(3L, "2026-08-24T00:00:00Z", 3L),
            ),
            5,
        )
        assertEquals(
            listOf("2026/08/17 ~ 2026/08/23", "2026/08/24 ~ 2026/08/30"),
            weekFrame.headerBoundaryIndex.boundaries.map { it.label },
        )
        assertEquals(listOf(0, 2), weekFrame.headerBoundaryIndex.boundaries.map { it.startMediaOrdinal })

        val monthFrame = frame(
            postTime,
            listOf(
                entry(4L, "2026-08-01T00:00:00Z", 1L),
                entry(5L, "2026-08-31T12:00:00Z", 2L),
                entry(6L, "2026-09-01T00:00:00Z", 3L),
            ),
            9,
        )
        assertEquals(listOf("2026/8", "2026/9"), monthFrame.headerBoundaryIndex.boundaries.map { it.label })
        assertEquals(listOf(0, 2), monthFrame.headerBoundaryIndex.boundaries.map { it.startMediaOrdinal })

        val likeFrame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount),
            listOf(
                entry(7L, date(), null),
                entry(8L, date(), 100_000L),
            ),
            4,
        )
        assertEquals(listOf("いいね数不明", "10万以上"), likeFrame.headerBoundaryIndex.boundaries.map { it.label })
        assertEquals(listOf(0, 1), likeFrame.headerBoundaryIndex.boundaries.map { it.startMediaOrdinal })
    }

    @Test
    fun defaultSortHasNoHeaderBoundaryIndex() {
        val frame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.Default),
            listOf(entry(1L, date(), 1L), entry(2L, date(), 2L)),
        )

        assertTrue(frame.headerBoundaryIndex.boundaries.isEmpty())
        assertNull(frame.headerBoundaryIndex.boundaryAtOrBefore(1))
    }

    @Test
    fun scrollbarHeaderPillsUseEveryBoundaryLabelAndStartOrdinalInOrder() {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val frame = frame(
            sort,
            listOf(
                entry(1L, "2026-08-19T00:00:00Z", 1L),
                entry(2L, "2026-08-20T00:00:00Z", 2L),
                entry(3L, "not-a-date", 3L),
            ),
            4,
        )

        val positions = mediaGridScrollbarHeaderPillPositions(
            frame = frame,
            totalMediaCount = 3,
            visibleMediaCount = 1,
            maxThumbTopPx = 200f,
        )

        assertEquals(listOf("2026/08/19", "2026/08/20", "日付不明"), positions.map { it.label })
        assertEquals(listOf(0, 1, 2), positions.map { it.startMediaOrdinal })
        assertEquals(listOf(0f, 100f, 200f), positions.map { it.topPx })
    }

    @Test
    fun defaultSortHasNoScrollbarHeaderPills() {
        val frame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.Default),
            listOf(entry(1L, date(), 1L), entry(2L, date(), 2L)),
        )

        assertTrue(
            mediaGridScrollbarHeaderPillPositions(frame, 2, 1, 200f).isEmpty(),
        )
    }

    @Test
    fun nextHeaderDoesNotAdvanceUntilFirstVisibleMediaMoves() {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val frame = frame(
            sort,
            listOf(
                entry(1L, "2026-08-19T12:00:00Z", 1L),
                entry(2L, "2026-08-20T12:00:00Z", 2L),
            ),
            4,
        )

        val nextHeaderVisible = anchor(frame, firstOrdinal = 0, lastOrdinal = 1)
        assertEquals("2026/08/19", currentMediaGridPosition(frame, nextHeaderVisible, sort, 4)?.label)

        val nextMediaAtTop = anchor(frame, firstOrdinal = 1, lastOrdinal = 1)
        assertEquals("2026/08/20", currentMediaGridPosition(frame, nextMediaAtTop, sort, 4)?.label)
    }

    @Test
    fun positionArrivingAfterScrollStartStillStartsThePill() {
        val frame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
            listOf(entry(1L, date(), 1L)),
            4,
        )
        val position = position(frame, 4)
        var state = reduceMediaGridPositionPillState(
            MediaGridPositionPillState(),
            MediaGridPositionPillEvent.ScrollStarted(null),
        )
        assertEquals(MediaGridPositionPillPhase.Hidden, state.phase)
        state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStarted(position))
        assertEquals(MediaGridPositionPillPhase.Visible, state.phase)
        assertEquals("2026/08/19", state.label)
    }

    @Test
    fun firstVisibleOrdinalIsUsedWithoutScanningFrameItems() {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val entries = (0L..40L).map { id -> entry(id + 1L, "2026-08-${((id % 9) + 1).toString().padStart(2, '0')}T12:00:00Z", id) }
        val frame = frame(sort, entries, 4)
        val position = currentMediaGridPosition(frame, anchor(frame, 40, 40), sort, 4)

        assertNotNull(position)
        assertEquals(40, position?.mediaOrdinal)
        assertEquals("2026/08/05", position?.label)
    }

    @Test
    fun scrollLifecycleKeepsPillForThreeSecondsThenSlidesOut() {
        val oldFrame = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry(1L, date(), 1L)), 4)
        val position = position(oldFrame, 4)
        var state = reduceMediaGridPositionPillState(
            MediaGridPositionPillState(),
            MediaGridPositionPillEvent.FrameChanged(oldFrame.key, hasBuckets = true, morphing = false),
        )
        state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStarted(position))
        state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStopped)
        assertEquals(MediaGridPositionPillPhase.Visible, state.phase)
        assertTrue(state.hideScheduled)

        val early = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.HideTimeout(state.generation),
        )
        assertEquals(MediaGridPositionPillPhase.Hiding, early.phase)
        val finished = reduceMediaGridPositionPillState(
            early,
            MediaGridPositionPillEvent.ExitAnimationFinished(early.generation),
        )
        assertEquals(MediaGridPositionPillPhase.Hidden, finished.phase)
    }

    @Test
    fun resumingScrollCancelsOldHideGenerationDuringExit() {
        val frame = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry(1L, date(), 1L)), 4)
        val position = position(frame, 4)
        var state = reduceMediaGridPositionPillState(
            MediaGridPositionPillState(),
            MediaGridPositionPillEvent.ScrollStarted(position),
        )
        state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStopped)
        val hiding = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.HideTimeout(state.generation))
        val resumed = reduceMediaGridPositionPillState(hiding, MediaGridPositionPillEvent.ScrollStarted(position))
        assertEquals(MediaGridPositionPillPhase.Visible, resumed.phase)
        assertEquals(state.generation + 1L, resumed.generation)

        val staleExit = reduceMediaGridPositionPillState(
            resumed,
            MediaGridPositionPillEvent.ExitAnimationFinished(hiding.generation),
        )
        assertEquals(MediaGridPositionPillPhase.Visible, staleExit.phase)
    }

    @Test
    fun frameChangeClearsOldLabelAndDefaultFrameStaysHidden() {
        val first = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry(1L, date(), 1L)), 4)
        val second = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount), listOf(entry(2L, date(), 2L)), 4)
        var state = reduceMediaGridPositionPillState(
            MediaGridPositionPillState(),
            MediaGridPositionPillEvent.ScrollStarted(position(first, 4)),
        )
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.FrameChanged(second.key, hasBuckets = true, morphing = false),
        )
        assertEquals(MediaGridPositionPillPhase.Hidden, state.phase)
        assertNull(state.label)

        val default = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.Default), listOf(entry(3L, date(), 3L)), 4)
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.FrameChanged(default.key, hasBuckets = false, morphing = false),
        )
        assertEquals(MediaGridPositionPillPhase.Hidden, state.phase)
    }

    @Test
    fun completedScrollbarDragHandsOffOneFinalPositionWithFreshTimer() {
        val frame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
            listOf(entry(1L, date(), 1L)),
            4,
        )
        var state = reduceMediaGridPositionPillState(
            MediaGridPositionPillState(),
            MediaGridPositionPillEvent.ScrollbarDragFinished(position(frame, 4)),
        )
        assertEquals(MediaGridPositionPillPhase.Visible, state.phase)
        assertTrue(state.hideScheduled)
        val generation = state.generation
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.HideTimeout(generation),
        )
        assertEquals(MediaGridPositionPillPhase.Hiding, state.phase)
    }

    @Test
    fun staleScrollbarCompletionCannotReplaceANewerGeneration() {
        val frame = frame(
            ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
            listOf(entry(1L, date(), 1L)),
            4,
        )
        val first = position(frame, 4)
        val second = first.copy(label = "newer")
        var state = reduceMediaGridPositionPillState(
            MediaGridPositionPillState(),
            MediaGridPositionPillEvent.ScrollbarDragFinished(first),
        )
        val oldGeneration = state.generation
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.ScrollbarDragFinished(second),
        )
        assertEquals("newer", state.label)
        assertEquals(oldGeneration + 1L, state.generation)
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.HideTimeout(oldGeneration),
        )
        assertEquals(MediaGridPositionPillPhase.Visible, state.phase)
    }

    @Test
    fun morphKeepsOldLabelThenReevaluatesAfterHandoff() {
        val source = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry(1L, date(), 1L)), 4)
        val target = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry(1L, date(), 1L)), 5)
        val oldPosition = position(source, 4)
        val newPosition = position(target, 5)
        var state = reduceMediaGridPositionPillState(
            MediaGridPositionPillState(),
            MediaGridPositionPillEvent.ScrollStarted(oldPosition),
        )
        state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.MorphStarted)
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.FrameChanged(target.key, hasBuckets = true, morphing = true),
        )
        state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.PositionChanged(newPosition))
        assertEquals("2026/08/19", state.label)

        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.MorphFinished(target.key, newPosition),
        )
        assertEquals(MediaGridPositionPillPhase.Visible, state.phase)
        assertEquals("2026/08/17 ~ 2026/08/23", state.label)
        assertEquals(target.key, state.frameKey)
    }

    @Test
    fun morphDoesNotLetAnExpiredOldTimerRemoveThePillBeforeHandoff() {
        val source = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry(1L, date(), 1L)), 4)
        val target = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry(1L, date(), 1L)), 5)
        var state = reduceMediaGridPositionPillState(
            MediaGridPositionPillState(),
            MediaGridPositionPillEvent.ScrollStarted(position(source, 4)),
        )
        state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStopped)
        state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.MorphStarted)
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.HideTimeout(state.generation),
        )
        assertEquals(MediaGridPositionPillPhase.Visible, state.phase)
        assertTrue(state.hideRequestedDuringMorph)
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.MorphFinished(target.key, position(target, 5)),
        )
        assertEquals(MediaGridPositionPillPhase.Hiding, state.phase)
        assertEquals("2026/08/17 ~ 2026/08/23", state.label)
    }

    @Test
    fun pinchAloneDoesNotCreatePill() {
        val target = frame(ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), listOf(entry(1L, date(), 1L)), 5)
        var state = reduceMediaGridPositionPillState(MediaGridPositionPillState(), MediaGridPositionPillEvent.MorphStarted)
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.FrameChanged(target.key, hasBuckets = true, morphing = true),
        )
        state = reduceMediaGridPositionPillState(
            state,
            MediaGridPositionPillEvent.MorphFinished(target.key, position(target, 5)),
        )
        assertEquals(MediaGridPositionPillPhase.Hidden, state.phase)
        assertNull(state.label)
    }

    private fun position(frame: MediaGridFrameData, columnCount: Int): MediaGridCurrentPosition =
        checkNotNull(currentMediaGridPosition(frame, anchor(frame, 0), frame.key.dataKey.sort, columnCount))

    private fun anchor(
        frame: MediaGridFrameData,
        firstOrdinal: Int,
        lastOrdinal: Int = firstOrdinal,
    ): MediaGridViewportAnchorSignature = MediaGridViewportAnchorSignature(
        renderKey = frame.key,
        firstVisibleItemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal[firstOrdinal],
        lastVisibleItemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal[lastOrdinal],
        firstVisibleMediaOrdinal = firstOrdinal,
        lastVisibleMediaOrdinal = lastOrdinal,
        viewportWidthPx = 1000,
        viewportHeightPx = 1000,
        cellSizePx = 250,
        columnCount = frame.key.columnCount,
    )

    private fun frame(
        sort: ClassifiedSortState,
        entries: List<MediaGridEntry>,
        columnCount: Int = 4,
    ): MediaGridFrameData {
        val dataKey = MediaGridDataKey(1L, 1L, TweetFilterState(), sort)
        return buildMediaGridFrameData(entries, sort, columnCount, dataKey)
    }

    private fun entry(id: Long, createdAt: String, likeCount: Long?): MediaGridEntry = MediaGridEntry(
        entryId = id,
        clipId = id,
        assetId = id,
        mediaKey = "media-$id",
        mediaIndex = 0,
        type = "photo",
        displayUrl = null,
        downloadState = "remote",
        localPath = null,
        xCreatedAt = createdAt,
        likeCount = likeCount,
    )

    private fun date(): String = "2026-08-19T12:00:00Z"
}
