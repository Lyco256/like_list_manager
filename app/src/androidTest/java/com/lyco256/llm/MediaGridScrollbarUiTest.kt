package com.lyco256.llm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MediaGridScrollbarUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun targetLabelFollowsOrdinalImmediatelyAndCancelRemovesIt() {
        val frame = testFrame()
        val scrollbarState = MediaGridScrollbarState()

        composeRule.setContent {
            MaterialTheme {
                val gridState = rememberLazyGridState()
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items((0 until 100).toList()) {
                            Box(Modifier.height(100.dp))
                        }
                    }
                    MediaGridScrollbar(
                        frame = frame,
                        anchor = anchor(frame),
                        state = gridState,
                        scrollbarState = scrollbarState,
                        enabled = true,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val geometry = calculateMediaGridScrollbarGeometry(
                totalMediaCount = 100,
                firstVisibleMediaOrdinal = 0,
                lastVisibleMediaOrdinal = 9,
                trackHeightPx = 600f,
                minThumbHeightPx = 32f,
            )
            assertTrue(scrollbarState.beginDrag(frame, geometry, geometry.thumbTopPx))
        }
        composeRule.waitForIdle()
        val labelBounds = composeRule.onNodeWithTag("media_grid_scrollbar_position_label")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(labelBounds.top >= rootBounds.top)
        assertTrue(labelBounds.bottom <= rootBounds.bottom)
        assertTrue(composeRule.onAllNodesWithText("2026/08/01").fetchSemanticsNodes().isNotEmpty())

        composeRule.runOnIdle { scrollbarState.updateDrag(580f) }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("2026/10/30").fetchSemanticsNodes().isNotEmpty())
        composeRule.onAllNodesWithTag("media_grid_position_pill").assertCountEquals(0)

        composeRule.runOnIdle { scrollbarState.cancelDrag() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("media_grid_scrollbar_position_label").assertCountEquals(0)
    }

    private fun testFrame(): MediaGridFrameData {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val entries = (0 until 100).map { index ->
            MediaGridEntry(
                entryId = index.toLong() + 1L,
                clipId = index.toLong() + 1L,
                assetId = index.toLong() + 1L,
                mediaKey = "media-$index",
                mediaIndex = 0,
                type = "photo",
                displayUrl = null,
                downloadState = "downloaded",
                localPath = null,
                xCreatedAt = LocalDate.of(2026, 8, 1)
                    .plusDays(index.toLong())
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toString(),
                likeCount = index.toLong(),
            )
        }
        return buildMediaGridFrameData(
            entries = entries,
            sort = sort,
            columnCount = 4,
            dataKey = MediaGridDataKey(1L, 1L, TweetFilterState(), sort),
        )
    }

    private fun anchor(frame: MediaGridFrameData): MediaGridViewportAnchorSignature =
        MediaGridViewportAnchorSignature(
            renderKey = frame.key,
            firstVisibleItemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal[0],
            lastVisibleItemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal[9],
            firstVisibleMediaOrdinal = 0,
            lastVisibleMediaOrdinal = 9,
            viewportWidthPx = 1000,
            viewportHeightPx = 600,
            cellSizePx = 100,
            columnCount = 4,
        )
}
