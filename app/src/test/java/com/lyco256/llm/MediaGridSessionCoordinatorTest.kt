package com.lyco256.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridSessionCoordinatorTest {
    @Test
    fun scrollCheckpointOnlyFiresOnObservedTrueToFalse() {
        var state = MediaGridScrollCheckpointState()
        fun transition(value: Boolean): Boolean = mediaGridScrollCheckpointTransition(state, value).also { state = it.state }.shouldCheckpoint

        assertEquals(false, transition(false))
        assertEquals(false, transition(true))
        assertEquals(false, transition(true))
        assertEquals(true, transition(false))
        assertEquals(false, transition(false))
        assertEquals(false, transition(true))
        assertEquals(true, transition(false))
    }

    @Test
    fun completedScrollbarCheckpointIsNotDuplicatedByScrollEndTransition() {
        val completed = MediaGridScrollbarDragSnapshot(
            endReason = MediaGridScrollbarDragEnd.Completed,
            completionId = 1L,
        )
        var state = MediaGridScrollCheckpointState()

        state = mediaGridScrollCheckpointTransition(state, isScrollInProgress = true).state
        val completion = mediaGridScrollCheckpointTransition(
            state,
            isScrollInProgress = false,
            scrollbarSnapshot = completed,
        )
        assertTrue(completion.shouldCheckpoint)
        state = completion.state

        val duplicate = mediaGridScrollCheckpointTransition(
            state,
            isScrollInProgress = false,
            scrollbarSnapshot = completed,
        )
        assertFalse(duplicate.shouldCheckpoint)
    }

    @Test
    fun normalScrollAfterScrollbarCompletionStillCheckpoints() {
        val completed = MediaGridScrollbarDragSnapshot(
            endReason = MediaGridScrollbarDragEnd.Completed,
            completionId = 1L,
        )
        var state = MediaGridScrollCheckpointState()
        state = mediaGridScrollCheckpointTransition(
            state,
            isScrollInProgress = false,
            scrollbarSnapshot = completed,
        ).state

        state = mediaGridScrollCheckpointTransition(
            state,
            isScrollInProgress = true,
            scrollbarSnapshot = completed,
        ).state
        val normalScrollEnd = mediaGridScrollCheckpointTransition(
            state,
            isScrollInProgress = false,
            scrollbarSnapshot = completed,
        )
        assertTrue(normalScrollEnd.shouldCheckpoint)
    }

    @Test
    fun columnsAndSourceRevisionsDoNotChangeSessionIdentity() {
        val filter = TweetFilterState(query = "robot")
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val first = MediaGridDataKey(1L, 2L, filter, sort)
        val refreshed = MediaGridDataKey(9L, 7L, filter, sort)

        assertEquals(mediaGridSessionKey(first), mediaGridSessionKey(refreshed))
    }

    @Test
    fun explicitFilterOrSortChangeStartsAnotherSessionIdentity() {
        val base = MediaGridDataKey(1L, 1L, TweetFilterState(), ClassifiedSortState())
        val filtered = base.copy(filter = TweetFilterState(query = "different"))
        val sorted = base.copy(sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount))

        assertNotEquals(mediaGridSessionKey(base), mediaGridSessionKey(filtered))
        assertNotEquals(mediaGridSessionKey(base), mediaGridSessionKey(sorted))
    }
}
