package com.lyco256.llm

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaGridMorphLazyGridHandoffComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun twoToThreeNoHeaderDelayedFrameKeepsCanvasAndCompletes() =
        runHandoff(2, 3, ClassifiedSortBase.Default, targetDelayFrames = 4)

    @Test
    fun threeToTwoNoHeaderCompletes() =
        runHandoff(3, 2, ClassifiedSortBase.Default)

    @Test
    fun fourToFiveChangesDayHeadersToWeekHeaders() =
        runHandoff(4, 5, ClassifiedSortBase.PostTime, expectHeaderChange = true)

    @Test
    fun fiveToFourChangesWeekHeadersToDayHeaders() =
        runHandoff(5, 4, ClassifiedSortBase.PostTime, expectHeaderChange = true)

    @Test
    fun eightToNineChangesWeekHeadersToMonthHeaders() =
        runHandoff(8, 9, ClassifiedSortBase.PostTime, expectHeaderChange = true)

    @Test
    fun nineToEightChangesMonthHeadersToWeekHeaders() =
        runHandoff(9, 8, ClassifiedSortBase.PostTime, expectHeaderChange = true)

    @Test
    fun elevenToTwelvePreservesThousandLikeCountBuckets() =
        runHandoff(11, 12, ClassifiedSortBase.LikeCount)

    @Test
    fun twelveToElevenPreservesThousandLikeCountBuckets() =
        runHandoff(12, 11, ClassifiedSortBase.LikeCount)

    @Test
    fun productionHostUsesTheSameLazyGridAndRemovesCanvasAfterHandoff() {
        val fixture = fixture(2, 3, ClassifiedSortBase.Default)
        val bitmaps = fixture.entries.associate { it.assetId to assetBitmap(it.assetId) }
        val preparedIndex = preparedIndex(bitmaps)
        val store = MediaGridRetainedImageStore(null)
        lateinit var hostState: MediaGridMorphProductionHostState
        val displayedFrame = mutableStateOf(fixture.sourceFrame)
        val displayedColumns = mutableStateOf(fixture.fromColumns)
        var columnChanges = 0
        val checkpointSuppression = CopyOnWriteArrayList<Boolean>()
        val anchorCheckpoints = CopyOnWriteArrayList<ClassifiedMediaGridScrollAnchor>()
        val gridState = LazyGridState(
            firstVisibleItemIndex = fixture.initialItemIndex,
            firstVisibleItemScrollOffset = fixture.initialItemScrollOffset,
        )
        try {
            composeRule.setContent {
                val frame = displayedFrame.value
                val columns = displayedColumns.value
                val density = LocalDensity.current
                hostState = rememberMediaGridMorphProductionHostState(
                    state = gridState,
                    sessionKey = mediaGridSessionKey(fixture.dataKey),
                    retainedImageStore = store,
                    enabled = true,
                )!!
                val identity = MediaGridMorphInteractionIdentity(
                    sourceRevision = frame.key.dataKey.sourceRevision,
                    frameKey = frame.key,
                    currentColumnCount = columns,
                    viewportSignature = fixture.pair.viewportSignature.copy(
                        renderKey = frame.key,
                        columnCount = columns,
                    ),
                )
                val interactionLocked = hostState.controller.interactionLocked.value ||
                    hostState.handoffSnapshot.value.suppressesUserScroll
                MaterialTheme {
                    Box(
                        Modifier
                            .requiredSize(
                                WidthPx.dp / density.density,
                                HeightPx.dp / density.density,
                            )
                            .testTag("production_morph_host"),
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            state = gridState,
                            userScrollEnabled = !interactionLocked,
                            modifier = Modifier.fillMaxSize().testTag("production_morph_grid"),
                        ) {
                            gridItems(
                                frame.items,
                                key = { it.key },
                                span = { item ->
                                    when (item) {
                                        is MediaGridHeaderItem -> GridItemSpan(maxLineSpan)
                                        is MediaGridCellItem -> GridItemSpan(1)
                                    }
                                },
                            ) { item ->
                                when (item) {
                                    is MediaGridHeaderItem -> Box(
                                        Modifier.fillMaxWidth().height(40.dp),
                                    )
                                    is MediaGridCellItem -> Box(
                                        Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Gray),
                                    )
                                }
                            }
                        }
                        MediaGridMorphProductionHost(
                            host = hostState,
                            frame = frame,
                            sessionKey = mediaGridSessionKey(fixture.dataKey),
                            identity = identity,
                            preparedPairsSnapshot = { mapOf(fixture.direction to fixture.pair) },
                            preparedIndex = preparedIndex,
                            state = gridState,
                            onColumnCountChange = { next ->
                                columnChanges++
                                displayedColumns.value = next
                                displayedFrame.value = if (next == fixture.toColumns) fixture.targetFrame else fixture.sourceFrame
                            },
                            onAnchorCheckpoint = { _, anchor -> anchorCheckpoints += anchor },
                            onCheckpointSuppressed = { checkpointSuppression += it },
                            modifier = Modifier.fillMaxSize().testTag("production_morph_canvas_layer"),
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                val center = fixtureAnchorSlot(fixture.pair, fixture.toColumns).startRect.center
                val plan = MediaGridMorphPlan.select(fixture.pair, center)
                val anchor = plan.anchor!!
                val targetCenter = Offset(
                    anchor.slot.endRect.left + anchor.slot.endRect.width * anchor.focalU - fixture.pair.viewport.left,
                    anchor.slot.endRect.top + anchor.slot.endRect.height * anchor.focalV - fixture.pair.viewport.top,
                )
                val identity = MediaGridMorphInteractionIdentity(
                    fixture.dataKey.sourceRevision,
                    fixture.sourceFrame.key,
                    fixture.fromColumns,
                    fixture.pair.viewportSignature,
                )
                assertTrue(
                    hostState.controller.beginPointers(
                        identity,
                        mapOf(fixture.direction to fixture.pair),
                        1L,
                        2L,
                        center - Offset(20f, 0f),
                        center + Offset(20f, 0f),
                    ),
                )
                hostState.controller.updatePointers(
                    targetCenter - Offset(5f, 0f),
                    targetCenter + Offset(5f, 0f),
                )
                hostState.controller.releasePointers()
            }
            composeRule.waitForIdle()
            assertEquals(1, columnChanges)
            assertEquals(MediaGridMorphPhase.Idle, hostState.controller.snapshot().phase)
            composeRule.onAllNodesWithTag("media_grid_morph_canvas").assertCountEquals(0)
            assertTrue(checkpointSuppression.contains(true))
            assertEquals(false, checkpointSuppression.last())
            assertEquals(1, anchorCheckpoints.size)
        } finally {
            composeRule.runOnIdle { store.close() }
            bitmaps.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun productionReadinessRequiresEveryPairAssetToBeResident() {
        val fixture = fixture(2, 3, ClassifiedSortBase.Default)
        val bitmaps = fixture.entries.associate { it.assetId to assetBitmap(it.assetId) }
        val completeIndex = preparedIndex(bitmaps)
        val identity = MediaGridMorphInteractionIdentity(
            sourceRevision = fixture.dataKey.sourceRevision,
            frameKey = fixture.sourceFrame.key,
            currentColumnCount = fixture.fromColumns,
            viewportSignature = fixture.pair.viewportSignature,
        )
        assertTrue(isMediaGridMorphProductionReady(fixture.pair, completeIndex))

        val requiredAssetId = fixture.pair.slots.first {
            it.startAssetId != null && it.endAssetId != null
        }.startAssetId!!
        val incompleteIndex = completeIndex.copy(
            preparedImageByAssetId = completeIndex.preparedImageByAssetId - requiredAssetId,
        )
        assertTrue(fixture.pair.matchesIdentity(identity))
        assertFalse(isMediaGridMorphProductionReady(fixture.pair, incompleteIndex))
    }

    @Test
    fun productionReadinessIgnoresOutsideOverscanAndZeroSizeSideAssets() {
        val fixture = fixture(2, 3, ClassifiedSortBase.Default)
        val bitmaps = fixture.entries.associate { it.assetId to assetBitmap(it.assetId) }
        val completeIndex = preparedIndex(bitmaps)
        val outsideSlot = MediaGridMorphSlot(
            row = 99,
            column = 0,
            startRect = Rect(10_000f, 10_000f, 10_100f, 10_100f),
            endRect = Rect(10_010f, 10_010f, 10_110f, 10_110f),
            startAssetId = 900_001L,
            endAssetId = 900_002L,
            startMediaOrdinal = null,
            endMediaOrdinal = null,
        )
        assertTrue(
            isMediaGridMorphProductionReady(
                fixture.pair.copy(slots = fixture.pair.slots + outsideSlot),
                completeIndex,
            ),
        )

        val visibleSlot = fixture.pair.slots.first {
            it.startRect.width > 0f && it.startRect.height > 0f &&
                it.startRect.left < fixture.pair.viewport.right &&
                it.startRect.right > fixture.pair.viewport.left &&
                it.startRect.top < fixture.pair.viewport.bottom &&
                it.startRect.bottom > fixture.pair.viewport.top
        }
        val zeroSizeStartSlot = visibleSlot.copy(
            startRect = Rect(
                visibleSlot.startRect.left,
                visibleSlot.startRect.top,
                visibleSlot.startRect.left,
                visibleSlot.startRect.bottom,
            ),
            startAssetId = 900_003L,
        )
        assertTrue(
            isMediaGridMorphProductionReady(
                fixture.pair.copy(
                    slots = fixture.pair.slots.map { slot ->
                        if (slot == visibleSlot) zeroSizeStartSlot else slot
                    },
                ),
                completeIndex,
            ),
        )
        bitmaps.values.forEach(Bitmap::recycle)
    }

    @Test
    fun productionGestureFallsBackOnceWhenPreparedPairIsUnavailable() {
        val fixture = fixture(2, 3, ClassifiedSortBase.Default)
        val identity = MediaGridMorphInteractionIdentity(
            sourceRevision = fixture.dataKey.sourceRevision,
            frameKey = fixture.sourceFrame.key,
            currentColumnCount = fixture.fromColumns,
            viewportSignature = fixture.pair.viewportSignature,
        )
        val controller = MediaGridMorphInteractionController()
        var callbackCount = 0
        var fallbackColumnCount = -1
        val stopCalls = AtomicInteger()
        composeRule.setContent {
            val density = LocalDensity.current
            Box(
                Modifier
                    .requiredSize(120.dp / density.density)
                    .testTag("production_fallback_root")
                    .mediaGridMorphGestureInput(
                        mode = MediaGridMorphGestureMode.Production,
                        controller = controller,
                        identity = identity,
                        preparedPairsSnapshot = { emptyMap() },
                        stopScroll = { stopCalls.incrementAndGet() },
                        onFallbackPinchFinished = { _, next ->
                            callbackCount++
                            fallbackColumnCount = next
                        },
                        fallbackColumnCount = { fixture.fromColumns },
                    ),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("production_fallback_root").performTouchInput {
            down(0, Offset(30f, 60f))
            down(1, Offset(90f, 60f))
            moveTo(0, Offset(40f, 60f))
            moveTo(1, Offset(80f, 60f))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        assertEquals(1, callbackCount)
        assertEquals(3, fallbackColumnCount)
        assertEquals(1, stopCalls.get())
        assertEquals(MediaGridMorphPhase.Idle, controller.snapshot().phase)
    }

    @Test
    fun shortPinchClaimsFallbackWithoutWaitingForASecondGesture() {
        val controller = MediaGridMorphInteractionController()
        val stopCalls = AtomicInteger()
        var callbackCount = 0
        var nextColumns = -1
        composeRule.setContent {
            Box(
                Modifier
                    .requiredSize(120.dp)
                    .testTag("short_pinch_fallback_root")
                    .mediaGridMorphGestureInput(
                        mode = MediaGridMorphGestureMode.Production,
                        controller = controller,
                        identity = null,
                        preparedPairsSnapshot = { emptyMap() },
                        stopScroll = { stopCalls.incrementAndGet() },
                        onFallbackPinchFinished = { _, next ->
                            callbackCount++
                            nextColumns = next
                        },
                        fallbackColumnCount = { 4 },
                    ),
            )
        }
        composeRule.onNodeWithTag("short_pinch_fallback_root").performTouchInput {
            down(0, Offset(10f, 60f))
            down(1, Offset(110f, 60f))
            moveTo(0, Offset(15f, 60f))
            moveTo(1, Offset(105f, 60f))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        assertEquals(1, callbackCount)
        assertEquals(5, nextColumns)
        assertEquals(1, stopCalls.get())
    }

    @Test
    fun realLazyGridKeepsScrollAndPanUntilPinchClaimThenStopsOnce() {
        val fixture = fixture(4, 5, ClassifiedSortBase.Default)
        val identity = MediaGridMorphInteractionIdentity(
            sourceRevision = fixture.dataKey.sourceRevision,
            frameKey = fixture.sourceFrame.key,
            currentColumnCount = fixture.fromColumns,
            viewportSignature = fixture.pair.viewportSignature,
        )
        val controller = MediaGridMorphInteractionController()
        val gridState = LazyGridState()
        val stopCalls = AtomicInteger()
        composeRule.setContent {
            val density = LocalDensity.current
            LazyVerticalGrid(
                columns = GridCells.Fixed(fixture.fromColumns),
                state = gridState,
                modifier = Modifier
                    .requiredSize(WidthPx.dp / density.density, HeightPx.dp / density.density)
                    .testTag("arbitration_grid")
                    .mediaGridMorphGestureInput(
                        mode = MediaGridMorphGestureMode.Production,
                        controller = controller,
                        identity = identity,
                        preparedPairsSnapshot = {
                            mapOf(MediaGridMorphDirection.IncreaseColumns to fixture.pair)
                        },
                        stopScroll = {
                            stopCalls.incrementAndGet()
                            gridState.stopScroll()
                        },
                    ),
            ) {
                gridItems((0 until 100).toList()) {
                    Box(Modifier.fillMaxWidth().height(60.dp))
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("arbitration_grid").performTouchInput {
            down(0, Offset(100f, 300f))
            moveBy(Offset(0f, -120f))
            up(0)
        }
        composeRule.waitForIdle()
        assertTrue(gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)

        composeRule.onNodeWithTag("arbitration_grid").performTouchInput {
            down(0, Offset(40f, 140f))
            down(1, Offset(100f, 140f))
            moveBy(Offset(0f, 30f))
            up(1)
            up(0)
        }
        composeRule.waitForIdle()
        assertEquals(0, stopCalls.get())
        assertEquals(MediaGridMorphPhase.Idle, controller.snapshot().phase)

        composeRule.onNodeWithTag("arbitration_grid").performTouchInput {
            down(0, Offset(100f, 140f))
            moveTo(0, Offset(100f, 120f))
            down(1, Offset(40f, 120f))
            moveTo(0, Offset(90f, 120f))
        }
        composeRule.waitForIdle()
        val trackedIds = controller.trackedPointerIds() ?: error("Morph did not claim")
        composeRule.onNodeWithTag("arbitration_grid").performTouchInput {
            down(2, Offset(70f, 40f))
            moveTo(2, Offset(70f, 50f))
        }
        composeRule.waitForIdle()
        assertEquals(trackedIds, controller.trackedPointerIds())
        assertEquals(1, stopCalls.get())
        assertEquals(MediaGridMorphPhase.Tracking, controller.snapshot().phase)

        composeRule.onNodeWithTag("arbitration_grid").performTouchInput {
            up(0)
            up(1)
            up(2)
        }
        composeRule.runOnIdle { controller.cancelPointers() }
        assertEquals(MediaGridMorphPhase.Idle, controller.snapshot().phase)
    }

    @Test
    fun productionHostRemovesTrackingCanvasOnSourceChangeWithoutColumnCallback() {
        val fixture = fixture(2, 3, ClassifiedSortBase.Default)
        val bitmaps = fixture.entries.associate { it.assetId to assetBitmap(it.assetId) }
        val preparedIndex = preparedIndex(bitmaps)
        val store = MediaGridRetainedImageStore(null)
        lateinit var hostState: MediaGridMorphProductionHostState
        val displayedFrame = mutableStateOf(fixture.sourceFrame)
        var columnChanges = 0
        val gridState = LazyGridState()
        try {
            composeRule.setContent {
                val frame = displayedFrame.value
                hostState = rememberMediaGridMorphProductionHostState(
                    state = gridState,
                    sessionKey = mediaGridSessionKey(frame.key.dataKey),
                    retainedImageStore = store,
                    enabled = true,
                )!!
                val identity = MediaGridMorphInteractionIdentity(
                    sourceRevision = frame.key.dataKey.sourceRevision,
                    frameKey = frame.key,
                    currentColumnCount = fixture.fromColumns,
                    viewportSignature = fixture.pair.viewportSignature.copy(renderKey = frame.key),
                )
                Box(
                    Modifier
                        .requiredSize(WidthPx.dp / LocalDensity.current.density)
                        .testTag("production_source_change_root"),
                ) {
                    MediaGridMorphProductionHost(
                        host = hostState,
                        frame = frame,
                        sessionKey = mediaGridSessionKey(frame.key.dataKey),
                        identity = identity,
                        preparedPairsSnapshot = { mapOf(fixture.direction to fixture.pair) },
                        preparedIndex = preparedIndex,
                        state = gridState,
                        onColumnCountChange = { columnChanges++ },
                        onAnchorCheckpoint = { _, _ -> },
                        onCheckpointSuppressed = { },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                val center = fixtureAnchorSlot(fixture.pair, fixture.toColumns).startRect.center
                val identity = MediaGridMorphInteractionIdentity(
                    fixture.dataKey.sourceRevision,
                    fixture.sourceFrame.key,
                    fixture.fromColumns,
                    fixture.pair.viewportSignature,
                )
                assertTrue(
                    hostState.controller.beginPointers(
                        identity,
                        mapOf(fixture.direction to fixture.pair),
                        1L,
                        2L,
                        center - Offset(20f, 0f),
                        center + Offset(20f, 0f),
                    ),
                )
                hostState.controller.updatePointers(
                    center - Offset(5f, 0f),
                    center + Offset(5f, 0f),
                )
            }
            composeRule.waitForIdle()
            assertEquals(MediaGridMorphPhase.Tracking, hostState.controller.snapshot().phase)
            composeRule.onAllNodesWithTag("media_grid_morph_canvas").assertCountEquals(1)

            composeRule.runOnIdle {
                displayedFrame.value = fixture.sourceFrame.copy(
                    key = fixture.sourceFrame.key.copy(
                        dataKey = fixture.sourceFrame.key.dataKey.copy(sourceRevision = 99L),
                    ),
                )
            }
            composeRule.waitForIdle()
            assertEquals(MediaGridMorphPhase.Idle, hostState.controller.snapshot().phase)
            assertEquals(0, columnChanges)
            composeRule.onAllNodesWithTag("media_grid_morph_canvas").assertCountEquals(0)
        } finally {
            composeRule.runOnIdle { store.close() }
            bitmaps.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun eightToNineChangesLikeCountBucketGranularity() =
        runHandoff(8, 9, ClassifiedSortBase.LikeCount, expectHeaderChange = true)

    @Test
    fun nineToEightChangesLikeCountBucketGranularity() =
        runHandoff(9, 8, ClassifiedSortBase.LikeCount, expectHeaderChange = true)

    @Test
    fun twoToThreeAtViewportEndCompletes() =
        runHandoff(2, 3, ClassifiedSortBase.Default, viewportMode = ViewportMode.End)

    @Test
    fun threeToTwoFromPartiallyVisibleRowCompletes() =
        runHandoff(3, 2, ClassifiedSortBase.Default, viewportMode = ViewportMode.Partial)

    @Test
    fun disappearingTargetAssetUsesOrdinalFallback() {
        val fixture = fixture(4, 5, ClassifiedSortBase.Default)
        val removedAsset = fixture.plan.anchor!!.slot.endAssetId!!
        val remaining = fixture.entries.filterNot { it.assetId == removedAsset }
        val fallbackFrame = buildMediaGridFrameData(
            remaining,
            fixture.sort,
            5,
            fixture.dataKey,
        )
        val result = runFixture(fixture.copy(targetFrame = fallbackFrame))
        assertTrue(
            result.snapshots.any {
                it.resolvedTarget?.assetId != null &&
                    it.resolvedTarget.assetId != removedAsset
            },
        )
        assertEquals(MediaGridMorphPhase.Idle, result.controller.snapshot().phase)
    }

    @Test
    fun invalidTargetFrameRollsBackWithoutDroppingCanvasEarly() {
        val fixture = fixture(4, 5, ClassifiedSortBase.Default)
        val invalidFrame = fixture.targetFrame.copy(
            key = fixture.targetFrame.key.copy(columnCount = 6),
        )
        val result = runFixture(fixture.copy(targetFrame = invalidFrame), expectRollback = true)
        assertEquals(1, result.commands.count {
            it is MediaGridMorphGridHandoffCommand.ChangeColumnCount
        })
        assertEquals(1, result.commands.count {
            it is MediaGridMorphGridHandoffCommand.RollbackColumnCount
        })
        assertEquals(1, result.commands.count {
            it is MediaGridMorphGridHandoffCommand.Cancel
        })
        assertTrue(result.snapshots.any {
            it.phase == MediaGridMorphGridHandoffPhase.RollingBack
        })
        assertEquals(MediaGridMorphPhase.Idle, result.controller.snapshot().phase)
    }

    @Test
    fun canvasRemovalMatchesUnderlyingCenterAndCellBoundary() {
        val fixture = fixture(2, 3, ClassifiedSortBase.Default)
        val result = runFixture(fixture, captureBoundary = true)
        val before = result.beforePixels!!
        val after = result.afterPixels!!
        val target = result.request.targetAnchor
        val centerX = target.maintainedCanvasPosition.x.toInt()
        val centerY = target.maintainedCanvasPosition.y.toInt()
        val boundaryInside = (
            target.endRect.right -
                result.request.plan.viewport.left +
                result.request.finalCorrection.x -
                2f
            ).toInt()
        assertPixelClose(before[centerX, centerY], after[centerX, centerY])
        assertPixelClose(before[boundaryInside, centerY], after[boundaryInside, centerY])
    }

    private fun runHandoff(
        fromColumns: Int,
        toColumns: Int,
        sortBase: ClassifiedSortBase,
        targetDelayFrames: Int = 0,
        viewportMode: ViewportMode = ViewportMode.Start,
        expectHeaderChange: Boolean = false,
    ) {
        val fixture = fixture(fromColumns, toColumns, sortBase, viewportMode)
        val result = runFixture(
            fixture,
            targetDelayFrames = targetDelayFrames,
            verifyUserScrollBoundary = fromColumns == 2 && toColumns == 3,
        )
        assertEquals(MediaGridMorphPhase.Idle, result.controller.snapshot().phase)
        assertEquals(
            "commands=${result.commands} snapshots=${result.snapshots}",
            toColumns,
            result.controller.snapshot().fromColumnCount,
        )
        assertEquals(1, result.commands.count {
            it is MediaGridMorphGridHandoffCommand.ChangeColumnCount
        })
        assertEquals(1, result.commands.count {
            it is MediaGridMorphGridHandoffCommand.Complete
        })
        assertFalse(result.commands.any {
            it is MediaGridMorphGridHandoffCommand.RollbackColumnCount
        })
        assertTrue(result.snapshots.any {
            it.phase == MediaGridMorphGridHandoffPhase.WaitingForTargetFrame
        })
        assertTrue(result.snapshots.any {
            it.phase == MediaGridMorphGridHandoffPhase.VerifyingTarget
        })
        assertTrue(result.snapshots.any {
            it.phase == MediaGridMorphGridHandoffPhase.ReadyToComplete
        })
        assertTrue(result.snapshots.all { it.correctionAttempts <= 3 })
        val correctionCommandCount = result.commands.count {
            it is MediaGridMorphGridHandoffCommand.ScrollBy
        }
        if (result.request.finalCorrection.y == 0f) {
            assertEquals(0, correctionCommandCount)
        } else {
            assertTrue(correctionCommandCount in 1..3)
        }
        val geometry = result.geometries.last { it.assetId == result.request.targetAnchor.assetId }
        val target = result.request.targetAnchor
        val actualFocal = Offset(
            geometry.rect.left + geometry.rect.width * target.focalU,
            geometry.rect.top + geometry.rect.height * target.focalV,
        )
        assertTrue(abs(actualFocal.x - target.maintainedCanvasPosition.x) <= 1f)
        assertTrue(abs(actualFocal.y - target.maintainedCanvasPosition.y) <= 1f)
        assertEquals(result.initialModelBuilds, result.finalModelBuilds)
        assertEquals(result.initialImageResolutions, result.finalImageResolutions)

        if (sortBase != ClassifiedSortBase.Default) {
            val sourceHeaders = fixture.sourceFrame.items.filterIsInstance<MediaGridHeaderItem>()
            val targetHeaders = fixture.targetFrame.items.filterIsInstance<MediaGridHeaderItem>()
            assertTrue(sourceHeaders.isNotEmpty())
            assertTrue(targetHeaders.isNotEmpty())
            assertEquals(
                expectHeaderChange,
                sourceHeaders.map { it.key } != targetHeaders.map { it.key },
            )
            val precedingHeader = fixture.targetFrame.items
                .take(geometry.itemIndex)
                .filterIsInstance<MediaGridHeaderItem>()
                .last()
            val bounds = composeRule.onNodeWithTag(precedingHeader.key)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue(abs(bounds.width - WidthPx) <= 1f)
        }
    }

    private fun runFixture(
        fixture: Fixture,
        targetDelayFrames: Int = 0,
        expectRollback: Boolean = false,
        captureBoundary: Boolean = false,
        verifyUserScrollBoundary: Boolean = false,
    ): Result {
        composeRule.mainClock.autoAdvance = false
        val bitmaps = fixture.entries.associate { it.assetId to assetBitmap(it.assetId) }
        val preparedIndex = preparedIndex(bitmaps)
        val commands = CopyOnWriteArrayList<MediaGridMorphGridHandoffCommand>()
        val snapshots = CopyOnWriteArrayList<MediaGridMorphGridHandoffSnapshot>()
        val geometries = CopyOnWriteArrayList<MediaGridMorphVisibleItemGeometry>()
        val modelBuilds = AtomicInteger()
        val imageResolutions = AtomicInteger()
        val userScrollEnabledStates = CopyOnWriteArrayList<Boolean>()
        val gridState = androidx.compose.foundation.lazy.grid.LazyGridState(
            firstVisibleItemIndex = fixture.initialItemIndex,
            firstVisibleItemScrollOffset = fixture.initialItemScrollOffset,
        )
        val controller = awaitingController(fixture)
        val handoffRequest = controller.snapshot().handoffRequest!!
        lateinit var completionAllowed: MutableState<Boolean>
        try {
            composeRule.setContent {
                val density = LocalDensity.current
                completionAllowed = remember { mutableStateOf(false) }
                MaterialTheme {
                    MediaGridMorphLazyGridHandoffTestHost(
                        sourceFrame = fixture.sourceFrame,
                        targetFrame = fixture.targetFrame,
                        controller = controller,
                        preparedPairsSnapshot = {
                            mapOf(fixture.direction to fixture.pair)
                        },
                        preparedIndex = preparedIndex,
                        targetFrameDelayFrames = targetDelayFrames,
                        completionAllowed = completionAllowed.value,
                        modifier = Modifier.requiredSize(
                            WidthPx.dp / density.density,
                            HeightPx.dp / density.density,
                        ),
                        state = gridState,
                        onSnapshot = snapshots::add,
                        onCommand = commands::add,
                        onVisibleTargetGeometry = geometries::add,
                        onRenderModelBuilt = { modelBuilds.incrementAndGet() },
                        onImageResolved = { imageResolutions.incrementAndGet() },
                        onUserScrollEnabledChanged = userScrollEnabledStates::add,
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag("media_grid_morph_canvas").assertCountEquals(1)
            assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, controller.snapshot().phase)
            val initialModelBuilds = modelBuilds.get()
            val initialImageResolutions = imageResolutions.get()

            repeat(if (targetDelayFrames > 0) 2 else 1) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            if (verifyUserScrollBoundary) {
                assertTrue(userScrollEnabledStates.contains(false))
            }
            composeRule.onAllNodesWithTag("media_grid_morph_canvas").assertCountEquals(1)
            if (!captureBoundary) {
                composeRule.runOnIdle { completionAllowed.value = true }
            }

            var beforePixels: androidx.compose.ui.graphics.PixelMap? = null
            if (captureBoundary) {
                var frameCount = 0
                while (
                    snapshots.lastOrNull()?.phase !=
                    MediaGridMorphGridHandoffPhase.ReadyToComplete &&
                    frameCount < 20
                ) {
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.waitForIdle()
                    frameCount++
                }
                assertEquals(
                    MediaGridMorphGridHandoffPhase.ReadyToComplete,
                    snapshots.last().phase,
                )
                composeRule.onAllNodesWithTag("media_grid_morph_canvas").assertCountEquals(1)
                beforePixels = composeRule.onNodeWithTag("media_grid_handoff_host")
                    .captureToImage()
                    .toPixelMap()
                composeRule.runOnIdle { completionAllowed.value = true }
            }

            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            assertEquals(
                "commands=$commands failureReasons=" +
                    snapshots.mapNotNull { it.failureReason }.distinct() +
                    " lastGeometry=${geometries.lastOrNull()}",
                MediaGridMorphPhase.Idle,
                controller.snapshot().phase,
            )
            composeRule.onAllNodesWithTag("media_grid_morph_canvas").assertCountEquals(0)
            val afterPixels = if (captureBoundary) {
                composeRule.onNodeWithTag("media_grid_handoff_host")
                    .captureToImage()
                    .toPixelMap()
            } else null
            if (expectRollback) {
                assertTrue(snapshots.any {
                    it.phase == MediaGridMorphGridHandoffPhase.RollingBack
                })
            }
            if (verifyUserScrollBoundary) {
                assertEquals(true, userScrollEnabledStates.last())
                assertTrue(gridState.canScrollBackward || gridState.canScrollForward)
            }
            return Result(
                controller = controller,
                request = handoffRequest,
                commands = commands,
                snapshots = snapshots,
                geometries = geometries,
                initialModelBuilds = initialModelBuilds,
                finalModelBuilds = modelBuilds.get(),
                initialImageResolutions = initialImageResolutions,
                finalImageResolutions = imageResolutions.get(),
                beforePixels = beforePixels,
                afterPixels = afterPixels,
            )
        } finally {
            bitmaps.values.forEach(Bitmap::recycle)
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun awaitingController(fixture: Fixture): MediaGridMorphInteractionController {
        val pair = fixture.pair
        val controller = MediaGridMorphInteractionController()
        val identity = MediaGridMorphInteractionIdentity(
            fixture.dataKey.sourceRevision,
            fixture.sourceFrame.key,
            fixture.fromColumns,
            pair.viewportSignature,
        )
        val anchorSlot = fixtureAnchorSlot(pair, fixture.toColumns)
        val center = anchorSlot.startRect.center
        val anchorPlan = MediaGridMorphPlan.select(pair, center)
        val anchor = anchorPlan.anchor!!
        val targetCenterX =
            anchor.slot.endRect.left + anchor.slot.endRect.width * anchor.focalU -
                pair.viewport.left
        val targetCenterY =
            anchor.slot.endRect.top + anchor.slot.endRect.height * anchor.focalV -
                pair.viewport.top
        val finalCenter = Offset(
            targetCenterX,
            targetCenterY - if (
                fixture.fromColumns == 2 &&
                fixture.toColumns == 3 &&
                fixture.initialItemIndex == 0
            ) 20f else 0f,
        )
        assertTrue(
            controller.beginPointers(
                identity,
                mapOf(fixture.direction to pair),
                1L,
                2L,
                center - Offset(20f, 0f),
                center + Offset(20f, 0f),
            ),
        )
        if (fixture.direction == MediaGridMorphDirection.IncreaseColumns) {
            controller.updatePointers(
                finalCenter - Offset(5f, 0f),
                finalCenter + Offset(5f, 0f),
            )
        } else {
            controller.updatePointers(
                finalCenter - Offset(50f, 0f),
                finalCenter + Offset(50f, 0f),
            )
        }
        controller.releasePointers()
        controller.advanceSettleElapsed(controller.snapshot().interactionGeneration, 180L)
        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, controller.snapshot().phase)
        return controller
    }

    private fun fixture(
        fromColumns: Int,
        toColumns: Int,
        sortBase: ClassifiedSortBase,
        viewportMode: ViewportMode = ViewportMode.Start,
    ): Fixture {
        val direction = if (toColumns > fromColumns) {
            MediaGridMorphDirection.IncreaseColumns
        } else {
            MediaGridMorphDirection.DecreaseColumns
        }
        val sort = ClassifiedSortState(
            baseOrder = sortBase,
            postTimeDescending = true,
            likeCountDescending = true,
        )
        val entries = List(72) { index ->
            MediaGridEntry(
                entryId = index.toLong() + 1L,
                clipId = index.toLong() + 1L,
                assetId = index.toLong() + 1L,
                mediaKey = "media-$index",
                mediaIndex = 0,
                type = "photo",
                displayUrl = null,
                downloadState = "saved",
                localPath = null,
                xCreatedAt = when {
                    index < 18 -> "2026-07-${30 - index / 3}T00:00:00Z"
                    index < 42 -> "2026-06-${28 - (index - 18) / 3}T00:00:00Z"
                    else -> "2026-05-${28 - (index - 42) / 3}T00:00:00Z"
                },
                likeCount = 12_000L - index * 175L,
            )
        }
        val dataKey = MediaGridDataKey(1L, 1L, TweetFilterState(), sort)
        val sourceFrame = buildMediaGridFrameData(entries, sort, fromColumns, dataKey)
        val targetFrame = buildMediaGridFrameData(entries, sort, toColumns, dataKey)
        val initialItemIndex = when (viewportMode) {
            ViewportMode.Start -> 0
            ViewportMode.Partial ->
                sourceFrame.ordinalIndex.itemIndexByMediaOrdinal[entries.size / 3]
            ViewportMode.End -> sourceFrame.items.lastIndex
        }
        val initialItemScrollOffset = if (viewportMode == ViewportMode.Partial) 37 else 0
        val capture = capture(
            sourceFrame,
            entries,
            sort,
            fromColumns,
            initialItemIndex,
            initialItemScrollOffset,
        )
        val pair = buildMediaGridMorphPreparedPairs(capture)[direction]!!
        val selectedSlot = fixtureAnchorSlot(pair, toColumns)
        val plan = MediaGridMorphPlan.select(pair, selectedSlot.startRect.center)
        return Fixture(
            fromColumns,
            toColumns,
            direction,
            sort,
            entries,
            dataKey,
            sourceFrame,
            targetFrame,
            pair,
            plan,
            initialItemIndex,
            initialItemScrollOffset,
        )
    }

    private fun capture(
        frame: MediaGridFrameData,
        entries: List<MediaGridEntry>,
        sort: ClassifiedSortState,
        columns: Int,
        initialItemIndex: Int,
        initialItemScrollOffset: Int,
    ): MediaGridMorphCapture {
        val cellSize = WidthPx.toFloat() / columns
        val headerHeight = 40f
        val allMediaRects = ArrayList<MediaGridMorphCapturedRect>()
        val allHeaderRects = ArrayList<MediaGridMorphCapturedHeaderRect>()
        val itemTopByIndex = FloatArray(frame.items.size)
        var y = 0f
        var column = 0
        frame.items.forEachIndexed { itemIndex, item ->
            when (item) {
                is MediaGridHeaderItem -> {
                    if (column != 0) {
                        y += cellSize
                        column = 0
                    }
                    itemTopByIndex[itemIndex] = y
                    val firstOrdinal = frame.items
                        .drop(itemIndex + 1)
                        .firstNotNullOfOrNull {
                            (it as? MediaGridCellItem)?.let { cell ->
                                frame.ordinalIndex.mediaOrdinalByAssetId[cell.entry.assetId]
                            }
                        }
                        ?: 0
                    allHeaderRects += MediaGridMorphCapturedHeaderRect(
                        firstOrdinal,
                        item.key.removePrefix("media_grid_header_"),
                        Rect(0f, y, WidthPx.toFloat(), y + headerHeight),
                    )
                    y += headerHeight
                }
                is MediaGridCellItem -> {
                    itemTopByIndex[itemIndex] = y
                    val ordinal = frame.ordinalIndex.mediaOrdinalByAssetId[item.entry.assetId]!!
                    val left = column * cellSize
                    val rect = Rect(left, y, left + cellSize, y + cellSize)
                    allMediaRects += MediaGridMorphCapturedRect(ordinal, rect)
                    column++
                    if (column == columns) {
                        column = 0
                        y += cellSize
                    }
                }
            }
        }
        if (column != 0) y += cellSize
        val requestedScrollY =
            itemTopByIndex[initialItemIndex] + initialItemScrollOffset.toFloat()
        val scrollY = requestedScrollY.coerceIn(0f, (y - HeightPx).coerceAtLeast(0f))
        fun viewportRect(rect: Rect) = Rect(
            rect.left,
            rect.top - scrollY,
            rect.right,
            rect.bottom - scrollY,
        )
        val mediaRects = allMediaRects.mapNotNull { captured ->
            val rect = viewportRect(captured.rect)
            captured.copy(rect = rect).takeIf {
                rect.top < HeightPx && rect.bottom > 0f
            }
        }
        val headerRects = allHeaderRects.mapNotNull { captured ->
            val rect = viewportRect(captured.rect)
            captured.copy(rect = rect).takeIf {
                rect.top < HeightPx && rect.bottom > 0f
            }
        }
        val firstVisible = mediaRects.minOf { it.mediaOrdinal }
        val lastVisible = mediaRects.maxOf { it.mediaOrdinal }
        val first = (firstVisible - columns * 2).coerceAtLeast(0)
        val last = (lastVisible + columns * 2).coerceAtMost(entries.lastIndex)
        val range = first..last
        val media = range.map { ordinal ->
            val entry = entries[ordinal]
            MediaGridMorphCapturedMedia(
                assetId = entry.assetId,
                mediaOrdinal = ordinal,
                itemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal[ordinal],
                xCreatedAt = entry.xCreatedAt,
                likeCount = entry.likeCount,
            )
        }
        val frameKey = frame.key
        return MediaGridMorphCapture(
            identity = MediaGridMorphPreparationIdentity(
                sourceRevision = frameKey.dataKey.sourceRevision,
                frameKey = frameKey,
                columnCount = columns,
                viewportSignature = MediaGridViewportSignature(
                    renderKey = frameKey,
                    firstVisibleItemIndex = initialItemIndex,
                    lastVisibleItemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal[lastVisible],
                    firstVisibleMediaOrdinal = firstVisible,
                    lastVisibleMediaOrdinal = lastVisible,
                    viewportWidthPx = WidthPx,
                    viewportHeightPx = HeightPx,
                    cellSizePx = cellSize.toInt(),
                    columnCount = columns,
                ),
            ),
            viewport = Rect(0f, 0f, WidthPx.toFloat(), HeightPx.toFloat()),
            cellSizePx = cellSize,
            headerHeightPx = headerHeight,
            sortBase = sort.baseOrder,
            mediaOrdinalRange = range,
            media = media,
            precedingMedia = first.takeIf { it > 0 }?.let { ordinal ->
                entries[ordinal - 1].let {
                    MediaGridMorphCapturedMedia(
                        it.assetId,
                        ordinal - 1,
                        frame.ordinalIndex.itemIndexByMediaOrdinal[ordinal - 1],
                        it.xCreatedAt,
                        it.likeCount,
                    )
                }
            },
            visibleMediaRects = mediaRects,
            visibleHeaderRects = headerRects,
        )
    }

    private fun fixtureAnchorSlot(
        pair: MediaGridMorphPreparedPair,
        toColumns: Int,
    ): MediaGridMorphSlot {
        val candidates = pair.slots.asSequence().filter {
            it.startAssetId != null &&
                it.endAssetId != null &&
                it.startRect.width > 0f &&
                it.startRect.height > 0f
        }
        if (toColumns == 11) {
            return candidates.minWith(compareBy(MediaGridMorphSlot::row, MediaGridMorphSlot::column))
        }
        val viewportCenter = pair.viewport.center
        return candidates.minBy {
            val dx = it.startRect.center.x - viewportCenter.x
            val dy = it.startRect.center.y - viewportCenter.y
            dx * dx + dy * dy
        }
    }

    private fun preparedIndex(bitmaps: Map<Long, Bitmap>): MediaGridResidentCanvasPreparedIndex {
        val prepared = bitmaps.mapValues { (assetId, bitmap) ->
            MediaGridResidentCanvasPreparedImage(
                assetId = assetId,
                identity = MediaGridResidentImageIdentity(assetId, "cache-$assetId", "source-$assetId"),
                image = bitmap.asImageBitmap(),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
            )
        }
        return MediaGridResidentCanvasPreparedIndex(1L, Collections.unmodifiableMap(prepared))
    }

    private fun assetBitmap(assetId: Long): Bitmap {
        val mixed = assetId * 1_103_515_245L + 12_345L
        val color = AndroidColor.rgb(
            (((mixed ushr 16) and 0x7f) + 96).toInt(),
            (((mixed ushr 8) and 0x7f) + 96).toInt(),
            (((mixed ushr 0) and 0x7f) + 96).toInt(),
        )
        return Bitmap.createBitmap(8, 8, Bitmap.Config.RGB_565).also { it.eraseColor(color) }
    }

    private fun assertPixelClose(
        before: androidx.compose.ui.graphics.Color,
        after: androidx.compose.ui.graphics.Color,
    ) {
        val distance = abs(before.red - after.red) +
            abs(before.green - after.green) +
            abs(before.blue - after.blue)
        assertTrue("before=$before after=$after distance=$distance", distance <= 0.08f)
    }

    private data class Fixture(
        val fromColumns: Int,
        val toColumns: Int,
        val direction: MediaGridMorphDirection,
        val sort: ClassifiedSortState,
        val entries: List<MediaGridEntry>,
        val dataKey: MediaGridDataKey,
        val sourceFrame: MediaGridFrameData,
        val targetFrame: MediaGridFrameData,
        val pair: MediaGridMorphPreparedPair,
        val plan: MediaGridMorphPlan,
        val initialItemIndex: Int,
        val initialItemScrollOffset: Int,
    )

    private data class Result(
        val controller: MediaGridMorphInteractionController,
        val request: MediaGridMorphHandoffRequest,
        val commands: List<MediaGridMorphGridHandoffCommand>,
        val snapshots: List<MediaGridMorphGridHandoffSnapshot>,
        val geometries: List<MediaGridMorphVisibleItemGeometry>,
        val initialModelBuilds: Int,
        val finalModelBuilds: Int,
        val initialImageResolutions: Int,
        val finalImageResolutions: Int,
        val beforePixels: androidx.compose.ui.graphics.PixelMap?,
        val afterPixels: androidx.compose.ui.graphics.PixelMap?,
    )

    private companion object {
        const val WidthPx = 360
        const val HeightPx = 480
    }

    private enum class ViewportMode {
        Start,
        Partial,
        End,
    }
}
