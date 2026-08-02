package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class MediaGridMorphTargetAnchor(
    val assetId: Long,
    val mediaOrdinal: Int,
    val slotRow: Int,
    val slotColumn: Int,
    val endRect: Rect,
    val focalU: Float,
    val focalV: Float,
    val maintainedCanvasPosition: Offset,
    val targetItemIndexHint: Int,
)

/**
 * Selects one target anchor from the already bounded plan. No frame-wide or
 * resident collection is consulted.
 */
internal fun selectMediaGridMorphTargetAnchor(
    plan: MediaGridMorphPlan,
    finalCorrection: Offset,
    fixedFocalCenter: Offset,
): MediaGridMorphTargetAnchor? {
    val viewportPlan = plan.viewportPlan
    if (viewportPlan != null && viewportPlan.rowPlans.isNotEmpty()) {
        val targetOrdinal = viewportPlan.targetFocalMediaOrdinal
            ?: viewportPlan.focalMediaOrdinal
            ?: return null
        val targetCell = viewportPlan.rowPlans
            .firstOrNull { it.relativeRow == 0 }
            ?.cells
            ?.firstOrNull { it.targetMediaOrdinal == targetOrdinal }
            ?: viewportPlan.rowPlans
                .firstOrNull { it.relativeRow == 0 }
                ?.cells
                ?.firstOrNull { it.endContent is MediaGridMorphSlotContent.Image }
            ?: return null
        val targetAssetId = viewportPlan.targetFocalAssetId
            ?: (targetCell.endContent as? MediaGridMorphSlotContent.Image)?.assetId
            ?: return null
        val rect = mediaGridMorphRowCellRect(viewportPlan, targetCell, 1f)
        if (rect.width <= 0f || rect.height <= 0f) return null
        val targetItemIndexHint = plan.preparedPair.targetLayout.media
            .firstOrNull { it.assetId == targetAssetId && it.mediaOrdinal == targetOrdinal }
            ?.itemIndex
            ?: targetOrdinal
        return MediaGridMorphTargetAnchor(
            assetId = targetAssetId,
            mediaOrdinal = targetOrdinal,
            slotRow = viewportPlan.targetAnchorRowIndex,
            slotColumn = targetCell.column,
            endRect = rect,
            focalU = ((fixedFocalCenter.x - finalCorrection.x - plan.viewport.left - rect.left) / rect.width)
                .coerceIn(0f, 1f),
            focalV = viewportPlan.focalV,
            maintainedCanvasPosition = fixedFocalCenter - plan.viewport.topLeft,
            targetItemIndexHint = targetItemIndexHint,
        )
    }
    val targetPointInViewport =
        fixedFocalCenter - finalCorrection + plan.viewport.topLeft
    val interactionSlot = plan.anchor?.slot
    var selected: MediaGridMorphSlot? = null
    var selectedDistance = Float.POSITIVE_INFINITY

    if (
        interactionSlot?.endContent is MediaGridMorphSlotContent.Image &&
        interactionSlot.endMediaOrdinal != null &&
        interactionSlot.endRect.width > 0f &&
        interactionSlot.endRect.height > 0f
    ) {
        selected = interactionSlot
    } else {
        for (slot in plan.slots) {
            val rect = slot.endRect
            if (
                slot.endContent !is MediaGridMorphSlotContent.Image ||
                slot.endMediaOrdinal == null ||
                rect.width <= 0f ||
                rect.height <= 0f
            ) continue
            val containsCenter = targetPointInViewport in rect
            val dx = rect.center.x - targetPointInViewport.x
            val dy = rect.center.y - targetPointInViewport.y
            val distance = dx * dx + dy * dy
            if (containsCenter || selected == null || distance < selectedDistance) {
                selected = slot
                selectedDistance = if (containsCenter) -1f else distance
                if (containsCenter) break
            }
        }
    }

    val slot = selected ?: return null
    val assetId = (slot.endContent as? MediaGridMorphSlotContent.Image)?.assetId ?: return null
    val mediaOrdinal = slot.endMediaOrdinal ?: return null
    val rect = slot.endRect
    val focalU = ((targetPointInViewport.x - rect.left) / rect.width).coerceIn(0f, 1f)
    val focalV = ((targetPointInViewport.y - rect.top) / rect.height).coerceIn(0f, 1f)
    val maintainedCanvasPosition = Offset(
        x = rect.left + rect.width * focalU - plan.viewport.left + finalCorrection.x,
        y = rect.top + rect.height * focalV - plan.viewport.top + finalCorrection.y,
    )
    val itemIndexHint = plan.preparedPair.targetLayout.media
        .firstOrNull { it.assetId == assetId && it.mediaOrdinal == mediaOrdinal }
        ?.itemIndex
        ?: mediaOrdinal
    return MediaGridMorphTargetAnchor(
        assetId = assetId,
        mediaOrdinal = mediaOrdinal,
        slotRow = slot.row,
        slotColumn = slot.column,
        endRect = rect,
        focalU = focalU,
        focalV = focalV,
        maintainedCanvasPosition = maintainedCanvasPosition,
        targetItemIndexHint = itemIndexHint,
    )
}

internal enum class MediaGridMorphGridHandoffPhase {
    Idle,
    RequestingColumnChange,
    WaitingForTargetFrame,
    PositioningTarget,
    VerifyingTarget,
    RevealingCurrent,
    RevealingTarget,
    Completed,
    RollingBack,
    Cancelled,
}

internal sealed interface MediaGridMorphGridHandoffCommand {
    data class ChangeColumnCount(val columnCount: Int) : MediaGridMorphGridHandoffCommand
    data class ScrollToItem(val itemIndex: Int, val scrollOffset: Int = 0) :
        MediaGridMorphGridHandoffCommand
    data class ScrollBy(val pixels: Float) : MediaGridMorphGridHandoffCommand
    data class RollbackColumnCount(val columnCount: Int) : MediaGridMorphGridHandoffCommand
    data class Cancel(val interactionGeneration: Long) : MediaGridMorphGridHandoffCommand
}

internal data class MediaGridMorphVisibleItemGeometry(
    val assetId: Long,
    val itemIndex: Int,
    val rect: Rect,
)

internal class MediaGridMorphVisibleRowGeometry(
    val rowIndex: Int,
    val rowTop: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val mediaOrdinals: IntArray,
    val headerKey: String? = null,
    val headerTitle: String? = null,
) {
    constructor(
        rowIndex: Int,
        rowTop: Float,
        cellWidth: Float,
        cellHeight: Float,
        mediaOrdinals: List<Int>,
        headerKey: String? = null,
        headerTitle: String? = null,
    ) : this(rowIndex, rowTop, cellWidth, cellHeight, mediaOrdinals.toIntArray(), headerKey, headerTitle)

    fun copy(
        rowIndex: Int = this.rowIndex,
        rowTop: Float = this.rowTop,
        cellWidth: Float = this.cellWidth,
        cellHeight: Float = this.cellHeight,
        mediaOrdinals: IntArray = this.mediaOrdinals.copyOf(),
        headerKey: String? = this.headerKey,
        headerTitle: String? = this.headerTitle,
    ): MediaGridMorphVisibleRowGeometry = MediaGridMorphVisibleRowGeometry(
        rowIndex, rowTop, cellWidth, cellHeight, mediaOrdinals, headerKey, headerTitle,
    )

    override fun equals(other: Any?): Boolean = other is MediaGridMorphVisibleRowGeometry &&
        rowIndex == other.rowIndex &&
        rowTop == other.rowTop &&
        cellWidth == other.cellWidth &&
        cellHeight == other.cellHeight &&
        mediaOrdinals.contentEquals(other.mediaOrdinals) &&
        headerKey == other.headerKey &&
        headerTitle == other.headerTitle

    override fun hashCode(): Int {
        var result = rowIndex
        result = 31 * result + rowTop.hashCode()
        result = 31 * result + cellWidth.hashCode()
        result = 31 * result + cellHeight.hashCode()
        result = 31 * result + mediaOrdinals.contentHashCode()
        result = 31 * result + (headerKey?.hashCode() ?: 0)
        result = 31 * result + (headerTitle?.hashCode() ?: 0)
        return result
    }
}

internal enum class MediaGridMorphGridHandoffFailureReason {
    CaptureUnavailable,
    PairUnavailable,
    IdentityMismatch,
    MissingVisibleSourceImage,
    MissingTargetImage,
    MissingHeaderText,
    RenderModelIncomplete,
    HandoffRequestMissing,
    ColumnCommandNotIssued,
    TargetFrameMismatch,
    TargetRowMismatch,
    RollbackCompleted,
    StaleData,
    UnexpectedTargetFrame,
    TargetMediaUnavailable,
    ViewportMismatch,
    TargetInvisible,
    GeometryMismatch,
    GeometryCorrectionExhausted,
    ExplicitFailure,
    RequestDisappeared,
}

internal data class MediaGridMorphResolvedTarget(
    val assetId: Long,
    val mediaOrdinal: Int,
    val itemIndex: Int,
    val focalU: Float,
    val focalV: Float,
    val desiredCanvasPosition: Offset,
    val expectedCellSize: Float,
    val targetRowIndex: Int? = null,
    val targetRowTop: Float? = null,
)

internal data class MediaGridMorphGridHandoffSnapshot(
    val phase: MediaGridMorphGridHandoffPhase = MediaGridMorphGridHandoffPhase.Idle,
    val request: MediaGridMorphHandoffRequest? = null,
    val resolvedTarget: MediaGridMorphResolvedTarget? = null,
    val correctionAttempts: Int = 0,
    val columnChangeIssued: Boolean = false,
    val rollbackColumnChangeIssued: Boolean = false,
    val targetScrollIssued: Boolean = false,
    val rollbackScrollIssued: Boolean = false,
    val lastScrollByPixels: Float? = null,
    val finalAnchorCheckpointPending: Boolean = false,
    val failureReason: MediaGridMorphGridHandoffFailureReason? = null,
) {
    val suppressesUserScroll: Boolean
        get() = phase != MediaGridMorphGridHandoffPhase.Idle &&
            phase != MediaGridMorphGridHandoffPhase.Completed &&
            phase != MediaGridMorphGridHandoffPhase.Cancelled

    val suppressesAnchorCheckpoint: Boolean
        get() = suppressesUserScroll
}

/**
 * Event-driven handoff state machine. It contains no Compose, clock, image,
 * resident store, queue, IO, Delay, or polling dependency.
 */
internal class MediaGridMorphGridHandoffCoordinator {
    private var current = MediaGridMorphGridHandoffSnapshot()

    fun snapshot(): MediaGridMorphGridHandoffSnapshot = current

    fun start(request: MediaGridMorphHandoffRequest): MediaGridMorphGridHandoffCommand? {
        if (
            current.phase != MediaGridMorphGridHandoffPhase.Idle &&
            current.phase != MediaGridMorphGridHandoffPhase.Cancelled
        ) return null
        current = MediaGridMorphGridHandoffSnapshot(
            phase = MediaGridMorphGridHandoffPhase.RequestingColumnChange,
            request = request,
        )
        current = current.copy(
            phase = MediaGridMorphGridHandoffPhase.WaitingForTargetFrame,
            columnChangeIssued = true,
        )
        return MediaGridMorphGridHandoffCommand.ChangeColumnCount(request.toColumnCount)
    }

    fun observeFrame(frame: MediaGridFrameData): MediaGridMorphGridHandoffCommand? {
        val request = current.request ?: return null
        if (frame.key.dataKey != request.sourceDataKey) {
            return cancelStale()
        }
        if (current.phase == MediaGridMorphGridHandoffPhase.RollingBack) {
            if (frame.key != request.sourceFrameKey) return null
            val sourceAnchor = request.interactionAnchor
                ?: return finishRollback()
            val itemIndex = frame.ordinalIndex.itemIndexByAssetId[sourceAnchor.assetId]
                ?: frame.ordinalIndex.itemIndexByMediaOrdinal
                    .getOrNull(
                        frame.ordinalIndex.assetIdByMediaOrdinal
                            .indices
                            .let { indices ->
                                if (indices.isEmpty()) return finishRollback()
                                sourceAnchor.slot.startMediaOrdinal
                                    ?.coerceIn(indices.first, indices.last)
                                    ?: indices.first
                            },
                    )
                ?: return finishRollback()
            current = current.copy(
                resolvedTarget = MediaGridMorphResolvedTarget(
                    assetId = frame.ordinalIndex.assetIdByMediaOrdinal[
                        frame.ordinalIndex.mediaOrdinalByItemIndex[itemIndex]
                    ],
                    mediaOrdinal = frame.ordinalIndex.mediaOrdinalByItemIndex[itemIndex],
                    itemIndex = itemIndex,
                    focalU = sourceAnchor.focalU.coerceIn(0f, 1f),
                    focalV = sourceAnchor.focalV.coerceIn(0f, 1f),
                    desiredCanvasPosition = sourceAnchor.initialPinchCenter,
                    expectedCellSize = sourceAnchor.slot.startRect.width,
                ),
                correctionAttempts = 0,
                rollbackScrollIssued = false,
            )
            return null
        }
        if (current.phase != MediaGridMorphGridHandoffPhase.WaitingForTargetFrame) return null
        if (frame.key == request.sourceFrameKey) return null
        if (frame.key != request.expectedTargetFrameKey) {
            return beginRollback(MediaGridMorphGridHandoffFailureReason.UnexpectedTargetFrame)
        }
        val targetAnchor = request.targetAnchor
        val targetOrdinal = request.targetFocalMediaOrdinal ?: targetAnchor.mediaOrdinal
        val directIndex = request.targetFocalMediaOrdinal?.let { frame.ordinalIndex.itemIndexByMediaOrdinal.getOrNull(it) }
            ?: frame.ordinalIndex.itemIndexByAssetId[targetAnchor.assetId]
        val resolvedOrdinal: Int
        val resolvedAssetId: Long
        val resolvedItemIndex: Int
        if (directIndex != null) {
            resolvedOrdinal = frame.ordinalIndex.mediaOrdinalByItemIndex[directIndex]
            resolvedAssetId = targetAnchor.assetId
            resolvedItemIndex = directIndex
        } else {
            val ordinals = frame.ordinalIndex.assetIdByMediaOrdinal.indices
            if (ordinals.isEmpty()) return beginRollback(MediaGridMorphGridHandoffFailureReason.TargetMediaUnavailable)
            resolvedOrdinal = targetOrdinal.coerceIn(ordinals.first, ordinals.last)
            resolvedAssetId = frame.ordinalIndex.assetIdByMediaOrdinal[resolvedOrdinal]
            resolvedItemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal[resolvedOrdinal]
        }
        current = current.copy(
            phase = MediaGridMorphGridHandoffPhase.PositioningTarget,
            resolvedTarget = MediaGridMorphResolvedTarget(
                assetId = resolvedAssetId,
                mediaOrdinal = resolvedOrdinal,
                itemIndex = resolvedItemIndex,
                focalU = targetAnchor.focalU,
                focalV = targetAnchor.focalV,
                desiredCanvasPosition = targetAnchor.maintainedCanvasPosition,
                expectedCellSize = request.targetCellSizePx ?: targetAnchor.endRect.width,
                targetRowIndex = request.targetAnchorRowIndex,
                targetRowTop = request.targetAnchorRowTop,
            ),
        )
        MediaGridMorphTestTrace.recordHandoffTrace(
            event = MediaGridMorphHandoffTraceEvent.ExpectedTargetFrame,
            generation = request.interactionGeneration,
            handoffPhase = current.phase,
        )
        return null
    }

    fun observeLayout(
        frame: MediaGridFrameData,
        visibleTarget: MediaGridMorphVisibleItemGeometry?,
        viewportWidth: Int,
        viewportHeight: Int,
        visibleTargetRow: MediaGridMorphVisibleRowGeometry? = null,
    ): MediaGridMorphGridHandoffCommand? {
        if (
            current.phase != MediaGridMorphGridHandoffPhase.PositioningTarget &&
            current.phase != MediaGridMorphGridHandoffPhase.RollingBack
        ) return null
        val request = current.request ?: return null
        if (frame.key.dataKey != request.sourceDataKey) return cancelStale()
        val rollingBack = current.phase == MediaGridMorphGridHandoffPhase.RollingBack
        val expectedKey = if (rollingBack) request.sourceFrameKey else request.expectedTargetFrameKey
        if (frame.key != expectedKey) {
            return if (rollingBack) null else beginRollback(MediaGridMorphGridHandoffFailureReason.UnexpectedTargetFrame)
        }
        if (viewportWidth != request.viewportWidth || viewportHeight != request.viewportHeight) {
            return if (rollingBack) {
                finishRollback()
            } else {
                beginRollback(MediaGridMorphGridHandoffFailureReason.ViewportMismatch)
            }
        }
        val target = current.resolvedTarget ?: return null
        if (!rollingBack && request.targetRowMediaOrdinals.isNotEmpty()) {
            val row = visibleTargetRow
            if (row == null) {
                if (current.targetScrollIssued) {
                    return beginRollback(MediaGridMorphGridHandoffFailureReason.TargetInvisible)
                }
                current = current.copy(targetScrollIssued = true, lastScrollByPixels = null)
                return MediaGridMorphGridHandoffCommand.ScrollToItem(
                    itemIndex = target.itemIndex,
                    scrollOffset = mediaGridMorphTargetScrollOffset(target.targetRowTop),
                )
            }
            if (!row.mediaOrdinals.contentEquals(request.targetRowMediaOrdinalArray)) {
                return beginRollback(MediaGridMorphGridHandoffFailureReason.TargetRowMismatch)
            }
            if (request.targetHeaderKey != null && row.headerKey != request.targetHeaderKey) {
                return beginRollback(MediaGridMorphGridHandoffFailureReason.GeometryMismatch)
            }
            if (request.targetHeaderTitle != null && row.headerTitle != request.targetHeaderTitle) {
                return beginRollback(MediaGridMorphGridHandoffFailureReason.GeometryMismatch)
            }
            val expectedRowTop = request.targetAnchorRowTop ?: row.rowTop
            val expectedSize = request.targetCellSizePx ?: row.cellWidth
            val rowSizeMatches = abs(row.cellWidth - expectedSize) <= GeometryTolerancePx &&
                abs(row.cellHeight - expectedSize) <= GeometryTolerancePx
            if (!rowSizeMatches) {
                return beginRollback(MediaGridMorphGridHandoffFailureReason.GeometryMismatch)
            }
            val rowDelta = row.rowTop - expectedRowTop
            if (abs(rowDelta) > GeometryTolerancePx) {
                if (current.correctionAttempts >= MaxCorrectionAttempts ||
                    current.lastScrollByPixels?.let { abs(it - rowDelta) <= GeometryTolerancePx / 2f } == true
                ) {
                    return beginRollback(MediaGridMorphGridHandoffFailureReason.GeometryCorrectionExhausted)
                }
                current = current.copy(
                    targetScrollIssued = true,
                    correctionAttempts = current.correctionAttempts + 1,
                    lastScrollByPixels = rowDelta,
                )
                return MediaGridMorphGridHandoffCommand.ScrollBy(rowDelta)
            }
            current = current.copy(
                phase = MediaGridMorphGridHandoffPhase.VerifyingTarget,
                lastScrollByPixels = null,
            )
            return null
        }

        if (rollingBack) {
            if (visibleTarget == null || visibleTarget.assetId != target.assetId) {
                if (current.rollbackScrollIssued) return finishRollback()
                current = current.copy(rollbackScrollIssued = true)
                return MediaGridMorphGridHandoffCommand.ScrollToItem(
                    itemIndex = target.itemIndex,
                    scrollOffset = mediaGridMorphTargetScrollOffset(target.targetRowTop),
                )
            }
        } else if (visibleTarget == null || visibleTarget.assetId != target.assetId) {
            if (current.targetScrollIssued) {
                return beginRollback(MediaGridMorphGridHandoffFailureReason.TargetInvisible)
            }
            current = current.copy(targetScrollIssued = true, lastScrollByPixels = null)
            return MediaGridMorphGridHandoffCommand.ScrollToItem(
                itemIndex = target.itemIndex,
                scrollOffset = mediaGridMorphTargetScrollOffset(target.targetRowTop),
            )
        }

        val rect = visibleTarget.rect
        val square = abs(rect.width - rect.height) <= GeometryTolerancePx
        val expectedSize = abs(rect.width - target.expectedCellSize) <= GeometryTolerancePx &&
            abs(rect.height - target.expectedCellSize) <= GeometryTolerancePx
        val actualFocal = Offset(
            rect.left + rect.width * target.focalU,
            rect.top + rect.height * target.focalV,
        )
        val deltaX = actualFocal.x - target.desiredCanvasPosition.x
        val deltaY = actualFocal.y - target.desiredCanvasPosition.y
        if (!square || !expectedSize || abs(deltaX) > GeometryTolerancePx) {
            return if (rollingBack) {
                finishRollback()
            } else {
                beginRollback(MediaGridMorphGridHandoffFailureReason.GeometryMismatch)
            }
        }
        if (abs(deltaY) > GeometryTolerancePx) {
            if (current.correctionAttempts >= MaxCorrectionAttempts ||
                current.lastScrollByPixels?.let { abs(it - deltaY) <= GeometryTolerancePx / 2f } == true
            ) {
                return if (rollingBack) {
                    finishRollback()
                } else {
                    beginRollback(MediaGridMorphGridHandoffFailureReason.GeometryCorrectionExhausted)
                }
            }
            current = current.copy(
                targetScrollIssued = true,
                correctionAttempts = current.correctionAttempts + 1,
                lastScrollByPixels = deltaY,
            )
            return MediaGridMorphGridHandoffCommand.ScrollBy(deltaY)
        }
        if (rollingBack) {
            current = current.copy(phase = MediaGridMorphGridHandoffPhase.RevealingCurrent)
            MediaGridMorphTestTrace.recordHandoffTrace(
                event = MediaGridMorphHandoffTraceEvent.RevealCurrent,
                generation = request.interactionGeneration,
                handoffPhase = current.phase,
            )
            return null
        }
        current = current.copy(
            phase = MediaGridMorphGridHandoffPhase.VerifyingTarget,
            lastScrollByPixels = null,
        )
        return null
    }

    fun beginTargetReveal(generation: Long): Boolean {
        val request = current.request ?: return false
        if (current.phase != MediaGridMorphGridHandoffPhase.VerifyingTarget || request.interactionGeneration != generation) return false
        current = current.copy(
            phase = MediaGridMorphGridHandoffPhase.RevealingTarget,
        )
        MediaGridMorphTestTrace.recordHandoffTrace(
            event = MediaGridMorphHandoffTraceEvent.RevealTarget,
            generation = generation,
            handoffPhase = current.phase,
        )
        return true
    }

    fun completeAfterReveal(generation: Long, target: Boolean): Boolean {
        val request = current.request ?: return false
        val expected = if (target) MediaGridMorphGridHandoffPhase.RevealingTarget else MediaGridMorphGridHandoffPhase.RevealingCurrent
        if (current.phase != expected || request.interactionGeneration != generation) return false
        current = current.copy(
            phase = if (target) MediaGridMorphGridHandoffPhase.Completed else MediaGridMorphGridHandoffPhase.Cancelled,
            finalAnchorCheckpointPending = true,
        )
        return true
    }

    fun failCurrent(): MediaGridMorphGridHandoffCommand? = beginRollback(MediaGridMorphGridHandoffFailureReason.ExplicitFailure)

    fun cancelForStaleDisplay() {
        if (
            current.phase == MediaGridMorphGridHandoffPhase.Idle ||
            current.phase == MediaGridMorphGridHandoffPhase.Cancelled ||
            current.phase == MediaGridMorphGridHandoffPhase.Completed
        ) return
        current = current.copy(
            phase = MediaGridMorphGridHandoffPhase.Cancelled,
            finalAnchorCheckpointPending = false,
            failureReason = MediaGridMorphGridHandoffFailureReason.RequestDisappeared,
        )
    }

    fun consumeFinalAnchorCheckpointPermission(): Boolean {
        if (!current.finalAnchorCheckpointPending) return false
        current = current.copy(finalAnchorCheckpointPending = false)
        return true
    }

    private fun beginRollback(reason: MediaGridMorphGridHandoffFailureReason): MediaGridMorphGridHandoffCommand? {
        val request = current.request ?: return null
        if (!current.columnChangeIssued) return finishRollback()
        if (current.rollbackColumnChangeIssued) return null
        current = current.copy(
            phase = MediaGridMorphGridHandoffPhase.RollingBack,
            rollbackColumnChangeIssued = true,
            resolvedTarget = null,
            correctionAttempts = 0,
            lastScrollByPixels = null,
            failureReason = reason,
        )
        return MediaGridMorphGridHandoffCommand.RollbackColumnCount(request.fromColumnCount)
    }

    private fun finishRollback(): MediaGridMorphGridHandoffCommand? {
        val request = current.request ?: return null
        current = current.copy(
            phase = MediaGridMorphGridHandoffPhase.Cancelled,
            finalAnchorCheckpointPending = true,
            lastScrollByPixels = null,
        )
        return null
    }

    private fun cancelStale(): MediaGridMorphGridHandoffCommand? {
        val request = current.request ?: return null
        current = current.copy(
            phase = MediaGridMorphGridHandoffPhase.Cancelled,
            finalAnchorCheckpointPending = false,
            failureReason = MediaGridMorphGridHandoffFailureReason.StaleData,
        )
        return MediaGridMorphGridHandoffCommand.Cancel(request.interactionGeneration)
    }

    private companion object {
        const val GeometryTolerancePx = 1f
        const val MaxCorrectionAttempts = 2
    }
}

internal fun mediaGridMorphTargetScrollOffset(targetAnchorRowTop: Float?): Int =
    targetAnchorRowTop?.takeIf(Float::isFinite)?.roundToInt() ?: 0
