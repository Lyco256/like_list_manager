package com.lyco256.llm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MediaGridScrollbarUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visualScrollbarBoundsStayAtGridRightEdgeAcrossDragStates() {
        val frame = testFrame()
        val scrollbarState = MediaGridScrollbarState()

        composeRule.setContent {
            MaterialTheme {
                val gridState = rememberLazyGridState()
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("media_grid_bounds"),
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
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val gridBounds = composeRule.onNodeWithTag("media_grid_bounds")
            .fetchSemanticsNode().boundsInRoot
        val containerBounds = composeRule.onNodeWithTag("media_grid_scrollbar")
            .fetchSemanticsNode().boundsInRoot
        val trackBounds = composeRule.onNodeWithTag("media_grid_scrollbar_track")
            .fetchSemanticsNode().boundsInRoot
        val normalThumbBounds = composeRule.onNodeWithTag("media_grid_scrollbar_thumb")
            .fetchSemanticsNode().boundsInRoot

        assertEquals(gridBounds.right, containerBounds.right, 0.5f)
        assertEquals(gridBounds.right, trackBounds.right, 0.5f)
        assertEquals(gridBounds.right, normalThumbBounds.right, 0.5f)
        assertEquals(trackBounds.left, normalThumbBounds.left, 0.5f)
        assertTrue(containerBounds.left < trackBounds.left)
        assertTrue(containerBounds.width >= trackBounds.width * 4f)

        val geometry = calculateMediaGridScrollbarGeometry(
            totalMediaCount = 100,
            firstVisibleMediaOrdinal = 0,
            lastVisibleMediaOrdinal = 9,
            trackHeightPx = trackBounds.height,
            minThumbHeightPx = 32f,
        )
        composeRule.runOnIdle {
            assertTrue(scrollbarState.beginDrag(frame, geometry, geometry.thumbTopPx))
        }
        composeRule.waitForIdle()
        val draggingThumbBounds = composeRule.onNodeWithTag("media_grid_scrollbar_thumb")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(trackBounds.right, draggingThumbBounds.right, 0.5f)
        assertEquals(trackBounds.left, draggingThumbBounds.left, 0.5f)
        val labelBounds = composeRule.onNodeWithTag("media_grid_scrollbar_position_label")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(labelBounds.right < trackBounds.left)

        composeRule.runOnIdle {
            scrollbarState.finishDrag()
            assertTrue(scrollbarState.dragSnapshot.isFinalTargetPending)
        }
        composeRule.waitForIdle()
        val finalThumbBounds = composeRule.onNodeWithTag("media_grid_scrollbar_thumb")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(trackBounds.right, finalThumbBounds.right, 0.5f)
        assertEquals(trackBounds.left, finalThumbBounds.left, 0.5f)
    }

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
        val updatedLabelBounds = composeRule.onNodeWithTag("media_grid_scrollbar_position_label")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(updatedLabelBounds.top >= rootBounds.top)
        assertTrue(updatedLabelBounds.bottom <= rootBounds.bottom)
        composeRule.onAllNodesWithTag("media_grid_position_pill").assertCountEquals(0)

        composeRule.runOnIdle { scrollbarState.cancelDrag() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("media_grid_scrollbar_position_label").assertCountEquals(0)
    }

    @Test
    fun realPointerDragKeepsBucketPillWithinBucketMovesAtBoundaryAndHidesOnUp() {
        val frame = bucketFrame()
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

        val scrollbar = composeRule.onNodeWithTag("media_grid_scrollbar")
        val scrollbarBounds = scrollbar.fetchSemanticsNode().boundsInRoot
        val trackBounds = composeRule.onNodeWithTag("media_grid_scrollbar_track")
            .fetchSemanticsNode().boundsInRoot
        val thumbBounds = composeRule.onNodeWithTag("media_grid_scrollbar_thumb")
            .fetchSemanticsNode().boundsInRoot
        val grabOffset = thumbBounds.center.y - thumbBounds.top
        val maxThumbTop = trackBounds.height - thumbBounds.height
        fun localYForOrdinal(ordinal: Int): Float =
            maxThumbTop * ordinal.toFloat() / 90f + grabOffset

        scrollbar.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(scrollbarBounds.width / 2f, grabOffset))
            moveTo(androidx.compose.ui.geometry.Offset(scrollbarBounds.width / 2f, localYForOrdinal(20)), 400)
        }
        composeRule.waitForIdle()
        val firstBucketPill = composeRule.onNodeWithTag("media_grid_scrollbar_bucket_pill")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(trackBounds.right, firstBucketPill.right, 0.5f)
        assertEquals(12f * composeRule.density.density, firstBucketPill.width, 1f)
        assertEquals(4f * composeRule.density.density, firstBucketPill.height, 1f)

        scrollbar.performTouchInput {
            moveTo(androidx.compose.ui.geometry.Offset(scrollbarBounds.width / 2f, localYForOrdinal(30)), 300)
        }
        composeRule.waitForIdle()
        val sameBucketPill = composeRule.onNodeWithTag("media_grid_scrollbar_bucket_pill")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(firstBucketPill.top, sameBucketPill.top, 1f)

        scrollbar.performTouchInput {
            moveTo(androidx.compose.ui.geometry.Offset(scrollbarBounds.width / 2f, localYForOrdinal(50)), 400)
        }
        composeRule.waitForIdle()
        val nextBucketPill = composeRule.onNodeWithTag("media_grid_scrollbar_bucket_pill")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(nextBucketPill.top > sameBucketPill.top + 1f)

        scrollbar.performTouchInput { up() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("media_grid_scrollbar_bucket_pill").assertCountEquals(0)
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

    private fun bucketFrame(): MediaGridFrameData {
        val sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime)
        val entries = (0 until 100).map { index ->
            val day = when {
                index < 40 -> 1
                index < 70 -> 2
                else -> 3
            }
            MediaGridEntry(
                entryId = index.toLong() + 1L,
                clipId = index.toLong() + 1L,
                assetId = index.toLong() + 1L,
                mediaKey = "bucket-media-$index",
                mediaIndex = 0,
                type = "photo",
                displayUrl = null,
                downloadState = "downloaded",
                localPath = null,
                xCreatedAt = LocalDate.of(2026, 8, day)
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
