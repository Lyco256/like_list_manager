package com.lyco256.llm

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    val finalPinchCenter: Offset,
    val viewportWidth: Int,
    val viewportHeight: Int,
)

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
)

internal enum class MediaGridMorphGestureMode {
    Disabled,
    Test,
    Production,
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
    currentPinchCenter: Offset,
): Offset {
    val anchor = plan.anchor ?: return Offset.Zero
    val rect = mediaGridMorphRect(anchor.slot, progress.coerceIn(0f, 1f))
    val focalInViewport = Offset(
        x = rect.left + rect.width * anchor.focalU,
        y = rect.top + rect.height * anchor.focalV,
    )
    val focalInCanvas = focalInViewport - plan.viewport.topLeft
    return currentPinchCenter - focalInCanvas
}

internal class MediaGridMorphInteractionController(
    private val onHandoffRequest: (MediaGridMorphHandoffRequest) -> Unit = {},
) {
    private data class Gesture(
        val identity: MediaGridMorphInteractionIdentity,
        val pairs: Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
        val firstPointerId: Long,
        val secondPointerId: Long,
        val initialDistance: Float,
        val initialCenter: Offset,
        val generation: Long,
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

    private val _activePlan = mutableStateOf<MediaGridMorphPlan?>(null)
    private val _progress = mutableStateOf(0f)
    private val _correction = mutableStateOf(Offset.Zero)
    private val _handoffRequest = mutableStateOf<MediaGridMorphHandoffRequest?>(null)
    private val _interactionLocked = mutableStateOf(false)
    internal val settleSignal: MutableState<Long> = mutableLongStateOf(0L)
    val activePlan: State<MediaGridMorphPlan?> get() = _activePlan
    val progress: State<Float> get() = _progress
    val correction: State<Offset> get() = _correction
    val handoffRequest: State<MediaGridMorphHandoffRequest?> get() = _handoffRequest
    val interactionLocked: State<Boolean> get() = _interactionLocked

    fun snapshot(): MediaGridMorphInteractionSnapshot = currentSnapshot

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
            firstPointerId = firstPointerId,
            secondPointerId = secondPointerId,
            initialDistance = initialDistance,
            initialCenter = center,
            generation = generation,
        )
        settle = null
        _activePlan.value = null
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
        var direction = currentSnapshot.direction
        if (nextDirection != null && nextDirection != direction) {
            val pair = activeGesture.pairs[nextDirection]
            if (pair != null) {
                plan = MediaGridMorphPlan.select(pair, activeGesture.initialCenter)
                direction = nextDirection
                _activePlan.value = plan
            }
        }
        val nextProgress = if (nextDirection == null || plan == null) {
            0f
        } else {
            mediaGridMorphProgressForScale(scale, nextDirection)
        }
        val nextCorrection = plan?.let {
            mediaGridMorphFocalCorrection(it, nextProgress, center)
        } ?: Offset.Zero
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
        )
    }

    fun releasePointers(releaseTimeNanos: Long? = null) {
        val activeGesture = gesture ?: return
        if (currentSnapshot.phase != MediaGridMorphPhase.Tracking) return
        val plan = currentSnapshot.plan
        val center = currentSnapshot.currentPinchCenter
        gesture = null
        if (plan == null || center == null) {
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
            firstFrameNanos = releaseTimeNanos,
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
        if (snapshot.phase == MediaGridMorphPhase.AwaitingGridHandoff) {
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
                if (expectedTargetIdentity) currentIdentity = identity
                return
            }
            currentIdentity = identity
            gesture = null
            settle = null
            resetToIdle(identity.currentColumnCount, snapshot.interactionGeneration)
            return
        }
        currentIdentity = identity
        val plan = snapshot.plan
        if (
            snapshot.sourceRevision != identity.sourceRevision ||
            snapshot.frameKey != identity.frameKey ||
            snapshot.fromColumnCount != identity.currentColumnCount ||
            plan?.preparedPair?.viewportSignature != identity.viewportSignature
        ) {
            gesture = null
            settle = null
            resetToIdle(identity.currentColumnCount, snapshot.interactionGeneration)
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
            resetToIdle(plan.fromColumnCount, generation)
            return
        }
        val identity = currentIdentity ?: return
        val targetAnchor = selectMediaGridMorphTargetAnchor(
            plan = plan,
            finalCorrection = nextCorrection,
            finalPinchCenter = activeSettle.fixedPinchCenter,
        )
        if (targetAnchor == null) {
            resetToIdle(plan.fromColumnCount, generation)
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
            finalPinchCenter = activeSettle.fixedPinchCenter,
            viewportWidth = identity.viewportSignature.viewportWidthPx,
            viewportHeight = identity.viewportSignature.viewportHeightPx,
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
        )
        onHandoffRequest(request)
    }

    fun completeHandoff(generation: Long) {
        val request = currentSnapshot.handoffRequest ?: return
        if (
            currentSnapshot.phase != MediaGridMorphPhase.AwaitingGridHandoff ||
            request.interactionGeneration != generation
        ) return
        resetToIdle(request.toColumnCount, generation)
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
    ) {
        _progress.value = progress
        _correction.value = correction
        _handoffRequest.value = handoffRequest
        _interactionLocked.value = phase != MediaGridMorphPhase.Idle
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
        )
    }

    private fun resetToIdle(currentColumnCount: Int, generation: Long) {
        _activePlan.value = null
        _progress.value = 0f
        _correction.value = Offset.Zero
        _handoffRequest.value = null
        _interactionLocked.value = false
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
        )
    }
}

@Composable
internal fun Modifier.mediaGridMorphGestureInput(
    mode: MediaGridMorphGestureMode,
    controller: MediaGridMorphInteractionController?,
    identity: MediaGridMorphInteractionIdentity?,
    preparedPairsSnapshot: () -> Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
    isScrollInProgress: () -> Boolean,
    onFallbackPinchFinished: (ClassifiedMediaGridScrollAnchor?, Int) -> Unit = { _, _ -> },
    fallbackColumnCount: () -> Int = { identity?.currentColumnCount ?: 0 },
    fallbackAnchorAtCenter: (Offset) -> ClassifiedMediaGridScrollAnchor? = { null },
    pointerInProgress: MutableStateFlow<Boolean>? = null,
): Modifier {
    check(mode != MediaGridMorphGestureMode.Test || BuildConfig.TEST_HARNESS) {
        "mediaGridMorphGestureInput Test mode is restricted to TEST_HARNESS"
    }
    val latestIdentity by rememberUpdatedState(identity)
    val latestPairs by rememberUpdatedState(preparedPairsSnapshot)
    val latestScroll by rememberUpdatedState(isScrollInProgress)
    val latestFallback by rememberUpdatedState(onFallbackPinchFinished)
    val latestColumnCount by rememberUpdatedState(fallbackColumnCount)
    val latestFallbackAnchor by rememberUpdatedState(fallbackAnchorAtCenter)
    return pointerInput(mode, controller) {
        awaitEachGesture {
            var morphAccepted = false
            var fallbackTracking = false
            var released = false
            var firstId = Long.MIN_VALUE
            var secondId = Long.MIN_VALUE
            var initialDistance = 0f
            var latestDistance = 0f
            var fallbackAnchor: ClassifiedMediaGridScrollAnchor? = null
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Unknown) {
                        if (morphAccepted && !released) {
                            released = true
                            controller?.cancelPointers()
                        }
                        break
                    }
                    var first: PointerInputChange? = null
                    var second: PointerInputChange? = null
                    for (change in event.changes) {
                        if (!change.pressed) continue
                        if (first == null) first = change else if (second == null) {
                            second = change
                            break
                        }
                    }
                    if (!released && first != null && second != null && !fallbackTracking && !morphAccepted) {
                        firstId = first.id.value
                        secondId = second.id.value
                        initialDistance = pointerDistance(first.position, second.position)
                        latestDistance = initialDistance
                        val canTrack = initialDistance.isFinite() && initialDistance > 0f && !latestScroll()
                        fallbackTracking = canTrack
                        if (canTrack) pointerInProgress?.value = true
                        if (canTrack) {
                            fallbackAnchor = latestFallbackAnchor(midpoint(first.position, second.position))
                        }
                        if (
                            mode != MediaGridMorphGestureMode.Disabled &&
                            canTrack &&
                            controller != null &&
                            latestIdentity != null
                        ) {
                            morphAccepted = controller.beginPointers(
                                identity = latestIdentity!!,
                                preparedPairsSnapshot = latestPairs(),
                                firstPointerId = firstId,
                                secondPointerId = secondId,
                                firstPosition = first.position,
                                secondPosition = second.position,
                            )
                            if (morphAccepted) fallbackTracking = false
                        }
                    }
                    if (morphAccepted && !released) {
                        first = null
                        second = null
                        for (change in event.changes) {
                            when (change.id.value) {
                                firstId -> first = change
                                secondId -> second = change
                            }
                        }
                        if (first == null || second == null || !first.pressed || !second.pressed) {
                            released = true
                            val normalRelease =
                                first?.changedToUp() == true || second?.changedToUp() == true
                            if (normalRelease) {
                                val releaseUptimeMillis = maxOf(
                                    first?.uptimeMillis ?: 0L,
                                    second?.uptimeMillis ?: 0L,
                                )
                                controller?.releasePointers(
                                    releaseTimeNanos = releaseUptimeMillis * 1_000_000L,
                                )
                            } else {
                                controller?.cancelPointers()
                            }
                            first?.takeIf {
                                mediaGridMorphShouldConsumePointer(true, it.id.value, firstId, secondId)
                            }?.consume()
                            second?.takeIf {
                                mediaGridMorphShouldConsumePointer(true, it.id.value, firstId, secondId)
                            }?.consume()
                        } else {
                            controller?.updatePointers(first.position, second.position)
                            if (
                                first.position != first.previousPosition &&
                                mediaGridMorphShouldConsumePointer(true, first.id.value, firstId, secondId)
                            ) first.consume()
                            if (
                                second.position != second.previousPosition &&
                                mediaGridMorphShouldConsumePointer(true, second.id.value, firstId, secondId)
                            ) second.consume()
                        }
                    } else if (fallbackTracking && !released) {
                        first = null
                        second = null
                        for (change in event.changes) {
                            when (change.id.value) {
                                firstId -> first = change
                                secondId -> second = change
                            }
                        }
                        if (first != null && second != null) {
                            latestDistance = pointerDistance(first.position, second.position)
                        }
                        val pointersReleased = first == null || second == null || !first.pressed || !second.pressed
                        if (pointersReleased) {
                            released = true
                            val scale = mediaGridMorphScale(initialDistance, latestDistance)
                            val nextColumnCount = scale?.let {
                                mediaGridColumnCountAfterPinchRelease(latestColumnCount(), it)
                            } ?: latestColumnCount()
                            latestFallback(fallbackAnchor, nextColumnCount)
                        }
                        for (change in event.changes) {
                            if (change.id.value == firstId || change.id.value == secondId) change.consume()
                        }
                    }
                    if (!morphAccepted && !fallbackTracking && !released && mode == MediaGridMorphGestureMode.Disabled) {
                        // Disabled still allows the ordinary one-pointer grid interaction.
                    }
                    var anyPressed = false
                    for (change in event.changes) {
                        if (change.pressed) {
                            anyPressed = true
                            break
                        }
                    }
                    if (!anyPressed) break
                }
            } finally {
                if (morphAccepted && !released) controller?.cancelPointers()
                pointerInProgress?.value = false
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
    isScrollInProgress: () -> Boolean,
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
                isScrollInProgress = isScrollInProgress,
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

private fun lerpValue(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun lerpOffset(start: Offset, end: Offset, fraction: Float): Offset =
    Offset(
        lerpValue(start.x, end.x, fraction),
        lerpValue(start.y, end.y, fraction),
    )
