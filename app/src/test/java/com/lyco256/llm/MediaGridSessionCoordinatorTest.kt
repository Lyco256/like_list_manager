package com.lyco256.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
