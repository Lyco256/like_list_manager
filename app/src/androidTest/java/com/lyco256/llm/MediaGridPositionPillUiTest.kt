package com.lyco256.llm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MediaGridPositionPillUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stopKeepsPillForThreeSecondsThenSlidesItOut() {
        composeRule.mainClock.autoAdvance = false
        var state by mutableStateOf(MediaGridPositionPillState())
        val position = position()
        composeRule.setContent {
            MaterialTheme {
                MediaGridPositionPill(state = state, onEvent = { event ->
                    state = reduceMediaGridPositionPillState(state, event)
                })
            }
        }

        composeRule.runOnIdle {
            state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStarted(position))
            state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStopped)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(MediaGridPositionPillAnimationMillis.toLong(), ignoreFrameDuration = true)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("media_grid_position_pill").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(
            MediaGridPositionPillDisplayMillis - MediaGridPositionPillAnimationMillis - 1L,
            ignoreFrameDuration = true,
        )
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("media_grid_position_pill").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1L, ignoreFrameDuration = true)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(MediaGridPositionPillPhase.Hiding, state.phase) }
        composeRule.mainClock.advanceTimeBy(MediaGridPositionPillAnimationMillis.toLong(), ignoreFrameDuration = true)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("media_grid_position_pill").assertCountEquals(0)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun resumingScrollDuringExitKeepsNewPillAndOldExitCannotRemoveIt() {
        composeRule.mainClock.autoAdvance = false
        var state by mutableStateOf(MediaGridPositionPillState())
        val position = position()
        composeRule.setContent {
            MaterialTheme {
                MediaGridPositionPill(state = state, onEvent = { event ->
                    state = reduceMediaGridPositionPillState(state, event)
                })
            }
        }

        composeRule.runOnIdle {
            state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStarted(position))
            state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStopped)
            state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.HideTimeout(state.generation))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val oldGeneration = state.generation
        composeRule.runOnIdle {
            state = reduceMediaGridPositionPillState(state, MediaGridPositionPillEvent.ScrollStarted(position))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(MediaGridPositionPillAnimationMillis.toLong() + 1L, ignoreFrameDuration = true)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("media_grid_position_pill").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(MediaGridPositionPillPhase.Visible, state.phase)
            assertEquals(oldGeneration + 1L, state.generation)
        }
        composeRule.mainClock.autoAdvance = true
    }

    private fun position(): MediaGridCurrentPosition {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val key = MediaGridRenderKey(MediaGridDataKey(1L, 1L, TweetFilterState(), sort), 4)
        return MediaGridCurrentPosition(key, mediaOrdinal = 0, bucketKey = "day", label = "2026/08/19")
    }
}
