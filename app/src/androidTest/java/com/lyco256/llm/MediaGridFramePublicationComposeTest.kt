package com.lyco256.llm

import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MediaGridFramePublicationComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fakeFrameClockPublishesOnlyOncePerFrameWhileDemandRemains() {
        val target = FakeFramePublicationTarget()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MediaGridFramePublicationRunner(target) }

        target.demand.value = true
        composeRule.runOnIdle { assertEquals(0, target.publicationCount) }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { assertEquals(1, target.publicationCount) }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { assertEquals(2, target.publicationCount) }

        // Each advance is one fake frame and the runner invokes the controller once for it.
        assertEquals(2, target.publicationCount)
    }

    private class FakeFramePublicationTarget : MediaGridFramePublicationTarget {
        val demand = MutableStateFlow(false)
        var publicationCount = 0

        override val framePublicationDemand = demand.asStateFlow()

        override fun publishOneReadyImageForFrame() {
            publicationCount++
        }
    }
}
