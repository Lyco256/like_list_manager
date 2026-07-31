package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridMorphHandoffTest {
    @Test
    fun targetAnchorPrefersInteractionSlotAndMatchesFinalCanvasPoint() {
        val preferred = slot(0, 0, 1L, 11L, 0, 0, Rect(0f, 0f, 100f, 100f))
        val centerSlot = slot(0, 1, 2L, 12L, 1, 1, Rect(100f, 0f, 200f, 100f))
        val plan = plan(
            slots = listOf(preferred, centerSlot),
            anchor = MediaGridMorphAnchor(1L, preferred, 0.25f, 0.5f, Offset(25f, 50f)),
        )

        val target = selectMediaGridMorphTargetAnchor(
            plan = plan,
            finalCorrection = Offset(125f, 0f),
            fixedFocalCenter = Offset(150f, 50f),
        )!!

        assertEquals(11L, target.assetId)
        assertEquals(0, target.mediaOrdinal)
        assertEquals(0.25f, target.focalU, 0.001f)
        assertEquals(0.5f, target.focalV, 0.001f)
        assertEquals(150f, target.maintainedCanvasPosition.x, 0.001f)
        assertEquals(50f, target.maintainedCanvasPosition.y, 0.001f)
    }

    @Test
    fun targetAnchorFallsBackToContainingThenNearestEndSlotAndRejectsNoTarget() {
        val empty = slot(0, 0, 1L, null, 0, null, Rect(0f, 0f, 0f, 0f))
        val left = slot(0, 1, 2L, 12L, 1, 4, Rect(0f, 0f, 100f, 100f))
        val right = slot(0, 2, 3L, 13L, 2, 5, Rect(100f, 0f, 200f, 100f))
        val plan = plan(
            slots = listOf(empty, left, right),
            anchor = MediaGridMorphAnchor(1L, empty, 0f, 0f, Offset.Zero),
        )

        val containing = selectMediaGridMorphTargetAnchor(
            plan,
            finalCorrection = Offset.Zero,
            fixedFocalCenter = Offset(150f, 50f),
        )!!
        assertEquals(13L, containing.assetId)

        val nearest = selectMediaGridMorphTargetAnchor(
            plan,
            finalCorrection = Offset.Zero,
            fixedFocalCenter = Offset(260f, 50f),
        )!!
        assertEquals(13L, nearest.assetId)
        assertEquals(1f, nearest.focalU, 0.001f)
        assertNull(
            selectMediaGridMorphTargetAnchor(
                plan(slots = listOf(empty), anchor = null),
                Offset.Zero,
                Offset.Zero,
            ),
        )
    }

    @Test
    fun awaitingAllowsSourceAndExpectedTargetIdentityButCancelsStaleChanges() {
        val controller = awaitingController()
        val request = controller.snapshot().handoffRequest!!
        val sourceIdentity = identity(request.sourceFrameKey, request.fromColumnCount, request)
        val sourceAfterColumnCommand = sourceIdentity.copy(currentColumnCount = request.toColumnCount)
        val targetIdentity = identity(request.expectedTargetFrameKey, request.toColumnCount, request)

        controller.updateIdentity(sourceAfterColumnCommand)
        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, controller.snapshot().phase)
        assertEquals(1f, controller.snapshot().progress, 0.001f)
        controller.updateIdentity(targetIdentity)
        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, controller.snapshot().phase)
        assertEquals(request.plan, controller.snapshot().plan)

        controller.updateIdentity(targetIdentity.copy(
            frameKey = targetIdentity.frameKey.copy(
                dataKey = targetIdentity.frameKey.dataKey.copy(hierarchyRevision = 99L),
            ),
        ))
        assertEquals(MediaGridMorphPhase.Failed, controller.snapshot().phase)
        assertEquals(MediaGridMorphFailureReason.IdentityMismatch, controller.snapshot().failureReason)
        assertNull(controller.snapshot().handoffRequest)
    }

    @Test
    fun awaitingCancelsSourceRevisionDataKeyAndViewportChangesIndependently() {
        fun assertCancelled(transform: (MediaGridMorphInteractionIdentity) -> MediaGridMorphInteractionIdentity) {
            val controller = awaitingController()
            val request = controller.snapshot().handoffRequest!!
            val target = identity(request.expectedTargetFrameKey, request.toColumnCount, request)
            controller.updateIdentity(transform(target))
            assertEquals(MediaGridMorphPhase.Failed, controller.snapshot().phase)
            assertEquals(MediaGridMorphFailureReason.IdentityMismatch, controller.snapshot().failureReason)
            assertNull(controller.snapshot().handoffRequest)
        }

        assertCancelled { it.copy(sourceRevision = it.sourceRevision + 1L) }
        assertCancelled {
            val changedDataKey = it.frameKey.dataKey.copy(
                filter = it.frameKey.dataKey.filter.copy(query = "changed"),
            )
            it.copy(frameKey = it.frameKey.copy(dataKey = changedDataKey))
        }
        assertCancelled {
            it.copy(
                viewportSignature = it.viewportSignature.copy(
                    viewportHeightPx = it.viewportSignature.viewportHeightPx + 1,
                ),
            )
        }
        assertCancelled { it.copy(currentColumnCount = it.currentColumnCount + 1) }
    }

    @Test
    fun noTargetAssetReturnsToCurrentWithoutPublishingRequest() {
        var requestCount = 0
        val controller = MediaGridMorphInteractionController { requestCount++ }
        val emptyTargetSlot = slot(
            row = 0,
            column = 0,
            startAssetId = 1L,
            endAssetId = null,
            startOrdinal = 0,
            endOrdinal = null,
            rect = Rect(0f, 0f, 100f, 100f),
        )
        val pair = plan(slots = listOf(emptyTargetSlot), anchor = null).preparedPair
        val identity = MediaGridMorphInteractionIdentity(
            sourceRevision = 1L,
            frameKey = pair.frameKey,
            currentColumnCount = 2,
            viewportSignature = pair.viewportSignature,
        )

        assertTrue(
            controller.beginPointers(
                identity,
                mapOf(MediaGridMorphDirection.IncreaseColumns to pair),
                1L,
                2L,
                Offset(0f, 50f),
                Offset(100f, 50f),
            ),
        )
        controller.updatePointers(Offset(35f, 50f), Offset(65f, 50f))
        controller.releasePointers()
        controller.advanceSettleElapsed(controller.snapshot().interactionGeneration, 180L)

        assertEquals(MediaGridMorphPhase.Failed, controller.snapshot().phase)
        assertEquals(MediaGridMorphFailureReason.TargetAnchorUnavailable, controller.snapshot().failureReason)
        assertNull(controller.snapshot().handoffRequest)
        assertEquals(0, requestCount)
    }

    @Test
    fun coordinatorChangesColumnOnceWaitsForFrameAndCompletesOnFollowingFrame() {
        val request = request()
        val coordinator = MediaGridMorphGridHandoffCoordinator()
        val first = coordinator.start(request)
        assertEquals(
            MediaGridMorphGridHandoffCommand.ChangeColumnCount(3),
            first,
        )
        assertNull(coordinator.start(request))
        assertNull(coordinator.observeFrame(frame(request.sourceFrameKey, longArrayOf(11L, 12L))))
        assertNull(
            coordinator.observeLayout(
                frame(request.sourceFrameKey, longArrayOf(11L, 12L)),
                visibleTarget = null,
                viewportWidth = 300,
                viewportHeight = 300,
            ),
        )
        assertEquals(MediaGridMorphGridHandoffPhase.WaitingForTargetFrame, coordinator.snapshot().phase)

        assertNull(
            coordinator.observeFrame(
                frame(request.expectedTargetFrameKey, longArrayOf(11L, 12L)),
            ),
        )
        assertEquals(MediaGridMorphGridHandoffPhase.PositioningTarget, coordinator.snapshot().phase)
        assertTrue(
            coordinator.observeLayout(
                frame(request.expectedTargetFrameKey, longArrayOf(11L, 12L)),
                visibleTarget = MediaGridMorphVisibleItemGeometry(
                    assetId = 11L,
                    itemIndex = 0,
                    rect = Rect(0f, 0f, 100f, 100f),
                ),
                viewportWidth = 300,
                viewportHeight = 300,
            ) is MediaGridMorphGridHandoffCommand.ScrollToItem,
        )
        assertNull(
            coordinator.observeLayout(
                frame(request.expectedTargetFrameKey, longArrayOf(11L, 12L)),
                visibleTarget = MediaGridMorphVisibleItemGeometry(
                    assetId = 11L,
                    itemIndex = 0,
                    rect = Rect(0f, 0f, 100f, 100f),
                ),
                viewportWidth = 300,
                viewportHeight = 300,
            ),
        )
        assertEquals(MediaGridMorphGridHandoffPhase.VerifyingTarget, coordinator.snapshot().phase)
        assertNull(coordinator.underlyingTargetGridDrawn())
        assertEquals(MediaGridMorphGridHandoffPhase.ReadyToComplete, coordinator.snapshot().phase)
        assertEquals(
            MediaGridMorphGridHandoffCommand.Complete(7L),
            coordinator.nextFrame(),
        )
        assertNull(coordinator.nextFrame())
        assertTrue(coordinator.consumeFinalAnchorCheckpointPermission())
        assertFalse(coordinator.consumeFinalAnchorCheckpointPermission())
    }

    @Test
    fun coordinatorUsesOrdinalFallbackAndLimitsYCorrectionToThreeAttempts() {
        val request = request().copy(
            targetAnchor = request().targetAnchor.copy(assetId = 99L, mediaOrdinal = 1),
        )
        val targetFrame = frame(request.expectedTargetFrameKey, longArrayOf(21L, 22L, 23L))
        val coordinator = MediaGridMorphGridHandoffCoordinator()
        coordinator.start(request)
        coordinator.observeFrame(targetFrame)
        assertEquals(22L, coordinator.snapshot().resolvedTarget?.assetId)
        assertEquals(1, coordinator.snapshot().resolvedTarget?.mediaOrdinal)

        assertTrue(
            coordinator.observeLayout(
                targetFrame,
                MediaGridMorphVisibleItemGeometry(22L, 1, Rect(0f, 10f, 100f, 110f)),
                300,
                300,
            ) is MediaGridMorphGridHandoffCommand.ScrollToItem,
        )
        repeat(3) { attempt ->
            assertEquals(
                MediaGridMorphGridHandoffCommand.ScrollBy(10f),
                coordinator.observeLayout(
                    targetFrame,
                    MediaGridMorphVisibleItemGeometry(22L, 1, Rect(0f, 10f, 100f, 110f)),
                    300,
                    300,
                ),
            )
            assertEquals(attempt + 1, coordinator.snapshot().correctionAttempts)
        }
        assertEquals(
            MediaGridMorphGridHandoffCommand.RollbackColumnCount(2),
            coordinator.observeLayout(
                targetFrame,
                MediaGridMorphVisibleItemGeometry(22L, 1, Rect(0f, 10f, 100f, 110f)),
                300,
                300,
            ),
        )
        assertNull(coordinator.failCurrent())
        assertEquals(MediaGridMorphGridHandoffPhase.RollingBack, coordinator.snapshot().phase)
    }

    @Test
    fun staleDataCancelsWithoutRollbackAndInvalidTargetFrameRollsBackOnce() {
        val request = request()
        val stale = MediaGridMorphGridHandoffCoordinator()
        stale.start(request)
        val staleKey = request.expectedTargetFrameKey.copy(
            dataKey = request.sourceDataKey.copy(sourceRevision = 999L),
        )
        assertEquals(
            MediaGridMorphGridHandoffCommand.Cancel(7L),
            stale.observeFrame(frame(staleKey, longArrayOf(11L))),
        )
        assertEquals(MediaGridMorphGridHandoffPhase.Cancelled, stale.snapshot().phase)
        assertEquals(MediaGridMorphGridHandoffFailureReason.StaleData, stale.snapshot().failureReason)
        assertFalse(stale.snapshot().finalAnchorCheckpointPending)

        val invalid = MediaGridMorphGridHandoffCoordinator()
        invalid.start(request)
        val unexpectedKey = request.expectedTargetFrameKey.copy(columnCount = 4)
        assertEquals(
            MediaGridMorphGridHandoffCommand.RollbackColumnCount(2),
            invalid.observeFrame(frame(unexpectedKey, longArrayOf(11L))),
        )
        assertEquals(
            MediaGridMorphGridHandoffFailureReason.UnexpectedTargetFrame,
            invalid.snapshot().failureReason,
        )
        assertNull(invalid.observeFrame(frame(unexpectedKey, longArrayOf(11L))))
    }

    @Test
    fun coordinatorSuppressesIntermediateCheckpointsAndAllowsExactlyOneAtEachTerminalPath() {
        val successRequest = request()
        val success = MediaGridMorphGridHandoffCoordinator()
        assertFalse(success.snapshot().suppressesAnchorCheckpoint)
        success.start(successRequest)
        assertTrue(success.snapshot().suppressesAnchorCheckpoint)
        success.observeFrame(frame(successRequest.expectedTargetFrameKey, longArrayOf(11L)))
        assertTrue(success.snapshot().suppressesAnchorCheckpoint)
        assertTrue(
            success.observeLayout(
                frame(successRequest.expectedTargetFrameKey, longArrayOf(11L)),
                MediaGridMorphVisibleItemGeometry(11L, 0, Rect(0f, 0f, 100f, 100f)),
                300,
                300,
            ) is MediaGridMorphGridHandoffCommand.ScrollToItem,
        )
        success.observeLayout(
            frame(successRequest.expectedTargetFrameKey, longArrayOf(11L)),
            MediaGridMorphVisibleItemGeometry(11L, 0, Rect(0f, 0f, 100f, 100f)),
            300,
            300,
        )
        assertTrue(success.snapshot().suppressesAnchorCheckpoint)
        success.underlyingTargetGridDrawn()
        assertTrue(success.snapshot().suppressesAnchorCheckpoint)
        success.nextFrame()
        assertFalse(success.snapshot().suppressesAnchorCheckpoint)
        assertTrue(success.consumeFinalAnchorCheckpointPermission())
        assertFalse(success.consumeFinalAnchorCheckpointPermission())

        val rollback = MediaGridMorphGridHandoffCoordinator()
        rollback.start(successRequest)
        rollback.failCurrent()
        assertTrue(rollback.snapshot().suppressesAnchorCheckpoint)
        rollback.observeFrame(frame(successRequest.sourceFrameKey, longArrayOf(11L)))
        rollback.observeLayout(
            frame(successRequest.sourceFrameKey, longArrayOf(11L)),
            MediaGridMorphVisibleItemGeometry(11L, 0, Rect(0f, 0f, 100f, 100f)),
            300,
            300,
        )
        assertFalse(rollback.snapshot().suppressesAnchorCheckpoint)
        assertTrue(rollback.consumeFinalAnchorCheckpointPermission())
        assertFalse(rollback.consumeFinalAnchorCheckpointPermission())

        val staleDisplay = MediaGridMorphGridHandoffCoordinator()
        staleDisplay.start(successRequest)
        staleDisplay.cancelForStaleDisplay()
        assertFalse(staleDisplay.snapshot().suppressesAnchorCheckpoint)
        assertFalse(staleDisplay.consumeFinalAnchorCheckpointPermission())
    }

    @Test
    fun coordinatorDoesNotLetAnotherGenerationOverwriteAnActiveRequest() {
        val coordinator = MediaGridMorphGridHandoffCoordinator()
        val active = request()
        coordinator.start(active)

        assertNull(
            coordinator.start(active.copy(interactionGeneration = active.interactionGeneration + 1L)),
        )
        assertEquals(active, coordinator.snapshot().request)
        assertEquals(MediaGridMorphGridHandoffPhase.WaitingForTargetFrame, coordinator.snapshot().phase)
    }

    @Test
    fun ordinalFallbackClampsToLastAssetWhenTargetOrdinalIsPastFrameEnd() {
        val base = request()
        val request = base.copy(
            targetAnchor = base.targetAnchor.copy(assetId = 999L, mediaOrdinal = 999),
        )
        val targetFrame = frame(request.expectedTargetFrameKey, longArrayOf(21L, 22L, 23L))
        val coordinator = MediaGridMorphGridHandoffCoordinator()

        coordinator.start(request)
        coordinator.observeFrame(targetFrame)

        assertEquals(23L, coordinator.snapshot().resolvedTarget?.assetId)
        assertEquals(2, coordinator.snapshot().resolvedTarget?.mediaOrdinal)
        assertEquals(2, coordinator.snapshot().resolvedTarget?.itemIndex)
    }

    private fun awaitingController(): MediaGridMorphInteractionController {
        val pair = plan(
            slots = listOf(slot(0, 0, 1L, 11L, 0, 0, Rect(0f, 0f, 100f, 100f))),
            anchor = null,
        ).preparedPair
        val identity = MediaGridMorphInteractionIdentity(
            sourceRevision = 1L,
            frameKey = pair.frameKey,
            currentColumnCount = 2,
            viewportSignature = pair.viewportSignature,
        )
        val controller = MediaGridMorphInteractionController()
        assertTrue(
            controller.beginPointers(
                identity,
                mapOf(MediaGridMorphDirection.IncreaseColumns to pair),
                1L,
                2L,
                Offset(0f, 50f),
                Offset(100f, 50f),
            ),
        )
        controller.updatePointers(Offset(35f, 50f), Offset(65f, 50f))
        controller.releasePointers()
        controller.advanceSettleElapsed(controller.snapshot().interactionGeneration, 180L)
        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, controller.snapshot().phase)
        return controller
    }

    private fun identity(
        key: MediaGridRenderKey,
        columns: Int,
        request: MediaGridMorphHandoffRequest,
    ) = MediaGridMorphInteractionIdentity(
        sourceRevision = request.sourceRevision,
        frameKey = key,
        currentColumnCount = columns,
        viewportSignature = request.plan.preparedPair.viewportSignature.copy(
            renderKey = key,
            columnCount = columns,
        ),
    )

    private fun request(): MediaGridMorphHandoffRequest {
        val plan = plan(
            slots = listOf(slot(0, 0, 1L, 11L, 0, 0, Rect(0f, 0f, 100f, 100f))),
            anchor = MediaGridMorphAnchor(
                assetId = 1L,
                slot = slot(0, 0, 1L, 11L, 0, 0, Rect(0f, 0f, 100f, 100f)),
                focalU = 0f,
                focalV = 0f,
                initialPinchCenter = Offset.Zero,
            ),
        )
        val sourceKey = plan.preparedPair.frameKey
        return MediaGridMorphHandoffRequest(
            interactionGeneration = 7L,
            sourceRevision = 1L,
            sourceDataKey = sourceKey.dataKey,
            sourceFrameKey = sourceKey,
            expectedTargetFrameKey = sourceKey.copy(columnCount = 3),
            fromColumnCount = 2,
            toColumnCount = 3,
            plan = plan,
            interactionAnchor = plan.anchor,
            targetAnchor = MediaGridMorphTargetAnchor(
                assetId = 11L,
                mediaOrdinal = 0,
                slotRow = 0,
                slotColumn = 0,
                endRect = Rect(0f, 0f, 100f, 100f),
                focalU = 0f,
                focalV = 0f,
                maintainedCanvasPosition = Offset.Zero,
                targetItemIndexHint = 0,
            ),
            finalCorrection = Offset.Zero,
            fixedFocalCenter = Offset.Zero,
            viewportWidth = 300,
            viewportHeight = 300,
        )
    }

    private fun frame(key: MediaGridRenderKey, assetIds: LongArray): MediaGridFrameData {
        val items = assetIds.mapIndexed { index, assetId ->
            MediaGridCellItem(
                key = "media_grid_item_$assetId",
                entry = entry(assetId),
                sourceIndex = index,
            )
        }
        val itemIndices = IntArray(assetIds.size) { it }
        val ordinals = IntArray(assetIds.size) { it }
        return MediaGridFrameData(
            key = key,
            items = items,
            itemByKey = items.associateBy { it.key },
            mediaCellIndices = itemIndices,
            assetIdByItemKey = items.associate { it.key to it.entry.assetId },
            ordinalIndex = MediaGridOrdinalIndex(
                assetIdByMediaOrdinal = assetIds,
                itemIndexByMediaOrdinal = itemIndices,
                mediaOrdinalByItemIndex = ordinals,
                mediaOrdinalByAssetId = assetIds.withIndex().associate { it.value to it.index },
                itemIndexByAssetId = assetIds.withIndex().associate { it.value to it.index },
            ),
        )
    }

    private fun entry(assetId: Long) = MediaGridEntry(
        entryId = assetId,
        clipId = assetId,
        assetId = assetId,
        mediaKey = "media-$assetId",
        mediaIndex = 0,
        type = "photo",
        displayUrl = null,
        downloadState = "saved",
        localPath = null,
        xCreatedAt = "2026-07-30T00:00:00Z",
        likeCount = 0L,
    )

    private fun plan(
        slots: List<MediaGridMorphSlot>,
        anchor: MediaGridMorphAnchor?,
    ): MediaGridMorphPlan {
        val dataKey = MediaGridDataKey(1L, 1L, TweetFilterState(), ClassifiedSortState())
        val frameKey = MediaGridRenderKey(dataKey, 2)
        val viewport = Rect(0f, 0f, 300f, 300f)
        val startMedia = slots.mapNotNull { slot ->
            slot.startAssetId?.let {
                MediaGridMorphMedia(
                    it,
                    slot.startMediaOrdinal ?: 0,
                    slot.startMediaOrdinal ?: 0,
                    slot.row,
                    slot.column,
                    slot.startRect,
                )
            }
        }
        val targetMedia = slots.mapNotNull { slot ->
            slot.endAssetId?.let {
                MediaGridMorphMedia(
                    it,
                    slot.endMediaOrdinal ?: 0,
                    slot.endMediaOrdinal ?: 0,
                    slot.row,
                    slot.column,
                    slot.endRect,
                )
            }
        }
        val pair = MediaGridMorphPreparedPair(
            sourceRevision = 1L,
            frameKey = frameKey,
            fromColumnCount = 2,
            toColumnCount = 3,
            viewport = viewport,
            viewportSignature = MediaGridViewportSignature(
                frameKey,
                0,
                slots.lastIndex,
                0,
                slots.lastIndex,
                300,
                300,
                150,
                2,
            ),
            startLayout = MediaGridMorphLayoutSnapshot(2, viewport, startMedia, emptyList(), 0..slots.lastIndex),
            targetLayout = MediaGridMorphLayoutSnapshot(3, viewport, targetMedia, emptyList(), 0..slots.lastIndex),
            slots = slots,
            headers = emptyList(),
            mediaOrdinalRange = 0..slots.lastIndex,
        )
        return MediaGridMorphPlan(pair, anchor)
    }

    private fun slot(
        row: Int,
        column: Int,
        startAssetId: Long?,
        endAssetId: Long?,
        startOrdinal: Int?,
        endOrdinal: Int?,
        rect: Rect,
    ) = MediaGridMorphSlot(
        row,
        column,
        rect,
        rect,
        startAssetId,
        endAssetId,
        startOrdinal,
        endOrdinal,
    )
}
