package com.lyco256.llm

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

internal class MediaGridMorphProductionHostState(
    val retainedImageStore: MediaGridRetainedImageStore,
) {
    val ownerToken: Long = retainedImageStore.newOwnerToken()
    val requestChannel = Channel<MediaGridMorphHandoffRequest>(Channel.UNLIMITED)
    val commandChannel = Channel<MediaGridMorphProductionCommand>(Channel.UNLIMITED)
    val coordinator = MediaGridMorphGridHandoffCoordinator()
    private var carryoverAssetIds = LongArray(0)
    val controller = MediaGridMorphInteractionController { request -> requestChannel.trySend(request) }.also { controller ->
        controller.setProtectionCallbacks(
            onProtect = { assetIds ->
            carryoverAssetIds = LongArray(0)
            retainedImageStore.updateProtection(
                ownerToken = ownerToken,
                visibleAssetIds = LongArray(0),
                activeAssetIds = assetIds,
            )
            },
            onRelease = {
            carryoverAssetIds = LongArray(0)
            retainedImageStore.updateProtection(
                ownerToken = ownerToken,
                visibleAssetIds = LongArray(0),
                activeAssetIds = LongArray(0),
            )
            },
            onCarryover = { assetIds ->
            carryoverAssetIds = assetIds.copyOf()
            retainedImageStore.updateProtection(
                ownerToken = ownerToken,
                visibleAssetIds = LongArray(0),
                activeAssetIds = carryoverAssetIds,
            )
            },
        )
    }
    val handoffSnapshot: State<MediaGridMorphGridHandoffSnapshot>
        get() = _handoffSnapshot

    private val _handoffSnapshot = mutableStateOf(coordinator.snapshot())
    private var disposed = false

    fun publishHandoffSnapshot() {
        if (!disposed) {
            val snapshot = coordinator.snapshot()
            _handoffSnapshot.value = snapshot
            if (BuildConfig.TEST_HARNESS) {
                MediaGridMorphTestTrace.recordHandoff(
                    MediaGridMorphHandoffObservation(
                        generation = snapshot.request?.interactionGeneration ?: 0L,
                        phase = snapshot.phase,
                        suppressesUserScroll = snapshot.suppressesUserScroll,
                        interactionLocked = controller.interactionLocked.value,
                    ),
                )
            }
        }
    }

    fun releaseCarryoverAfterStableIdleReady() {
        if (carryoverAssetIds.isEmpty() || controller.snapshot().phase != MediaGridMorphPhase.Idle) return
        carryoverAssetIds = LongArray(0)
        retainedImageStore.updateProtection(
            ownerToken = ownerToken,
            visibleAssetIds = LongArray(0),
            activeAssetIds = LongArray(0),
        )
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        controller.cancelPointers()
        retainedImageStore.removeOwner(ownerToken)
        requestChannel.close()
        commandChannel.close()
    }
}

internal data class MediaGridMorphProductionCommand(
    val generation: Long,
    val command: MediaGridMorphGridHandoffCommand,
)

@Composable
internal fun rememberMediaGridMorphProductionHostState(
    state: LazyGridState,
    sessionKey: MediaGridSessionKey?,
    retainedImageStore: MediaGridRetainedImageStore?,
    enabled: Boolean,
): MediaGridMorphProductionHostState? {
    val host = remember(state, sessionKey, retainedImageStore, enabled) {
        if (enabled && retainedImageStore != null) {
            MediaGridMorphProductionHostState(retainedImageStore)
        } else {
            null
        }
    }
    DisposableEffect(host) {
        onDispose { host?.dispose() }
    }
    return host
}

private data class MediaGridMorphProductionLayoutSample(
    val target: MediaGridMorphVisibleItemGeometry?,
    val viewportWidth: Int,
    val viewportHeight: Int,
)

/**
 * TEST_HARNESS-only visual compatibility host. Production uses
 * MediaGridMorphProductionHandoffEffects and the unified LazyGrid surface.
 */
@Composable
internal fun MediaGridMorphTestHandoffHost(
    host: MediaGridMorphProductionHostState,
    frame: MediaGridFrameData,
    sessionKey: MediaGridSessionKey?,
    identity: MediaGridMorphInteractionIdentity,
    preparedPairsSnapshot: () -> Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    state: LazyGridState,
    onColumnCountChange: (Int) -> Unit,
    onAnchorCheckpoint: (MediaGridSessionKey, ClassifiedMediaGridScrollAnchor) -> Unit,
    onCheckpointSuppressed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    check(BuildConfig.TEST_HARNESS) {
        "MediaGridMorphTestHandoffHost is restricted to TEST_HARNESS"
    }
    val lifecycleOwner = LocalContext.current as? LifecycleOwner
    val latestFrame by rememberUpdatedState(frame)
    val latestSessionKey by rememberUpdatedState(sessionKey)
    val latestColumnChange by rememberUpdatedState(onColumnCountChange)
    val latestCheckpoint by rememberUpdatedState(onAnchorCheckpoint)
    val latestSuppression by rememberUpdatedState(onCheckpointSuppressed)
    val controller = host.controller
    val coordinator = host.coordinator
    controller.updateIdentity(identity)

    val activePlan by controller.activePlan
    val settleGeneration by controller.settleSignal
    val interactionLocked by controller.interactionLocked
    val handoffSnapshot by host.handoffSnapshot
    val locked = interactionLocked || handoffSnapshot.suppressesUserScroll
    var lockedGeneration by remember(host) { mutableStateOf<Long?>(null) }
    var lockedDataKey by remember(host) { mutableStateOf<MediaGridDataKey?>(null) }
    var checkpointHandledGeneration by remember(host) { mutableStateOf<Long?>(null) }

    SideEffect { latestSuppression(locked) }
    DisposableEffect(host) {
        onDispose { latestSuppression(false) }
    }
    if (lifecycleOwner != null) {
        DisposableEffect(lifecycleOwner, host) {
            val observer = LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
                val snapshot = controller.snapshot()
                val request = snapshot.handoffRequest
                if (request != null && snapshot.phase == MediaGridMorphPhase.AwaitingGridHandoff) {
                    val command = coordinator.failCurrent()
                    if (command != null) {
                        host.commandChannel.trySend(
                            MediaGridMorphProductionCommand(request.interactionGeneration, command),
                        )
                    }
                    host.publishHandoffSnapshot()
                } else if (snapshot.phase != MediaGridMorphPhase.Idle) {
                    controller.cancelPointers()
                    checkpointHandledGeneration = snapshot.interactionGeneration
                    val key = latestSessionKey
                    val currentFrame = latestFrame
                    if (key != null && currentFrame != null) {
                        captureClassifiedMediaGridScrollAnchor(state, currentFrame.assetIdByItemKey)
                            ?.let { latestCheckpoint(key, it) }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(host, locked, frame.key) {
        val snapshot = controller.snapshot()
        if (locked) {
            lockedGeneration = snapshot.interactionGeneration
            lockedDataKey = snapshot.frameKey?.dataKey
            return@LaunchedEffect
        }
        val generation = lockedGeneration ?: return@LaunchedEffect
        if (checkpointHandledGeneration != generation && frame.key.dataKey == lockedDataKey) {
            val key = latestSessionKey
            captureClassifiedMediaGridScrollAnchor(state, frame.assetIdByItemKey)
                ?.let { if (key != null) latestCheckpoint(key, it) }
        }
        lockedGeneration = null
        lockedDataKey = null
    }

    LaunchedEffect(controller, settleGeneration) {
        if (settleGeneration == 0L) return@LaunchedEffect
        while (controller.isSettling(settleGeneration)) {
            withFrameNanos(controller::advanceSettleFrame)
        }
    }

    fun enqueue(command: MediaGridMorphGridHandoffCommand?, generation: Long) {
        if (command != null) {
            host.commandChannel.trySend(MediaGridMorphProductionCommand(generation, command))
        }
        host.publishHandoffSnapshot()
    }

    suspend fun observeCurrentHandoffLayout(
        request: MediaGridMorphHandoffRequest,
        currentFrame: MediaGridFrameData,
    ) {
        val snapshot = coordinator.snapshot()
        if (
            snapshot.phase != MediaGridMorphGridHandoffPhase.PositioningTarget &&
            snapshot.phase != MediaGridMorphGridHandoffPhase.RollingBack
        ) return
        val layout = state.layoutInfo
        val targetTranslationX = request.finalCorrection.x.takeIf {
            snapshot.phase == MediaGridMorphGridHandoffPhase.PositioningTarget
        } ?: 0f
        val targetTranslationY = request.finalCorrection.y.takeIf {
            snapshot.phase == MediaGridMorphGridHandoffPhase.PositioningTarget
        } ?: 0f
        val target = snapshot.resolvedTarget?.let { resolved ->
            layout.visibleItemsInfo.firstNotNullOfOrNull { info ->
                val key = info.key as? String ?: return@firstNotNullOfOrNull null
                val assetId = currentFrame.assetIdByItemKey[key] ?: return@firstNotNullOfOrNull null
                if (assetId != resolved.assetId) return@firstNotNullOfOrNull null
                MediaGridMorphVisibleItemGeometry(
                    assetId = assetId,
                    itemIndex = info.index,
                    rect = Rect(
                        left = info.offset.x.toFloat() + targetTranslationX,
                        top = (info.offset.y - layout.viewportStartOffset).toFloat() + targetTranslationY,
                        right = (info.offset.x + info.size.width).toFloat() + targetTranslationX,
                        bottom = (info.offset.y - layout.viewportStartOffset + info.size.height).toFloat() + targetTranslationY,
                    ),
                )
            }
        }
        enqueue(
            coordinator.observeLayout(
                frame = currentFrame,
                visibleTarget = target,
                visibleTargetRow = captureMediaGridMorphVisibleTargetRow(
                    state = state,
                    frame = currentFrame,
                    targetOrdinal = request.targetFocalMediaOrdinal ?: request.targetAnchor.mediaOrdinal,
                    targetRowIndex = request.targetAnchorRowIndex,
                    exactTargetLayoutIndex = request.exactTargetLayoutIndex,
                ),
                visibleTargetViewport = captureMediaGridMorphVisibleTargetViewport(
                    state = state,
                    frame = currentFrame,
                    exactTargetLayoutIndex = request.exactTargetLayoutIndex,
                    targetRowIndex = request.targetAnchorRowIndex,
                ),
                viewportWidth = layout.viewportSize.width,
                viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
            ),
            request.interactionGeneration,
        )
        if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.VerifyingTarget) {
            withFrameNanos { }
            enqueue(
                coordinator.underlyingTargetGridDrawn(),
                request.interactionGeneration,
            )
        }
    }

    LaunchedEffect(host) {
        for (request in host.requestChannel) {
            val current = controller.snapshot().handoffRequest
            if (current?.interactionGeneration != request.interactionGeneration) continue
            enqueue(coordinator.start(request), request.interactionGeneration)
        }
    }

    LaunchedEffect(host, controller.handoffRequest.value) {
        if (controller.handoffRequest.value == null) {
            coordinator.cancelForStaleDisplay()
            host.publishHandoffSnapshot()
        }
    }

    LaunchedEffect(host, frame.key, controller.handoffRequest.value) {
        val request = controller.handoffRequest.value ?: return@LaunchedEffect
        if (frame.key.dataKey != request.sourceDataKey) {
            coordinator.cancelForStaleDisplay()
            host.publishHandoffSnapshot()
            return@LaunchedEffect
        }
        enqueue(coordinator.observeFrame(frame), request.interactionGeneration)
    }

    LaunchedEffect(host, frame.key, handoffSnapshot.phase, controller.handoffRequest.value) {
        val request = controller.handoffRequest.value ?: return@LaunchedEffect
        if (
            handoffSnapshot.phase != MediaGridMorphGridHandoffPhase.PositioningTarget &&
            handoffSnapshot.phase != MediaGridMorphGridHandoffPhase.RollingBack
        ) return@LaunchedEffect
        snapshotFlow {
            val layout = state.layoutInfo
            val target = coordinator.snapshot().resolvedTarget
            val phase = coordinator.snapshot().phase
            val targetTranslationX = request.finalCorrection.x.takeIf {
                phase == MediaGridMorphGridHandoffPhase.PositioningTarget
            } ?: 0f
            val targetTranslationY = request.finalCorrection.y.takeIf {
                phase == MediaGridMorphGridHandoffPhase.PositioningTarget
            } ?: 0f
            val visibleTarget = target?.let { resolved ->
                layout.visibleItemsInfo.firstNotNullOfOrNull { info ->
                    val key = info.key as? String ?: return@firstNotNullOfOrNull null
                    val assetId = frame.assetIdByItemKey[key] ?: return@firstNotNullOfOrNull null
                    if (assetId != resolved.assetId) return@firstNotNullOfOrNull null
                    MediaGridMorphVisibleItemGeometry(
                        assetId = assetId,
                        itemIndex = info.index,
                        rect = Rect(
                            left = info.offset.x.toFloat() + targetTranslationX,
                            top = (info.offset.y - layout.viewportStartOffset).toFloat() + targetTranslationY,
                            right = (info.offset.x + info.size.width).toFloat() + targetTranslationX,
                            bottom = (info.offset.y - layout.viewportStartOffset + info.size.height).toFloat() + targetTranslationY,
                        ),
                    )
                }
            }
            MediaGridMorphProductionLayoutSample(
                target = visibleTarget,
                viewportWidth = layout.viewportSize.width,
                viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
            )
        }.distinctUntilChanged().collect {
            observeCurrentHandoffLayout(request, frame)
        }
    }

    LaunchedEffect(host, handoffSnapshot.phase, controller.handoffRequest.value) {
        val request = controller.handoffRequest.value ?: return@LaunchedEffect
        if (handoffSnapshot.phase != MediaGridMorphGridHandoffPhase.ReadyToComplete) return@LaunchedEffect
        withFrameNanos { }
        enqueue(coordinator.nextFrame(), request.interactionGeneration)
    }

    LaunchedEffect(host) {
        for (envelope in host.commandChannel) {
            val request = coordinator.snapshot().request
            if (request?.interactionGeneration != envelope.generation) continue
            when (val command = envelope.command) {
                is MediaGridMorphGridHandoffCommand.ChangeColumnCount ->
                    latestColumnChange(command.columnCount)
                is MediaGridMorphGridHandoffCommand.RollbackColumnCount ->
                    latestColumnChange(command.columnCount)
                is MediaGridMorphGridHandoffCommand.ScrollToItem -> {
                    state.scrollToItem(command.itemIndex, command.scrollOffset)
                    withFrameNanos { }
                    coordinator.snapshot().request?.let { request ->
                        observeCurrentHandoffLayout(request, latestFrame)
                    }
                    if (BuildConfig.TEST_HARNESS) {
                        MediaGridMorphTestTrace.recordHandoffCommand(
                            MediaGridMorphHandoffCommandObservation(
                                generation = envelope.generation,
                                command = "ScrollToItem",
                                directLayoutReevaluated = true,
                            )
                        )
                    }
                }
                is MediaGridMorphGridHandoffCommand.ScrollBy -> {
                    state.scrollBy(command.pixels)
                    withFrameNanos { }
                    coordinator.snapshot().request?.let { request ->
                        observeCurrentHandoffLayout(request, latestFrame)
                    }
                    if (BuildConfig.TEST_HARNESS) {
                        MediaGridMorphTestTrace.recordHandoffCommand(
                            MediaGridMorphHandoffCommandObservation(
                                generation = envelope.generation,
                                command = "ScrollBy",
                                directLayoutReevaluated = true,
                            )
                        )
                    }
                }
                is MediaGridMorphGridHandoffCommand.Complete -> {
                    controller.completeHandoff(command.interactionGeneration)
                    checkpointHandledGeneration = command.interactionGeneration
                    withFrameNanos { }
                    if (coordinator.consumeFinalAnchorCheckpointPermission()) {
                        val key = latestSessionKey
                        val currentFrame = latestFrame
                        if (key != null && currentFrame != null) {
                            captureClassifiedMediaGridScrollAnchor(state, currentFrame.assetIdByItemKey)
                                ?.let { latestCheckpoint(key, it) }
                        }
                    }
                }
                is MediaGridMorphGridHandoffCommand.Cancel -> {
                    controller.cancelHandoff(command.interactionGeneration)
                    checkpointHandledGeneration = command.interactionGeneration
                    withFrameNanos { }
                    if (coordinator.consumeFinalAnchorCheckpointPermission()) {
                        val key = latestSessionKey
                        val currentFrame = latestFrame
                        if (key != null && currentFrame != null) {
                            captureClassifiedMediaGridScrollAnchor(state, currentFrame.assetIdByItemKey)
                                ?.let { latestCheckpoint(key, it) }
                        }
                    }
                }
            }
            host.publishHandoffSnapshot()
        }
    }

    if (activePlan != null) {
        MediaGridMorphCanvasLayer(
            plan = activePlan!!,
            preparedIndex = preparedIndex,
            progress = controller.progress,
            correction = controller.correction,
            mode = MediaGridMorphCanvasMode.TestVisible,
            modifier = modifier,
        )
    }
}

/**
 * Production handoff effects for the real LazyGrid. This function has no
 * visual output: the grid's unified draw surface remains the only renderer.
 */
@Composable
internal fun MediaGridMorphProductionHandoffEffects(
    host: MediaGridMorphProductionHostState,
    frame: MediaGridFrameData,
    sessionKey: MediaGridSessionKey?,
    identity: MediaGridMorphInteractionIdentity,
    state: LazyGridState,
    onColumnCountChange: (Int) -> Unit,
    onAnchorCheckpoint: (MediaGridSessionKey, ClassifiedMediaGridScrollAnchor) -> Unit,
    onCheckpointSuppressed: (Boolean) -> Unit,
) {
    val controller = host.controller
    val coordinator = host.coordinator
    val handoffSnapshot by host.handoffSnapshot
    val latestFrame by rememberUpdatedState(frame)
    val latestSessionKey by rememberUpdatedState(sessionKey)
    val latestColumnChange by rememberUpdatedState(onColumnCountChange)
    val latestCheckpoint by rememberUpdatedState(onAnchorCheckpoint)
    val latestSuppression by rememberUpdatedState(onCheckpointSuppressed)
    controller.updateIdentity(identity)

    val morphLocked = controller.interactionLocked.value || handoffSnapshot.suppressesUserScroll
    SideEffect { latestSuppression(morphLocked) }
    DisposableEffect(host) { onDispose { latestSuppression(false) } }

    fun publish(command: MediaGridMorphGridHandoffCommand?, generation: Long) {
        if (command != null) host.commandChannel.trySend(MediaGridMorphProductionCommand(generation, command))
        host.publishHandoffSnapshot()
    }

    fun checkpointIfAllowed() {
        if (!coordinator.consumeFinalAnchorCheckpointPermission()) return
        val key = latestSessionKey ?: return
        captureClassifiedMediaGridScrollAnchor(state, latestFrame.assetIdByItemKey)?.let { latestCheckpoint(key, it) }
    }

    suspend fun observeCurrentHandoffLayout(
        request: MediaGridMorphHandoffRequest,
        currentFrame: MediaGridFrameData,
    ) {
        val snapshot = coordinator.snapshot()
        if (
            snapshot.phase != MediaGridMorphGridHandoffPhase.PositioningTarget &&
            snapshot.phase != MediaGridMorphGridHandoffPhase.RollingBack
        ) return
        val layout = state.layoutInfo
        val targetTranslationX = request.finalCorrection.x.takeIf {
            snapshot.phase == MediaGridMorphGridHandoffPhase.PositioningTarget
        } ?: 0f
        val targetTranslationY = request.finalCorrection.y.takeIf {
            snapshot.phase == MediaGridMorphGridHandoffPhase.PositioningTarget
        } ?: 0f
        val visibleTarget = snapshot.resolvedTarget?.let { resolved ->
            layout.visibleItemsInfo.firstNotNullOfOrNull { info ->
                val key = info.key as? String ?: return@firstNotNullOfOrNull null
                val assetId = currentFrame.assetIdByItemKey[key] ?: return@firstNotNullOfOrNull null
                if (assetId != resolved.assetId) return@firstNotNullOfOrNull null
                MediaGridMorphVisibleItemGeometry(
                    assetId = assetId,
                    itemIndex = info.index,
                    rect = Rect(
                        left = info.offset.x.toFloat() + targetTranslationX,
                        top = (info.offset.y - layout.viewportStartOffset).toFloat() + targetTranslationY,
                        right = (info.offset.x + info.size.width).toFloat() + targetTranslationX,
                        bottom = (info.offset.y - layout.viewportStartOffset + info.size.height).toFloat() + targetTranslationY,
                    ),
                )
            }
        }
        val visibleRow = captureMediaGridMorphVisibleTargetRow(
            state = state,
            frame = currentFrame,
            targetOrdinal = request.targetFocalMediaOrdinal ?: request.targetAnchor.mediaOrdinal,
            targetRowIndex = request.targetAnchorRowIndex,
            exactTargetLayoutIndex = request.exactTargetLayoutIndex,
        )
        val visibleViewport = captureMediaGridMorphVisibleTargetViewport(
            state = state,
            frame = currentFrame,
            exactTargetLayoutIndex = request.exactTargetLayoutIndex,
            targetRowIndex = request.targetAnchorRowIndex,
        )
        publish(
            coordinator.observeLayout(
                frame = currentFrame,
                visibleTarget = visibleTarget,
                visibleTargetRow = visibleRow,
                visibleTargetViewport = visibleViewport,
                viewportWidth = layout.viewportSize.width,
                viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
            ),
            request.interactionGeneration,
        )
        if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.VerifyingTarget) {
            coordinator.underlyingTargetGridDrawn()
            controller.beginTargetReveal(request.interactionGeneration)
            host.publishHandoffSnapshot()
        }
    }

    LaunchedEffect(host) {
        for (request in host.requestChannel) {
            val current = controller.snapshot().handoffRequest
            if (current?.interactionGeneration != request.interactionGeneration) continue
            publish(coordinator.start(request), request.interactionGeneration)
        }
    }

    LaunchedEffect(host, frame.key, controller.handoffRequest.value) {
        val request = controller.handoffRequest.value
        if (request == null) {
            coordinator.cancelForStaleDisplay()
            host.publishHandoffSnapshot()
        } else if (frame.key.dataKey != request.sourceDataKey) {
            coordinator.cancelForStaleDisplay()
            host.publishHandoffSnapshot()
        } else {
            publish(coordinator.observeFrame(frame), request.interactionGeneration)
        }
    }

    LaunchedEffect(host, handoffSnapshot.phase, controller.handoffRequest.value, frame.key) {
        val request = controller.handoffRequest.value ?: return@LaunchedEffect
        if (
            handoffSnapshot.phase != MediaGridMorphGridHandoffPhase.PositioningTarget &&
            handoffSnapshot.phase != MediaGridMorphGridHandoffPhase.RollingBack
        ) return@LaunchedEffect
        snapshotFlow {
            val layout = state.layoutInfo
            val resolved = coordinator.snapshot().resolvedTarget
            val visibleTarget = resolved?.let { target ->
                layout.visibleItemsInfo.firstNotNullOfOrNull { info ->
                    val key = info.key as? String ?: return@firstNotNullOfOrNull null
                    val assetId = latestFrame.assetIdByItemKey[key] ?: return@firstNotNullOfOrNull null
                    if (assetId != target.assetId) return@firstNotNullOfOrNull null
                    MediaGridMorphVisibleItemGeometry(
                        assetId = assetId,
                        itemIndex = info.index,
                        rect = Rect(
                            info.offset.x.toFloat(),
                            (info.offset.y - layout.viewportStartOffset).toFloat(),
                            (info.offset.x + info.size.width).toFloat(),
                            (info.offset.y - layout.viewportStartOffset + info.size.height).toFloat(),
                        ),
                    )
                }
            }
            val visibleRow = captureMediaGridMorphVisibleTargetRow(
                state = state,
                frame = latestFrame,
                targetOrdinal = request.targetFocalMediaOrdinal ?: request.targetAnchor.mediaOrdinal,
                targetRowIndex = request.targetAnchorRowIndex,
                exactTargetLayoutIndex = request.exactTargetLayoutIndex,
            )
            val visibleViewport = captureMediaGridMorphVisibleTargetViewport(
                state = state,
                frame = latestFrame,
                exactTargetLayoutIndex = request.exactTargetLayoutIndex,
                targetRowIndex = request.targetAnchorRowIndex,
            )
            Triple(
                visibleTarget,
                visibleRow,
                layout.viewportSize.width to (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
            ).let { (targetGeometry, rowGeometry, viewportSize) ->
                HandoffLayoutSample(
                    target = targetGeometry,
                    row = rowGeometry,
                    viewport = visibleViewport,
                    viewportWidth = viewportSize.first,
                    viewportHeight = viewportSize.second,
                )
            }
        }.distinctUntilChanged().collect { sample ->
            val command = coordinator.observeLayout(
                frame = latestFrame,
                visibleTarget = sample.target,
                visibleTargetRow = sample.row,
                visibleTargetViewport = sample.viewport,
                viewportWidth = sample.viewportWidth,
                viewportHeight = sample.viewportHeight,
            )
            publish(command, request.interactionGeneration)
            if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.VerifyingTarget) {
                coordinator.underlyingTargetGridDrawn()
                controller.beginTargetReveal(request.interactionGeneration)
                host.publishHandoffSnapshot()
            }
        }
    }

    val settleGeneration = controller.settleSignal.value
    LaunchedEffect(host, settleGeneration) {
        if (settleGeneration == 0L) return@LaunchedEffect
        while (controller.isSettling(settleGeneration)) withFrameNanos(controller::advanceSettleFrame)
    }

    LaunchedEffect(host, controller.drawMode.value, controller.snapshot().interactionGeneration) {
        val snapshot = controller.snapshot()
        when (snapshot.drawMode) {
            MediaGridMorphDrawMode.RevealCurrent -> {
                val sourceViewportAnchor = snapshot.claimBundle?.sourceViewportAnchor
                if (sourceViewportAnchor != null && !state.matches(sourceViewportAnchor)) {
                    controller.cancelPointers()
                    host.publishHandoffSnapshot()
                    return@LaunchedEffect
                }
                withFrameNanos { }
                if (sourceViewportAnchor == null || state.matches(sourceViewportAnchor)) {
                    controller.acknowledgeCurrentReveal(snapshot.interactionGeneration)
                } else {
                    controller.cancelPointers()
                }
            }
            MediaGridMorphDrawMode.RevealTarget -> {
                withFrameNanos { }
                controller.acknowledgeTargetReveal(snapshot.interactionGeneration)
                coordinator.nextFrame()
                checkpointIfAllowed()
                host.publishHandoffSnapshot()
            }
            else -> Unit
        }
    }

    LaunchedEffect(host) {
        for (envelope in host.commandChannel) {
            val request = coordinator.snapshot().request
            if (request?.interactionGeneration != envelope.generation) continue
            when (val command = envelope.command) {
                is MediaGridMorphGridHandoffCommand.ChangeColumnCount -> latestColumnChange(command.columnCount)
                is MediaGridMorphGridHandoffCommand.RollbackColumnCount -> {
                    if (BuildConfig.TEST_HARNESS) {
                        MediaGridMorphTestTrace.recordRollbackColumnCountCommand(
                            coordinator.snapshot().failureReason?.name,
                            coordinator.snapshot().failureDetail,
                        )
                    }
                    latestColumnChange(command.columnCount)
                }
                is MediaGridMorphGridHandoffCommand.ScrollToItem -> {
                    state.scrollToItem(command.itemIndex, command.scrollOffset)
                    withFrameNanos { }
                    coordinator.snapshot().request?.let { request ->
                        observeCurrentHandoffLayout(request, latestFrame)
                    }
                    if (BuildConfig.TEST_HARNESS) {
                        MediaGridMorphTestTrace.recordHandoffCommand(
                            MediaGridMorphHandoffCommandObservation(
                                generation = envelope.generation,
                                command = "ScrollToItem",
                                directLayoutReevaluated = true,
                            )
                        )
                    }
                }
                is MediaGridMorphGridHandoffCommand.ScrollBy -> {
                    state.scrollBy(command.pixels)
                    withFrameNanos { }
                    coordinator.snapshot().request?.let { request ->
                        observeCurrentHandoffLayout(request, latestFrame)
                    }
                    if (BuildConfig.TEST_HARNESS) {
                        MediaGridMorphTestTrace.recordHandoffCommand(
                            MediaGridMorphHandoffCommandObservation(
                                generation = envelope.generation,
                                command = "ScrollBy",
                                directLayoutReevaluated = true,
                            )
                        )
                    }
                }
                is MediaGridMorphGridHandoffCommand.Complete -> checkpointIfAllowed()
                is MediaGridMorphGridHandoffCommand.Cancel -> {
                    controller.cancelHandoff(command.interactionGeneration)
                    checkpointIfAllowed()
                }
            }
            host.publishHandoffSnapshot()
        }
    }
}

private fun LazyGridState.matches(anchor: MediaGridMorphSourceViewportAnchor): Boolean =
    firstVisibleItemIndex == anchor.firstVisibleItemIndex &&
        firstVisibleItemScrollOffset == anchor.firstVisibleItemScrollOffset

private fun captureMediaGridMorphVisibleTargetRow(
    state: LazyGridState,
    frame: MediaGridFrameData,
    targetOrdinal: Int,
    targetRowIndex: Int?,
    exactTargetLayoutIndex: MediaGridMorphExactTargetLayoutIndex? = null,
): MediaGridMorphVisibleRowGeometry? {
    val viewport = captureMediaGridMorphVisibleTargetViewport(
        state = state,
        frame = frame,
        exactTargetLayoutIndex = exactTargetLayoutIndex,
        targetRowIndex = targetRowIndex,
    )
    return viewport.rows.firstOrNull { targetOrdinal in it.mediaOrdinals }
}

private data class HandoffLayoutSample(
    val target: MediaGridMorphVisibleItemGeometry?,
    val row: MediaGridMorphVisibleRowGeometry?,
    val viewport: MediaGridMorphVisibleViewportGeometry?,
    val viewportWidth: Int,
    val viewportHeight: Int,
)

private fun captureMediaGridMorphVisibleTargetViewport(
    state: LazyGridState,
    frame: MediaGridFrameData,
    exactTargetLayoutIndex: MediaGridMorphExactTargetLayoutIndex?,
    targetRowIndex: Int?,
): MediaGridMorphVisibleViewportGeometry {
    val layout = state.layoutInfo
    val viewportWidth = layout.viewportSize.width.toFloat()
    val viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0).toFloat()
    val viewport = Rect(0f, 0f, viewportWidth, viewportHeight)
    fun localRect(info: androidx.compose.foundation.lazy.grid.LazyGridItemInfo): Rect = Rect(
        left = info.offset.x.toFloat(),
        top = (info.offset.y - layout.viewportStartOffset).toFloat(),
        right = (info.offset.x + info.size.width).toFloat(),
        bottom = (info.offset.y - layout.viewportStartOffset + info.size.height).toFloat(),
    )
    fun visible(rect: Rect): Boolean =
        rect.right > viewport.left && rect.left < viewport.right &&
            rect.bottom > viewport.top && rect.top < viewport.bottom

    val media = layout.visibleItemsInfo.mapNotNull { info ->
        val ordinal = frame.ordinalIndex.mediaOrdinalByItemIndex.getOrNull(info.index) ?: return@mapNotNull null
        if (ordinal < 0) return@mapNotNull null
        val rect = localRect(info)
        if (!visible(rect)) return@mapNotNull null
        MediaGridMorphVisibleCellGeometry(ordinal, info.index, rect)
    }
    val rows = media.groupBy { cell ->
        exactTargetLayoutIndex?.rowIdForMediaOrdinal(cell.mediaOrdinal)
            ?: cell.rect.top.roundToInt()
    }.toList()
        .sortedBy { (_, cells) -> cells.minOf { it.rect.top } }
        .map { (rowId, cells) ->
            val ordered = cells.sortedBy { it.rect.left }
            MediaGridMorphVisibleRowGeometry(
                rowIndex = rowId,
                rowTop = ordered.minOf { it.rect.top },
                cellWidth = ordered.first().rect.width,
                cellHeight = ordered.first().rect.height,
                mediaOrdinals = ordered.map { it.mediaOrdinal },
                cells = ordered,
            )
        }
    val headers = layout.visibleItemsInfo.mapNotNull { info ->
        val header = frame.items.getOrNull(info.index) as? MediaGridHeaderItem ?: return@mapNotNull null
        val rect = localRect(info)
        if (!visible(rect)) return@mapNotNull null
        MediaGridMorphVisibleHeaderGeometry(info.index, header.key, header.label, rect)
    }.sortedBy { it.itemIndex }
    val rowsWithHeaders = rows.map { row ->
        val firstItemIndex = row.cells.minOf { it.itemIndex }
        val header = headers.lastOrNull { it.itemIndex < firstItemIndex }
        row.copy(headerKey = header?.key, headerTitle = header?.title)
    }
    return MediaGridMorphVisibleViewportGeometry(viewport, rowsWithHeaders, headers)
}
