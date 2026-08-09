package com.lyco256.llm

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

internal data class MediaGridMorphInteractionIdentity(
    val sourceRevision: Long,
    val frameKey: MediaGridRenderKey,
    val currentColumnCount: Int,
    val viewportSignature: MediaGridViewportSignature,
)

internal data class MediaGridMorphHandoffRequest(
    val interactionGeneration: Long,
    val sourceRevision: Long,
    val sourceDataKey: MediaGridDataKey,
    val sourceFrameKey: MediaGridRenderKey,
    val expectedTargetFrameKey: MediaGridRenderKey,
    val fromColumnCount: Int,
    val toColumnCount: Int,
    val plan: MediaGridMorphPlan,
    val interactionAnchor: MediaGridMorphAnchor?,
    val targetAnchor: MediaGridMorphTargetAnchor,
    val finalCorrection: Offset,
    val fixedFocalCenter: Offset,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val sourceFocalMediaOrdinal: Int? = null,
    val targetFocalMediaOrdinal: Int? = null,
    val targetAnchorRowIndex: Int? = null,
    val targetAnchorRowTop: Float? = null,
    val targetRowMediaOrdinals: List<Int> = emptyList(),
    val targetFocalItemIndex: Int? = null,
    val targetRowFirstItemIndex: Int? = null,
    val targetExactRowId: Int? = null,
    val targetDesiredRowTopViewportLocal: Float? = null,
    val targetUnclampedRowTopViewportLocal: Float? = null,
    val targetExactMaxScroll: Float? = null,
    val exactTargetLayoutIndex: MediaGridMorphExactTargetLayoutIndex? = null,
    val targetHeaderKey: String? = null,
    val targetCellSizePx: Float? = null,
    val targetHeaderTitle: String? = null,
    val frozenViewportPlan: MediaGridMorphViewportPlan? = plan.viewportPlan,
    val sourceViewportAnchor: MediaGridMorphSourceViewportAnchor? = null,
)

internal enum class MediaGridMorphFailureReason {
    CaptureUnavailable,
    PairUnavailable,
    TargetAnchorUnavailable,
    IdentityMismatch,
    MissingVisibleSourceImage,
    MissingTargetImage,
    MissingHeaderText,
    SourceViewportMismatch,
    RenderModelIncomplete,
    HandoffRequestMissing,
    ColumnCommandNotIssued,
    TargetFrameMismatch,
    TargetRowMismatch,
    RollbackCompleted,
}

internal data class MediaGridMorphInteractionSnapshot(
    val phase: MediaGridMorphPhase,
    val direction: MediaGridMorphDirection?,
    val plan: MediaGridMorphPlan?,
    val progress: Float,
    val correction: Offset,
    val currentPinchCenter: Offset?,
    val fromColumnCount: Int,
    val toColumnCount: Int,
    val sourceRevision: Long?,
    val frameKey: MediaGridRenderKey?,
    val handoffRequest: MediaGridMorphHandoffRequest?,
    val interactionGeneration: Long,
    val failureReason: MediaGridMorphFailureReason? = null,
    val drawMode: MediaGridMorphDrawMode = MediaGridMorphDrawMode.Normal,
    val claimBundle: MediaGridMorphClaimBundle? = null,
    val activeRenderModel: MediaGridMorphRowRenderModel? = null,
    val protectedAssetIds: LongArray = LongArray(0),
)

internal enum class MediaGridMorphGestureMode {
    Disabled,
    Test,
    Production,
}

internal enum class MediaGridMorphGestureArbitrationState {
    OnePointerOrIdle,
    TwoPointerCandidate,
    MorphClaimed,
    FallbackClaimed,
    ReleasedOrCancelled,
}

internal data class MediaGridMorphCandidate(
    val firstPointerId: Long,
    val secondPointerId: Long,
    val firstInitialPosition: Offset,
    val secondInitialPosition: Offset,
    val initialDistance: Float,
    val initialCentroid: Offset,
    val generation: Long,
    val claimBundle: MediaGridMorphClaimBundle? = null,
    val claimFirstPosition: Offset? = null,
    val claimSecondPosition: Offset? = null,
    val claimDirection: MediaGridMorphDirection? = null,
)

internal fun mediaGridMorphClaimBundleMatchesIdentity(
    bundle: MediaGridMorphClaimBundle,
    latestIdentity: MediaGridMorphInteractionIdentity?,
): Boolean = latestIdentity != null && latestIdentity == bundle.identity

internal fun mediaGridMorphFailureReasonForClaimReadiness(
    reason: MediaGridMorphClaimReadinessReason,
): MediaGridMorphFailureReason = when (reason) {
    MediaGridMorphClaimReadinessReason.CaptureUnavailable -> MediaGridMorphFailureReason.CaptureUnavailable
    MediaGridMorphClaimReadinessReason.DirectionUnavailable -> MediaGridMorphFailureReason.PairUnavailable
    MediaGridMorphClaimReadinessReason.StaleIdentity -> MediaGridMorphFailureReason.IdentityMismatch
    MediaGridMorphClaimReadinessReason.SourceViewportMismatch -> MediaGridMorphFailureReason.SourceViewportMismatch
    MediaGridMorphClaimReadinessReason.MissingSourceImage -> MediaGridMorphFailureReason.MissingVisibleSourceImage
    MediaGridMorphClaimReadinessReason.MissingTargetImage -> MediaGridMorphFailureReason.MissingTargetImage
    MediaGridMorphClaimReadinessReason.MissingHeaderText -> MediaGridMorphFailureReason.MissingHeaderText
    MediaGridMorphClaimReadinessReason.GeometryIncomplete -> MediaGridMorphFailureReason.RenderModelIncomplete
    MediaGridMorphClaimReadinessReason.PreparedIndexChanged -> MediaGridMorphFailureReason.IdentityMismatch
}

internal fun mediaGridMorphShouldRelease(
    wasBothPressed: Boolean,
    bothPressed: Boolean,
    firstChangedToUp: Boolean,
    secondChangedToUp: Boolean,
    eventIsRelease: Boolean,
    allPointersUp: Boolean,
    anyChangedToUp: Boolean,
): Boolean = wasBothPressed && !bothPressed && (
    firstChangedToUp ||
        secondChangedToUp ||
        (eventIsRelease && allPointersUp && anyChangedToUp)
    )

internal fun mediaGridMorphCandidateDirection(
    initialDistance: Float,
    currentDistance: Float,
    touchSlop: Float,
): MediaGridMorphDirection? {
    val scale = mediaGridMorphScale(initialDistance, currentDistance) ?: return null
    val direction = mediaGridMorphDirectionForScale(scale) ?: return null
    val spanChange = abs(currentDistance - initialDistance)
    if (!spanChange.isFinite() || !touchSlop.isFinite() || touchSlop < 0f) return null
    if (spanChange < touchSlop * 0.35f) return null
    return direction
}

internal fun mediaGridMorphIsParallelTranslation(
    candidate: MediaGridMorphCandidate,
    firstPosition: Offset,
    secondPosition: Offset,
    touchSlop: Float,
): Boolean {
    val firstDelta = firstPosition - candidate.firstInitialPosition
    val secondDelta = secondPosition - candidate.secondInitialPosition
    val firstDistance = hypot(firstDelta.x, firstDelta.y)
    val secondDistance = hypot(secondDelta.x, secondDelta.y)
    if (!firstDistance.isFinite() || !secondDistance.isFinite()) return false
    if (minOf(firstDistance, secondDistance) < touchSlop * 0.35f) return false
    val deltaDifference = hypot(
        firstDelta.x - secondDelta.x,
        firstDelta.y - secondDelta.y,
    )
    return deltaDifference <= maxOf(touchSlop * 0.25f, maxOf(firstDistance, secondDistance) * 0.1f)
}

internal fun mediaGridMorphScale(initialDistance: Float, currentDistance: Float): Float? {
    if (
        !initialDistance.isFinite() ||
        !currentDistance.isFinite() ||
        initialDistance <= 0f ||
        currentDistance <= 0f
    ) return null
    return initialDistance / currentDistance.coerceAtLeast(0.001f)
}

internal fun mediaGridMorphShouldConsumePointer(
    accepted: Boolean,
    pointerId: Long,
    firstTrackedPointerId: Long,
    secondTrackedPointerId: Long,
): Boolean = accepted &&
    (pointerId == firstTrackedPointerId || pointerId == secondTrackedPointerId)

internal fun mediaGridMorphFocalCorrection(
    plan: MediaGridMorphPlan,
    progress: Float,
    fixedInitialPinchCenter: Offset,
): Offset {
    if (plan.viewportPlan != null) return Offset.Zero
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return Offset.Zero
    val anchor = plan.anchor ?: return Offset.Zero
    val rect = mediaGridMorphRect(anchor.slot, p)
    val focalInViewport = Offset(
        x = rect.left + rect.width * anchor.focalU,
        y = rect.top + rect.height * anchor.focalV,
    )
    val focalInCanvas = focalInViewport - plan.viewport.topLeft
    return fixedInitialPinchCenter - focalInCanvas
}

internal class MediaGridMorphInteractionController(
    private val onHandoffRequest: (MediaGridMorphHandoffRequest) -> Unit = {},
) {
    private var onProtectAssets: (LongArray) -> Unit = {}
    private var onReleaseAssets: (LongArray) -> Unit = {}
    private var onCarryoverAssets: (LongArray) -> Unit = {}
    private data class Gesture(
        val identity: MediaGridMorphInteractionIdentity,
        val pairs: Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
        val claimBundle: MediaGridMorphClaimBundle?,
        val firstPointerId: Long,
        val secondPointerId: Long,
        val initialDistance: Float,
        val initialCenter: Offset,
        val generation: Long,
        var lockedDirection: MediaGridMorphDirection? = null,
    )

    private data class Settle(
        val generation: Long,
        val startProgress: Float,
        val startCorrection: Offset,
        val fixedPinchCenter: Offset,
        val toTarget: Boolean,
        var firstFrameNanos: Long? = null,
    )

    private var nextGeneration = 0L
    private var gesture: Gesture? = null
    private var settle: Settle? = null
    private var currentIdentity: MediaGridMorphInteractionIdentity? = null
    private var protectedAssetIds: LongArray? = null
    private var currentSnapshot = MediaGridMorphInteractionSnapshot(
        phase = MediaGridMorphPhase.Idle,
        direction = null,
        plan = null,
        progress = 0f,
        correction = Offset.Zero,
        currentPinchCenter = null,
        fromColumnCount = 0,
        toColumnCount = 0,
        sourceRevision = null,
        frameKey = null,
        handoffRequest = null,
        interactionGeneration = 0L,
    )

    private val _snapshotState = mutableStateOf(currentSnapshot)
    private val _activePlan: State<MediaGridMorphPlan?> = derivedStateOf { _snapshotState.value.plan }
    private val _progress: State<Float> = derivedStateOf { _snapshotState.value.progress }
    private val _correction: State<Offset> = derivedStateOf { _snapshotState.value.correction }
    private val _handoffRequest: State<MediaGridMorphHandoffRequest?> = derivedStateOf { _snapshotState.value.handoffRequest }
    private val _interactionLocked: State<Boolean> = derivedStateOf { _snapshotState.value.plan != null && _snapshotState.value.phase != MediaGridMorphPhase.Idle }
    private val _drawMode: State<MediaGridMorphDrawMode> = derivedStateOf { _snapshotState.value.drawMode }
    internal val settleSignal: MutableState<Long> = mutableLongStateOf(0L)
    val activePlan: State<MediaGridMorphPlan?> get() = _activePlan
    val progress: State<Float> get() = _progress
    val correction: State<Offset> get() = _correction
    val handoffRequest: State<MediaGridMorphHandoffRequest?> get() = _handoffRequest
    val interactionLocked: State<Boolean> get() = _interactionLocked
    val drawMode: State<MediaGridMorphDrawMode> get() = _drawMode

    fun snapshot(): MediaGridMorphInteractionSnapshot = currentSnapshot

    val snapshotState: State<MediaGridMorphInteractionSnapshot> get() = _snapshotState

    fun setProtectionCallbacks(
        onProtect: (LongArray) -> Unit,
        onRelease: (LongArray) -> Unit,
        onCarryover: (LongArray) -> Unit = onRelease,
    ) {
        check(protectedAssetIds == null) { "Protection callbacks cannot change during an active Morph" }
        onProtectAssets = onProtect
        onReleaseAssets = onRelease
        onCarryoverAssets = onCarryover
    }

    fun recordFailure(reason: MediaGridMorphFailureReason) {
        if (currentSnapshot.phase == MediaGridMorphPhase.Idle) {
            currentSnapshot = currentSnapshot.copy(failureReason = reason)
            _snapshotState.value = currentSnapshot
        }
    }

    fun beginPointers(
        identity: MediaGridMorphInteractionIdentity,
        preparedPairsSnapshot: Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
        firstPointerId: Long,
        secondPointerId: Long,
        firstPosition: Offset,
        secondPosition: Offset,
    ): Boolean {
        val initialDistance = pointerDistance(firstPosition, secondPosition)
        if (!initialDistance.isFinite() || initialDistance <= 0f) return false
        val validPairs = preparedPairsSnapshot.filterValues { it.matchesIdentity(identity) }
        if (validPairs.isEmpty()) return false
        val generation = ++nextGeneration
        val center = midpoint(firstPosition, secondPosition)
        currentIdentity = identity
        gesture = Gesture(
            identity = identity,
            pairs = validPairs,
            claimBundle = null,
            firstPointerId = firstPointerId,
            secondPointerId = secondPointerId,
            initialDistance = initialDistance,
            initialCenter = center,
            generation = generation,
            lockedDirection = null,
        )
        settle = null
        publish(
            phase = MediaGridMorphPhase.Tracking,
            direction = null,
            plan = null,
            progress = 0f,
            correction = Offset.Zero,
            center = center,
            identity = identity,
            toColumnCount = identity.currentColumnCount,
            handoffRequest = null,
            generation = generation,
            drawMode = MediaGridMorphDrawMode.Normal,
        )
        return true
    }

    /** Atomically claims a prebuilt bundle on the first direction event. */
    fun claimPointers(
        bundle: MediaGridMorphClaimBundle,
        currentFirstPosition: Offset,
        currentSecondPosition: Offset,
    ): Boolean {
        if (!bundle.initialDistance.isFinite() || bundle.initialDistance <= 0f) return false
        val currentDistance = pointerDistance(currentFirstPosition, currentSecondPosition)
        val scale = mediaGridMorphScale(bundle.initialDistance, currentDistance) ?: return false
        val direction = mediaGridMorphDirectionForScale(scale) ?: return false
        val active = bundle.directions[direction] ?: return false
        if (!active.isComplete) return false
        val generation = maxOf(++nextGeneration, bundle.generation).also { nextGeneration = it }
        currentIdentity = bundle.identity
        gesture = Gesture(
            identity = bundle.identity,
            pairs = mapOf(direction to active.plan.preparedPair),
            claimBundle = bundle,
            firstPointerId = bundle.firstPointerId,
            secondPointerId = bundle.secondPointerId,
            initialDistance = bundle.initialDistance,
            initialCenter = bundle.fixedInitialCenter,
            generation = generation,
            lockedDirection = direction,
        )
        settle = null
        if (protectedAssetIds == null) {
            val union = bundle.protectedAssetUnion.copyOf()
            onProtectAssets(union)
            protectedAssetIds = union
        }
        val progress = mediaGridMorphProgressForPlan(
            plan = active.plan,
            initialDistance = bundle.initialDistance,
            currentDistance = currentDistance,
            scale = scale,
            direction = direction,
        )
        publish(
            phase = MediaGridMorphPhase.Tracking,
            direction = direction,
            plan = active.plan,
            progress = progress,
            correction = mediaGridMorphFocalCorrection(active.plan, progress, bundle.fixedInitialCenter),
            center = midpoint(currentFirstPosition, currentSecondPosition),
            identity = bundle.identity,
            toColumnCount = active.targetColumnCount,
            handoffRequest = null,
            generation = generation,
            drawMode = MediaGridMorphDrawMode.Morph,
            claimBundle = bundle,
            renderModel = active.renderModel,
        )
        return true
    }

    fun trackedPointerIds(): Pair<Long, Long>? =
        gesture?.let { it.firstPointerId to it.secondPointerId }

    fun updatePointers(firstPosition: Offset, secondPosition: Offset) {
        val activeGesture = gesture ?: return
        if (currentSnapshot.phase != MediaGridMorphPhase.Tracking) return
        val currentDistance = pointerDistance(firstPosition, secondPosition)
        val scale = mediaGridMorphScale(activeGesture.initialDistance, currentDistance) ?: return
        val center = midpoint(firstPosition, secondPosition)
        val nextDirection = mediaGridMorphDirectionForScale(scale)
        var plan = currentSnapshot.plan
        var renderModel = currentSnapshot.activeRenderModel
        if (activeGesture.lockedDirection == null && nextDirection != null) {
            val claimDirection = activeGesture.claimBundle?.directions?.get(nextDirection)
                ?.takeIf { it.isComplete }
            val pair = activeGesture.pairs[nextDirection]
            when {
                claimDirection != null -> {
                    activeGesture.lockedDirection = nextDirection
                    plan = claimDirection.plan
                    renderModel = claimDirection.renderModel
                }
                activeGesture.claimBundle == null && pair != null -> {
                    activeGesture.lockedDirection = nextDirection
                    plan = if (pair.viewportPlanTemplate != null) {
                        MediaGridMorphPlan.selectRowReflow(pair, activeGesture.initialCenter)
                    } else {
                        MediaGridMorphPlan.select(pair, activeGesture.initialCenter)
                    }
                    renderModel = null
                }
            }
        }
        val direction = activeGesture.lockedDirection
        val nextProgress = if (direction == null || plan == null) {
            0f
        } else {
            if (plan.viewportPlan != null) {
                mediaGridMorphProgressForDistance(
                    initialDistance = activeGesture.initialDistance,
                    currentDistance = currentDistance,
                    fromColumnCount = plan.fromColumnCount,
                    toColumnCount = plan.toColumnCount,
                )
            } else {
                mediaGridMorphProgressForScale(scale, direction)
            }
        }
        val nextCorrection = when {
            plan == null || plan.viewportPlan != null -> Offset.Zero
            else -> mediaGridMorphFocalCorrection(plan, nextProgress, activeGesture.initialCenter)
        }
        publish(
            phase = MediaGridMorphPhase.Tracking,
            direction = direction,
            plan = plan,
            progress = nextProgress,
            correction = nextCorrection,
            center = center,
            identity = activeGesture.identity,
            toColumnCount = plan?.toColumnCount ?: activeGesture.identity.currentColumnCount,
            handoffRequest = null,
            generation = activeGesture.generation,
            drawMode = if (
                activeGesture.claimBundle != null &&
                    direction != null &&
                    plan != null &&
                    renderModel != null
            ) MediaGridMorphDrawMode.Morph else MediaGridMorphDrawMode.Normal,
            claimBundle = activeGesture.claimBundle,
            renderModel = renderModel,
        )
    }

    fun releasePointers() {
        val activeGesture = gesture ?: return
        if (currentSnapshot.phase != MediaGridMorphPhase.Tracking) return
        val plan = currentSnapshot.plan
        val center = activeGesture.initialCenter
        gesture = null
        if (plan == null) {
            resetToIdle(activeGesture.identity.currentColumnCount, activeGesture.generation)
            return
        }
        val toTarget = currentSnapshot.progress >= MediaGridMorphDefaults.ReleaseThreshold
        settle = Settle(
            generation = activeGesture.generation,
            startProgress = currentSnapshot.progress,
            startCorrection = currentSnapshot.correction,
            fixedPinchCenter = center,
            toTarget = toTarget,
        )
        publish(
            phase = if (toTarget) MediaGridMorphPhase.SettlingToTarget else MediaGridMorphPhase.SettlingToCurrent,
            direction = currentSnapshot.direction,
            plan = plan,
            progress = currentSnapshot.progress,
            correction = currentSnapshot.correction,
            center = center,
            identity = activeGesture.identity,
            toColumnCount = plan.toColumnCount,
            handoffRequest = null,
            generation = activeGesture.generation,
            drawMode = if (activeGesture.claimBundle != null) MediaGridMorphDrawMode.Morph else null,
            claimBundle = activeGesture.claimBundle,
            renderModel = currentSnapshot.activeRenderModel,
        )
        settleSignal.value = activeGesture.generation
    }

    fun cancelPointers() {
        val identity = currentIdentity
        val generation = currentSnapshot.interactionGeneration
        gesture = null
        settle = null
        resetToIdle(identity?.currentColumnCount ?: currentSnapshot.fromColumnCount, generation)
    }

    fun updateIdentity(identity: MediaGridMorphInteractionIdentity) {
        val snapshot = currentSnapshot
        if (snapshot.phase == MediaGridMorphPhase.Idle) {
            currentIdentity = identity
            return
        }
        if (
            snapshot.phase == MediaGridMorphPhase.AwaitingGridHandoff ||
            snapshot.phase == MediaGridMorphPhase.RevealingTarget
        ) {
            val request = snapshot.handoffRequest ?: run {
                currentIdentity = identity
                resetToIdle(identity.currentColumnCount, snapshot.interactionGeneration)
                return
            }
            val sameData =
                identity.sourceRevision == request.sourceRevision &&
                    identity.frameKey.dataKey == request.sourceDataKey
            val sameViewport =
                identity.viewportSignature.viewportWidthPx == request.viewportWidth &&
                    identity.viewportSignature.viewportHeightPx == request.viewportHeight
            val sourceIdentity =
                identity.currentColumnCount in setOf(request.fromColumnCount, request.toColumnCount) &&
                    identity.frameKey == request.sourceFrameKey
            val expectedTargetIdentity =
                identity.currentColumnCount == request.toColumnCount &&
                    identity.frameKey == request.expectedTargetFrameKey
            if (sameData && sameViewport && (sourceIdentity || expectedTargetIdentity)) {
                if (expectedTargetIdentity || snapshot.phase == MediaGridMorphPhase.RevealingTarget) currentIdentity = identity
                return
            }
            currentIdentity = identity
            gesture = null
            settle = null
            publishFailure(
                identity.currentColumnCount,
                snapshot.interactionGeneration,
                MediaGridMorphFailureReason.IdentityMismatch,
            )
            return
        }
        if (snapshot.phase == MediaGridMorphPhase.RevealingCurrent) {
            val sameSource =
                identity.sourceRevision == snapshot.sourceRevision &&
                    identity.frameKey == snapshot.frameKey &&
                    identity.currentColumnCount == snapshot.fromColumnCount
            if (sameSource) {
                currentIdentity = identity
                return
            }
        }
        currentIdentity = identity
        if (
            snapshot.sourceRevision != identity.sourceRevision ||
            snapshot.frameKey != identity.frameKey ||
            snapshot.fromColumnCount != identity.currentColumnCount
        ) {
            // The plan is intentionally frozen from the claim-time grid
            // snapshot. Stopping an in-flight scroll may change visible item
            // offsets/signatures without changing the source frame; that is
            // not a reason to discard the captured Morph geometry.
            gesture = null
            settle = null
            publishFailure(
                identity.currentColumnCount,
                snapshot.interactionGeneration,
                MediaGridMorphFailureReason.IdentityMismatch,
            )
        }
    }

    fun isSettling(generation: Long): Boolean =
        settle?.generation == generation &&
            (currentSnapshot.phase == MediaGridMorphPhase.SettlingToCurrent ||
                currentSnapshot.phase == MediaGridMorphPhase.SettlingToTarget)

    fun advanceSettleFrame(frameNanos: Long) {
        val activeSettle = settle ?: return
        val first = activeSettle.firstFrameNanos ?: frameNanos.also {
            activeSettle.firstFrameNanos = it
        }
        val elapsedMillis = ((frameNanos - first).coerceAtLeast(0L) / 1_000_000L)
        advanceSettleElapsed(activeSettle.generation, elapsedMillis)
    }

    fun advanceSettleElapsed(generation: Long, elapsedMillis: Long) {
        val activeSettle = settle ?: return
        if (activeSettle.generation != generation || currentSnapshot.interactionGeneration != generation) return
        val plan = currentSnapshot.plan ?: return
        val duration = MediaGridMorphDefaults.SettleDurationMillis.coerceAtLeast(1L)
        val fraction = (elapsedMillis.toFloat() / duration).coerceIn(0f, 1f)
        val targetProgress = if (activeSettle.toTarget) 1f else 0f
        val nextProgress = lerpValue(activeSettle.startProgress, targetProgress, fraction)
        val nextCorrection = if (activeSettle.toTarget) {
            mediaGridMorphFocalCorrection(plan, nextProgress, activeSettle.fixedPinchCenter)
        } else {
            lerpOffset(activeSettle.startCorrection, Offset.Zero, fraction)
        }
        if (fraction < 1f) {
            publish(
                phase = currentSnapshot.phase,
                direction = currentSnapshot.direction,
                plan = plan,
                progress = nextProgress,
                correction = nextCorrection,
                center = activeSettle.fixedPinchCenter,
                identity = currentIdentity ?: return,
                toColumnCount = plan.toColumnCount,
                handoffRequest = null,
                generation = generation,
            )
            return
        }
        settle = null
        if (!activeSettle.toTarget) {
            publish(
                phase = MediaGridMorphPhase.RevealingCurrent,
                direction = currentSnapshot.direction,
                plan = plan,
                progress = 0f,
                correction = Offset.Zero,
                center = activeSettle.fixedPinchCenter,
                identity = currentIdentity ?: return,
                toColumnCount = plan.fromColumnCount,
                handoffRequest = null,
                generation = generation,
                drawMode = MediaGridMorphDrawMode.RevealCurrent,
            )
            return
        }
        val identity = currentIdentity ?: return
        val targetAnchor = selectMediaGridMorphTargetAnchor(
            plan = plan,
            finalCorrection = nextCorrection,
            fixedFocalCenter = activeSettle.fixedPinchCenter,
        )
        if (targetAnchor == null) {
            publishFailure(
                currentColumnCount = identity.currentColumnCount,
                generation = generation,
                failureReason = MediaGridMorphFailureReason.TargetAnchorUnavailable,
                toColumnCount = plan.toColumnCount,
            )
            return
        }
        val request = MediaGridMorphHandoffRequest(
            interactionGeneration = generation,
            sourceRevision = identity.sourceRevision,
            sourceDataKey = identity.frameKey.dataKey,
            sourceFrameKey = identity.frameKey,
            expectedTargetFrameKey = MediaGridRenderKey(
                dataKey = identity.frameKey.dataKey,
                columnCount = plan.toColumnCount,
            ),
            fromColumnCount = plan.fromColumnCount,
            toColumnCount = plan.toColumnCount,
            plan = plan,
            interactionAnchor = plan.anchor,
            targetAnchor = targetAnchor,
            finalCorrection = nextCorrection,
            fixedFocalCenter = activeSettle.fixedPinchCenter,
            viewportWidth = identity.viewportSignature.viewportWidthPx,
            viewportHeight = identity.viewportSignature.viewportHeightPx,
            sourceFocalMediaOrdinal = plan.viewportPlan?.focalMediaOrdinal,
            targetFocalMediaOrdinal = plan.viewportPlan?.targetFocalMediaOrdinal,
            targetAnchorRowIndex = plan.viewportPlan?.targetAnchorRowIndex,
            targetAnchorRowTop = plan.viewportPlan?.targetAnchorRowTop,
            targetRowMediaOrdinals = plan.viewportPlan?.targetAnchorRowMediaOrdinals?.toList()
                ?.takeIf { it.isNotEmpty() }
                ?: plan.viewportPlan?.rowPlans
                    ?.firstOrNull { it.relativeRow == 0 }
                    ?.cells
                    ?.mapNotNull { it.targetMediaOrdinal }
                    .orEmpty(),
            targetFocalItemIndex = plan.viewportPlan?.let { viewportPlan ->
                viewportPlan.targetFocalMediaOrdinal?.let { ordinal ->
                    viewportPlan.exactTargetLayoutIndex?.itemIndexByMediaOrdinal?.getOrNull(ordinal)
                }
            },
            targetRowFirstItemIndex = plan.viewportPlan?.targetAnchorRowFirstItemIndex,
            targetExactRowId = plan.viewportPlan?.targetAnchorRowId,
            targetDesiredRowTopViewportLocal = plan.viewportPlan?.targetAnchorRowTop,
            targetUnclampedRowTopViewportLocal = plan.viewportPlan?.targetUnclampedRowTop,
            targetExactMaxScroll = plan.viewportPlan?.targetMaxScroll,
            exactTargetLayoutIndex = plan.viewportPlan?.exactTargetLayoutIndex,
            targetHeaderKey = plan.viewportPlan?.let { viewportPlan ->
                viewportPlan.exactTargetLayoutIndex
                    ?.headerForRow(viewportPlan.targetAnchorRowId ?: -1)
                    ?.key
            },
            targetCellSizePx = plan.viewportPlan?.let { it.viewport.width / plan.toColumnCount.coerceAtLeast(1) },
            targetHeaderTitle = plan.viewportPlan?.headerPlans
                ?.firstOrNull { it.relativeRow == 0 }
                ?.endTitle,
            frozenViewportPlan = plan.viewportPlan,
            sourceViewportAnchor = currentSnapshot.claimBundle?.sourceViewportAnchor,
        )
        publish(
            phase = MediaGridMorphPhase.AwaitingGridHandoff,
            direction = currentSnapshot.direction,
            plan = plan,
            progress = 1f,
            correction = nextCorrection,
            center = activeSettle.fixedPinchCenter,
            identity = identity,
            toColumnCount = plan.toColumnCount,
            handoffRequest = request,
            generation = generation,
            drawMode = MediaGridMorphDrawMode.Morph,
        )
        onHandoffRequest(request)
    }

    fun acknowledgeCurrentReveal(generation: Long) {
        if (currentSnapshot.phase != MediaGridMorphPhase.RevealingCurrent || currentSnapshot.interactionGeneration != generation) return
        resetToIdle(currentSnapshot.fromColumnCount, generation)
    }

    fun beginTargetReveal(generation: Long): Boolean {
        if (currentSnapshot.phase != MediaGridMorphPhase.AwaitingGridHandoff || currentSnapshot.interactionGeneration != generation) return false
        publish(
            phase = MediaGridMorphPhase.RevealingTarget,
            direction = currentSnapshot.direction,
            plan = currentSnapshot.plan,
            progress = 1f,
            correction = Offset.Zero,
            center = currentSnapshot.currentPinchCenter,
            identity = currentIdentity ?: return false,
            toColumnCount = currentSnapshot.toColumnCount,
            handoffRequest = currentSnapshot.handoffRequest,
            generation = generation,
            drawMode = MediaGridMorphDrawMode.RevealTarget,
        )
        return true
    }

    fun acknowledgeTargetReveal(generation: Long) {
        if (currentSnapshot.phase != MediaGridMorphPhase.RevealingTarget || currentSnapshot.interactionGeneration != generation) return
        resetToIdle(currentSnapshot.toColumnCount, generation, retainAssetsForImmediateReverse = true)
    }

    fun completeHandoff(generation: Long) {
        val request = currentSnapshot.handoffRequest ?: return
        if (
            currentSnapshot.phase != MediaGridMorphPhase.AwaitingGridHandoff ||
            request.interactionGeneration != generation
        ) return
        resetToIdle(request.toColumnCount, generation, retainAssetsForImmediateReverse = true)
    }

    fun cancelHandoff(generation: Long) {
        val request = currentSnapshot.handoffRequest ?: return
        if (
            currentSnapshot.phase != MediaGridMorphPhase.AwaitingGridHandoff ||
            request.interactionGeneration != generation
        ) return
        resetToIdle(request.fromColumnCount, generation)
    }

    private fun publish(
        phase: MediaGridMorphPhase,
        direction: MediaGridMorphDirection?,
        plan: MediaGridMorphPlan?,
        progress: Float,
        correction: Offset,
        center: Offset?,
        identity: MediaGridMorphInteractionIdentity,
        toColumnCount: Int,
        handoffRequest: MediaGridMorphHandoffRequest?,
        generation: Long,
        failureReason: MediaGridMorphFailureReason? = null,
        drawMode: MediaGridMorphDrawMode? = null,
        claimBundle: MediaGridMorphClaimBundle? = currentSnapshot.claimBundle,
        renderModel: MediaGridMorphRowRenderModel? = currentSnapshot.activeRenderModel,
    ) {
        val morphSnapshotComplete = claimBundle != null &&
            phase != MediaGridMorphPhase.Idle &&
            direction != null &&
            plan != null &&
            renderModel != null &&
            renderModel.isComplete &&
            protectedAssetIds != null &&
            claimBundle.generation == generation
        val resolvedDrawMode = drawMode ?: when (phase) {
            MediaGridMorphPhase.AwaitingGridHandoff ->
                if (morphSnapshotComplete) MediaGridMorphDrawMode.Morph else MediaGridMorphDrawMode.Normal
            MediaGridMorphPhase.RevealingCurrent -> MediaGridMorphDrawMode.RevealCurrent
            MediaGridMorphPhase.RevealingTarget ->
                if (morphSnapshotComplete) MediaGridMorphDrawMode.RevealTarget else MediaGridMorphDrawMode.Normal
            MediaGridMorphPhase.Tracking,
            MediaGridMorphPhase.SettlingToCurrent,
            MediaGridMorphPhase.SettlingToTarget,
                -> if (morphSnapshotComplete) MediaGridMorphDrawMode.Morph else MediaGridMorphDrawMode.Normal
            else -> MediaGridMorphDrawMode.Normal
        }
        val safeDrawMode = if (
            resolvedDrawMode == MediaGridMorphDrawMode.Morph && !morphSnapshotComplete
        ) MediaGridMorphDrawMode.Normal else resolvedDrawMode
        currentSnapshot = MediaGridMorphInteractionSnapshot(
            phase = phase,
            direction = direction,
            plan = plan,
            progress = progress,
            correction = correction,
            currentPinchCenter = center,
            fromColumnCount = identity.currentColumnCount,
            toColumnCount = toColumnCount,
            sourceRevision = identity.sourceRevision,
            frameKey = identity.frameKey,
            handoffRequest = handoffRequest,
            interactionGeneration = generation,
            failureReason = failureReason,
            drawMode = safeDrawMode,
            claimBundle = claimBundle,
            activeRenderModel = renderModel,
            protectedAssetIds = protectedAssetIds ?: LongArray(0),
        )
        _snapshotState.value = currentSnapshot
    }

    private fun resetToIdle(
        currentColumnCount: Int,
        generation: Long,
        failureReason: MediaGridMorphFailureReason? = null,
        retainAssetsForImmediateReverse: Boolean = false,
    ) {
        releaseProtectedAssets(retainAssetsForImmediateReverse)
        currentSnapshot = MediaGridMorphInteractionSnapshot(
            phase = MediaGridMorphPhase.Idle,
            direction = null,
            plan = null,
            progress = 0f,
            correction = Offset.Zero,
            currentPinchCenter = null,
            fromColumnCount = currentColumnCount,
            toColumnCount = currentColumnCount,
            sourceRevision = currentIdentity?.sourceRevision,
            frameKey = currentIdentity?.frameKey,
            handoffRequest = null,
            interactionGeneration = generation,
            failureReason = failureReason,
            drawMode = MediaGridMorphDrawMode.Normal,
            claimBundle = null,
            activeRenderModel = null,
            protectedAssetIds = LongArray(0),
        )
        _snapshotState.value = currentSnapshot
    }

    /**
     * Keep a diagnosable terminal failure without leaving a stale Canvas or
     * interaction lock over the current LazyGrid. A subsequent valid gesture
     * may start a new generation through beginPointers().
     */
    private fun publishFailure(
        currentColumnCount: Int,
        generation: Long,
        failureReason: MediaGridMorphFailureReason,
        toColumnCount: Int = currentColumnCount,
    ) {
        gesture = null
        settle = null
        releaseProtectedAssets()
        val identity = currentIdentity ?: return
        publish(
            phase = MediaGridMorphPhase.Failed,
            direction = currentSnapshot.direction,
            plan = null,
            progress = 0f,
            correction = Offset.Zero,
            center = null,
            identity = identity,
            toColumnCount = toColumnCount,
            handoffRequest = null,
            generation = generation,
            failureReason = failureReason,
            claimBundle = null,
            renderModel = null,
        )
    }

    private fun releaseProtectedAssets(retainForImmediateReverse: Boolean = false) {
        val assets = protectedAssetIds ?: return
        protectedAssetIds = null
        if (retainForImmediateReverse) onCarryoverAssets(assets) else onReleaseAssets(assets)
    }
}

@Composable
internal fun Modifier.mediaGridMorphGestureInput(
    mode: MediaGridMorphGestureMode,
    controller: MediaGridMorphInteractionController?,
    identity: MediaGridMorphInteractionIdentity?,
    preparedPairsSnapshot: () -> Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
    stopScroll: suspend () -> Unit = {},
    onFallbackPinchFinished: (ClassifiedMediaGridScrollAnchor?, Int) -> Unit = { _, _ -> },
    fallbackColumnCount: () -> Int = { identity?.currentColumnCount ?: 0 },
    fallbackAnchorAtCenter: (Offset) -> ClassifiedMediaGridScrollAnchor? = { null },
    pointerInProgress: MutableStateFlow<Boolean>? = null,
    prepareClaimBundle: ((MediaGridMorphCandidate) -> MediaGridMorphClaimPreparationResult)? = null,
    captureOnClaim: (() -> MediaGridMorphCapture?)? = null,
    isPairReady: (MediaGridMorphPreparedPair) -> Boolean = { true },
    claimFailureReason: (MediaGridMorphPreparedPair?) -> MediaGridMorphFailureReason? = { null },
    onMorphClaimAssets: (LongArray) -> Unit = {},
): Modifier {
    check(mode != MediaGridMorphGestureMode.Test || BuildConfig.TEST_HARNESS) {
        "mediaGridMorphGestureInput Test mode is restricted to TEST_HARNESS"
    }
    val latestIdentity by rememberUpdatedState(identity)
    val latestPairs by rememberUpdatedState(preparedPairsSnapshot)
    val latestStopScroll by rememberUpdatedState(stopScroll)
    val latestFallback by rememberUpdatedState(onFallbackPinchFinished)
    val latestColumnCount by rememberUpdatedState(fallbackColumnCount)
    val latestFallbackAnchor by rememberUpdatedState(fallbackAnchorAtCenter)
    val latestCaptureOnClaim by rememberUpdatedState(captureOnClaim)
    val latestPairReady by rememberUpdatedState(isPairReady)
    val latestClaimFailureReason by rememberUpdatedState(claimFailureReason)
    val latestMorphClaimAssets by rememberUpdatedState(onMorphClaimAssets)
    val latestPrepareClaimBundle by rememberUpdatedState(prepareClaimBundle)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    return pointerInput(mode, controller) {
        coroutineScope {
            val pointerScope = this
            var nextCandidateGeneration = 0L
            awaitEachGesture {
            var arbitrationState = MediaGridMorphGestureArbitrationState.OnePointerOrIdle
            var candidate: MediaGridMorphCandidate? = null
            var latestDistance = 0f
            var lockedFallbackDirection: MediaGridMorphDirection? = null
            var fallbackAnchor: ClassifiedMediaGridScrollAnchor? = null
            var stopScrollLaunched = false
            var trackedFirstPosition: Offset? = null
            var trackedSecondPosition: Offset? = null
            var trackedFirstPressed = false
            var trackedSecondPressed = false
            var completedByExplicitRelease = false

            fun consumeTracked(change: PointerInputChange?) {
                if (change == null) return
                if (change.position != change.previousPosition || !change.pressed) change.consume()
            }

            fun consumeClaimEvent(first: PointerInputChange?, second: PointerInputChange?) {
                first?.consume()
                second?.consume()
            }

            fun trackedChange(
                event: androidx.compose.ui.input.pointer.PointerEvent,
                pointerId: Long,
            ): PointerInputChange? {
                for (change in event.changes) {
                    if (change.id.value == pointerId) return change
                }
                return null
            }

            fun hasPressedPointer(event: androidx.compose.ui.input.pointer.PointerEvent): Boolean {
                for (change in event.changes) {
                    if (change.pressed) return true
                }
                return false
            }

            fun launchStopScrollOnce() {
                if (stopScrollLaunched) return
                stopScrollLaunched = true
                pointerScope.launch(start = CoroutineStart.UNDISPATCHED) { latestStopScroll() }
            }

            fun issueCanonicalFallback(
                distance: Float,
                center: Offset,
                direction: MediaGridMorphDirection? = lockedFallbackDirection,
            ) {
                val current = latestColumnCount()
                val decision = mediaGridMorphCanonicalReleaseDecision(
                    currentColumnCount = current,
                    initialDistance = candidate?.initialDistance ?: distance,
                    releaseDistance = distance,
                    lockedDirection = direction,
                )
                if (
                    decision.progress >= MediaGridMorphDefaults.ReleaseThreshold &&
                        decision.targetColumnCount != current
                ) {
                    fallbackAnchor = fallbackAnchor ?: latestFallbackAnchor(center)
                    latestFallback(fallbackAnchor, decision.targetColumnCount)
                }
            }

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Unknown) {
                        if (
                            arbitrationState == MediaGridMorphGestureArbitrationState.MorphClaimed ||
                                controller?.snapshot()?.phase != MediaGridMorphPhase.Idle
                        ) {
                            controller?.cancelPointers()
                        }
                        arbitrationState = MediaGridMorphGestureArbitrationState.ReleasedOrCancelled
                        break
                    }

                    var firstPressed: PointerInputChange? = null
                    var secondPressed: PointerInputChange? = null
                    for (change in event.changes) {
                        if (!change.pressed) continue
                        if (firstPressed == null) firstPressed = change else if (secondPressed == null) {
                            secondPressed = change
                            break
                        }
                    }
                    if (
                        arbitrationState == MediaGridMorphGestureArbitrationState.OnePointerOrIdle &&
                        firstPressed != null &&
                        secondPressed != null
                    ) {
                        val initialDistance = pointerDistance(firstPressed.position, secondPressed.position)
                        if (initialDistance.isFinite() && initialDistance > 0f) {
                            nextCandidateGeneration++
                            val candidateSeed = MediaGridMorphCandidate(
                                firstPointerId = firstPressed.id.value,
                                secondPointerId = secondPressed.id.value,
                                firstInitialPosition = firstPressed.position,
                                secondInitialPosition = secondPressed.position,
                                initialDistance = initialDistance,
                                initialCentroid = midpoint(firstPressed.position, secondPressed.position),
                                generation = nextCandidateGeneration,
                            )
                            // Claim preparation is intentionally deferred until
                            // the direction threshold is crossed. At that point
                            // the current pointer positions provide the actual
                            // pinch center used for plan selection.
                            candidate = candidateSeed
                            latestDistance = initialDistance
                            trackedFirstPosition = firstPressed.position
                            trackedSecondPosition = secondPressed.position
                            trackedFirstPressed = true
                            trackedSecondPressed = true
                            arbitrationState = MediaGridMorphGestureArbitrationState.TwoPointerCandidate
                        }
                    }

                    val activeCandidate = candidate
                    if (activeCandidate != null && (
                            arbitrationState == MediaGridMorphGestureArbitrationState.TwoPointerCandidate ||
                                arbitrationState == MediaGridMorphGestureArbitrationState.MorphClaimed ||
                                arbitrationState == MediaGridMorphGestureArbitrationState.FallbackClaimed
                            )) {
                        val first = trackedChange(event, activeCandidate.firstPointerId)
                        val second = trackedChange(event, activeCandidate.secondPointerId)
                        val wasBothPressed = trackedFirstPressed && trackedSecondPressed
                        first?.let {
                            trackedFirstPosition = it.position
                            trackedFirstPressed = it.pressed
                        }
                        second?.let {
                            trackedSecondPosition = it.position
                            trackedSecondPressed = it.pressed
                        }
                        val bothPresent = trackedFirstPosition != null && trackedSecondPosition != null
                        val bothPressed = bothPresent && trackedFirstPressed && trackedSecondPressed
                        if (bothPresent) {
                            latestDistance = pointerDistance(trackedFirstPosition!!, trackedSecondPosition!!)
                        }
                        val allPointersUp = !hasPressedPointer(event)
                        val trackedPointerMissing = first == null || second == null
                        // A tracked pointer missing for one event is not a release
                        // while the other pointer is still pressed. Only an
                        // explicit up, or the whole pointer scope becoming up,
                        // releases the claimed gesture.
                        val normalRelease = mediaGridMorphShouldRelease(
                            wasBothPressed = wasBothPressed,
                            bothPressed = bothPressed,
                            firstChangedToUp = first?.changedToUp() == true,
                            secondChangedToUp = second?.changedToUp() == true,
                            eventIsRelease = event.type == PointerEventType.Release,
                            allPointersUp = allPointersUp,
                            anyChangedToUp = event.changes.any { it.changedToUp() },
                        )

                        if (arbitrationState == MediaGridMorphGestureArbitrationState.TwoPointerCandidate) {
                            if (!bothPressed) {
                                if (normalRelease && mode != MediaGridMorphGestureMode.Disabled) {
                                    val currentCentroid = midpoint(
                                        trackedFirstPosition ?: activeCandidate.firstInitialPosition,
                                        trackedSecondPosition ?: activeCandidate.secondInitialPosition,
                                    )
                                    val parallelTranslation = trackedFirstPosition?.let { firstPosition ->
                                        trackedSecondPosition?.let { secondPosition ->
                                            mediaGridMorphIsParallelTranslation(
                                                candidate = activeCandidate,
                                                firstPosition = firstPosition,
                                                secondPosition = secondPosition,
                                                touchSlop = touchSlop,
                                            )
                                        }
                                    } == true
                                    val direction = if (parallelTranslation) null else {
                                        mediaGridMorphCandidateDirection(
                                            initialDistance = activeCandidate.initialDistance,
                                            currentDistance = latestDistance,
                                            touchSlop = touchSlop,
                                        )
                                    }
                                    if (direction != null) {
                                        lockedFallbackDirection = direction
                                        issueCanonicalFallback(latestDistance, currentCentroid, direction)
                                    }
                                }
                                arbitrationState = MediaGridMorphGestureArbitrationState.ReleasedOrCancelled
                            } else {
                                val currentFirstPosition = trackedFirstPosition!!
                                val currentSecondPosition = trackedSecondPosition!!
                                val currentDistance = pointerDistance(currentFirstPosition, currentSecondPosition)
                                val currentCentroid = midpoint(currentFirstPosition, currentSecondPosition)
                                latestDistance = currentDistance
                                val direction = if (
                                    mediaGridMorphIsParallelTranslation(
                                        candidate = activeCandidate,
                                        firstPosition = currentFirstPosition,
                                        secondPosition = currentSecondPosition,
                                        touchSlop = touchSlop,
                                    )
                                ) {
                                    null
                                } else {
                                    mediaGridMorphCandidateDirection(
                                        initialDistance = activeCandidate.initialDistance,
                                        currentDistance = currentDistance,
                                        touchSlop = touchSlop,
                                    )
                                }
                                if (direction != null && mode != MediaGridMorphGestureMode.Disabled) {
                                    lockedFallbackDirection = direction
                                    // Production preparation is bounded and
                                    // selected from the actual current pinch
                                    // center. A cached pair may describe a
                                    // fallback, but it must not authorize a
                                    // Morph claim when a production preparer
                                    // is available; never claim a stale bundle.
                                    val cachedPairs = latestPairs()
                                    val productionClaimAllowed = if (mode != MediaGridMorphGestureMode.Production) {
                                        true
                                    } else if (latestPrepareClaimBundle != null) {
                                        true
                                    } else if (activeCandidate.claimBundle != null) {
                                        activeCandidate.claimBundle.isCompleteFor(direction)
                                    } else if (latestCaptureOnClaim == null) {
                                        cachedPairs.isEmpty() || cachedPairs[direction]?.let { pair ->
                                            latestIdentity?.let(pair::matchesIdentity) == true && latestPairReady(pair)
                                        } == true
                                    } else {
                                        true
                                    }
                                    val claimCandidate = activeCandidate.copy(
                                        claimFirstPosition = currentFirstPosition,
                                        claimSecondPosition = currentSecondPosition,
                                        claimDirection = direction,
                                    )
                                    val claimPreparation = if (
                                        mode == MediaGridMorphGestureMode.Production &&
                                            productionClaimAllowed
                                    ) {
                                        latestPrepareClaimBundle?.invoke(claimCandidate)
                                    } else {
                                        null
                                    }
                                    if (
                                        productionClaimAllowed &&
                                            (mode != MediaGridMorphGestureMode.Production ||
                                                claimPreparation == null ||
                                                claimPreparation is MediaGridMorphClaimPreparationResult.Ready)
                                    ) {
                                        launchStopScrollOnce()
                                    }
                                    val claimBundle = when (claimPreparation) {
                                        is MediaGridMorphClaimPreparationResult.Ready -> claimPreparation.bundle
                                        else -> if (claimPreparation == null) activeCandidate.claimBundle else null
                                    }
                                    val claimCapture = if (claimBundle == null && claimPreparation == null) {
                                        latestCaptureOnClaim?.invoke()
                                    } else null
                                    val claimIdentity = claimBundle?.identity ?: claimCapture?.identity?.toInteractionIdentity() ?: latestIdentity
                                    val claimBundleIdentityMatches = claimBundle != null && (
                                        mediaGridMorphClaimBundleMatchesIdentity(claimBundle, latestIdentity)
                                    )
                                    val availablePairs = when {
                                        claimBundle != null -> claimBundle.directions.mapValues { it.value.plan.preparedPair }
                                        claimCapture != null -> buildMediaGridMorphRowPreparedPairs(claimCapture)
                                        mode == MediaGridMorphGestureMode.Test || (
                                            mode == MediaGridMorphGestureMode.Production &&
                                                claimPreparation == null &&
                                                latestCaptureOnClaim == null
                                            ) -> latestPairs()
                                        else -> emptyMap()
                                    }
                                    val currentPairs = availablePairs.filterValues(latestPairReady).takeIf { it.isNotEmpty() } ?: emptyMap()
                                    val currentPair = currentPairs[direction]
                                    val morphAccepted = if (controller == null || claimIdentity == null) {
                                        false
                                    } else if (claimBundle != null && claimBundleIdentityMatches) {
                                        claimBundleIdentityMatches && controller.claimPointers(
                                            bundle = claimBundle,
                                            currentFirstPosition = currentFirstPosition,
                                            currentSecondPosition = currentSecondPosition,
                                        )
                                    } else if (mode != MediaGridMorphGestureMode.Production) {
                                        currentPair != null && currentPair.matchesIdentity(claimIdentity) &&
                                            controller.beginPointers(
                                                identity = claimIdentity,
                                                preparedPairsSnapshot = currentPairs,
                                                firstPointerId = activeCandidate.firstPointerId,
                                                secondPointerId = activeCandidate.secondPointerId,
                                                firstPosition = activeCandidate.firstInitialPosition,
                                                secondPosition = activeCandidate.secondInitialPosition,
                                            )
                                    } else {
                                        false
                                    }
                                    if (BuildConfig.TEST_HARNESS) {
                                        MediaGridMorphTestTrace.recordClaim(
                                            MediaGridMorphClaimObservation(
                                                generation = activeCandidate.generation,
                                                direction = direction,
                                                bundlePresent = claimBundle != null,
                                                directionPrepared = claimBundle?.isCompleteFor(direction) == true,
                                                accepted = morphAccepted,
                                                failureReason = if (morphAccepted) null else {
                                                    (claimPreparation as? MediaGridMorphClaimPreparationResult.Unavailable)
                                                        ?.let { mediaGridMorphFailureReasonForClaimReadiness(it.reason) }
                                                        ?: latestClaimFailureReason(availablePairs[direction])
                                                },
                                                readinessReason = (claimPreparation as? MediaGridMorphClaimPreparationResult.Unavailable)
                                                    ?.reason,
                                                readinessReport = claimPreparation?.report,
                                            ),
                                        )
                                    }
                                    if (morphAccepted) {
                                        if (claimBundle == null) {
                                            latestMorphClaimAssets(currentPair?.requiredMorphAssetIds() ?: LongArray(0))
                                            controller?.updatePointers(currentFirstPosition, currentSecondPosition)
                                        }
                                        pointerInProgress?.value = true
                                        arbitrationState = MediaGridMorphGestureArbitrationState.MorphClaimed
                                    } else {
                                        controller?.recordFailure(
                                            when {
                                                claimPreparation is MediaGridMorphClaimPreparationResult.Unavailable ->
                                                    mediaGridMorphFailureReasonForClaimReadiness(claimPreparation.reason)
                                                claimBundle == null && claimCapture == null && latestCaptureOnClaim != null ->
                                                    MediaGridMorphFailureReason.CaptureUnavailable
                                                claimBundle != null && !claimBundleIdentityMatches ->
                                                    MediaGridMorphFailureReason.IdentityMismatch
                                                claimIdentity == null -> MediaGridMorphFailureReason.IdentityMismatch
                                                else -> latestClaimFailureReason(availablePairs[direction])
                                                    ?: if (availablePairs[direction] == null) {
                                                        MediaGridMorphFailureReason.PairUnavailable
                                                    } else {
                                                        MediaGridMorphFailureReason.RenderModelIncomplete
                                                    }
                                            },
                                        )
                                        fallbackAnchor = latestFallbackAnchor(currentCentroid)
                                        pointerInProgress?.value = true
                                        arbitrationState = MediaGridMorphGestureArbitrationState.FallbackClaimed
                                    }
                                    consumeClaimEvent(first, second)
                                }
                            }
                        } else if (arbitrationState == MediaGridMorphGestureArbitrationState.MorphClaimed) {
                            if (bothPresent) {
                                controller?.updatePointers(trackedFirstPosition!!, trackedSecondPosition!!)
                            }
                            if (!bothPressed) {
                                if (trackedPointerMissing && hasPressedPointer(event)) {
                                    // Preserve the last position/pressed state
                                    // until the missing tracked pointer returns.
                                    consumeTracked(first)
                                    consumeTracked(second)
                                } else if (normalRelease) {
                                    val snapshot = controller?.snapshot()
                                    if (
                                        snapshot?.claimBundle != null &&
                                            (snapshot.plan == null || snapshot.activeRenderModel == null)
                                    ) {
                                        controller?.cancelPointers()
                                        issueCanonicalFallback(
                                            latestDistance,
                                            midpoint(
                                                trackedFirstPosition ?: activeCandidate.firstInitialPosition,
                                                trackedSecondPosition ?: activeCandidate.secondInitialPosition,
                                            ),
                                        )
                                    } else {
                                        // releasePointers starts the settle clock on the
                                        // first Compose frame. PointerInput uptimeMillis
                                        // has a different clock origin than withFrameNanos.
                                        controller?.releasePointers()
                                        completedByExplicitRelease = true
                                    }
                                } else {
                                    controller?.cancelPointers()
                                }
                                arbitrationState = MediaGridMorphGestureArbitrationState.ReleasedOrCancelled
                            } else {
                                consumeTracked(first)
                                consumeTracked(second)
                            }
                        } else if (arbitrationState == MediaGridMorphGestureArbitrationState.FallbackClaimed) {
                            if (bothPresent) latestDistance = pointerDistance(trackedFirstPosition!!, trackedSecondPosition!!)
                            if (!bothPressed) {
                                issueCanonicalFallback(
                                    latestDistance,
                                    midpoint(
                                        trackedFirstPosition ?: activeCandidate.firstInitialPosition,
                                        trackedSecondPosition ?: activeCandidate.secondInitialPosition,
                                    ),
                                )
                                arbitrationState = MediaGridMorphGestureArbitrationState.ReleasedOrCancelled
                            } else {
                                consumeTracked(first)
                                consumeTracked(second)
                            }
                        }
                    }
                    if (!hasPressedPointer(event)) break
                }
            } finally {
                if (
                    !completedByExplicitRelease &&
                        arbitrationState != MediaGridMorphGestureArbitrationState.FallbackClaimed &&
                        controller?.snapshot()?.phase != MediaGridMorphPhase.Idle
                ) {
                    controller?.cancelPointers()
                }
                pointerInProgress?.value = false
            }
            }
        }
    }
}

@Composable
internal fun MediaGridMorphInteractiveTestLayer(
    controller: MediaGridMorphInteractionController,
    identity: MediaGridMorphInteractionIdentity,
    preparedPairsSnapshot: () -> Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    stopScroll: suspend () -> Unit = {},
    modifier: Modifier = Modifier,
    onRenderModelBuilt: (() -> Unit)? = null,
    onImageResolved: ((Long) -> Unit)? = null,
    onTextMeasured: ((String) -> Unit)? = null,
) {
    check(BuildConfig.TEST_HARNESS) {
        "MediaGridMorphInteractiveTestLayer is restricted to TEST_HARNESS"
    }
    controller.updateIdentity(identity)
    val settleGeneration = controller.settleSignal.value
    LaunchedEffect(controller, settleGeneration) {
        if (settleGeneration == 0L) return@LaunchedEffect
        while (controller.isSettling(settleGeneration)) {
            withFrameNanos(controller::advanceSettleFrame)
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .mediaGridMorphGestureInput(
                mode = MediaGridMorphGestureMode.Test,
                controller = controller,
                identity = identity,
                preparedPairsSnapshot = preparedPairsSnapshot,
                stopScroll = stopScroll,
            ),
    ) {
        val plan = controller.activePlan.value
        if (plan != null) {
            MediaGridMorphCanvasLayer(
                plan = plan,
                preparedIndex = preparedIndex,
                progress = controller.progress,
                correction = controller.correction,
                mode = MediaGridMorphCanvasMode.TestVisible,
                onRenderModelBuilt = onRenderModelBuilt,
                onImageResolved = onImageResolved,
                onTextMeasured = onTextMeasured,
            )
        }
    }
}

private fun pointerDistance(first: Offset, second: Offset): Float =
    hypot(first.x - second.x, first.y - second.y)

private fun midpoint(first: Offset, second: Offset): Offset =
    Offset((first.x + second.x) / 2f, (first.y + second.y) / 2f)

private fun mediaGridMorphProgressForPlan(
    plan: MediaGridMorphPlan,
    initialDistance: Float,
    currentDistance: Float,
    scale: Float,
    direction: MediaGridMorphDirection,
): Float = if (plan.viewportPlan != null) {
    mediaGridMorphProgressForDistance(
        initialDistance = initialDistance,
        currentDistance = currentDistance,
        fromColumnCount = plan.fromColumnCount,
        toColumnCount = plan.toColumnCount,
    )
} else {
    mediaGridMorphProgressForScale(scale, direction)
}

private fun lerpValue(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun lerpOffset(start: Offset, end: Offset, fraction: Float): Offset =
    Offset(
        lerpValue(start.x, end.x, fraction),
        lerpValue(start.y, end.y, fraction),
    )
