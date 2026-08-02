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

internal class MediaGridMorphProductionHostState(
    val retainedImageStore: MediaGridRetainedImageStore,
) {
    val ownerToken: Long = retainedImageStore.newOwnerToken()
    val requestChannel = Channel<MediaGridMorphHandoffRequest>(Channel.UNLIMITED)
    val commandChannel = Channel<MediaGridMorphProductionCommand>(Channel.UNLIMITED)
    val drawAckChannel = Channel<MediaGridMorphDrawAck>(Channel.UNLIMITED)
    val drawAckDispatcher = MediaGridMorphDrawAckDispatcher { ack -> drawAckChannel.trySend(ack).isSuccess }
    val coordinator = MediaGridMorphGridHandoffCoordinator()
    val controller = MediaGridMorphInteractionController { request -> requestChannel.trySend(request) }.also { controller ->
        controller.setProtectionCallbacks(
            onProtect = { assetIds ->
            retainedImageStore.updateProtection(
                ownerToken = ownerToken,
                visibleAssetIds = LongArray(0),
                activeAssetIds = assetIds,
            )
            },
            onRelease = {
            retainedImageStore.updateProtection(
                ownerToken = ownerToken,
                visibleAssetIds = LongArray(0),
                activeAssetIds = LongArray(0),
            )
            },
        )
    }
    val handoffSnapshot: State<MediaGridMorphGridHandoffSnapshot>
        get() = _handoffSnapshot

    private val _handoffSnapshot = mutableStateOf(coordinator.snapshot())
    private var disposed = false

    fun publishHandoffSnapshot() {
        if (!disposed) _handoffSnapshot.value = coordinator.snapshot()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        controller.cancelPointers()
        retainedImageStore.removeOwner(ownerToken)
        requestChannel.close()
        commandChannel.close()
        drawAckChannel.close()
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
    val frameKey: MediaGridRenderKey,
    val target: MediaGridMorphVisibleItemGeometry?,
    val visibleRow: MediaGridMorphVisibleRowGeometry?,
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
        val visibleTargetRow = captureMediaGridMorphVisibleTargetRow(
            state = state,
            frame = currentFrame,
            targetOrdinal = request.targetFocalMediaOrdinal ?: request.targetAnchor.mediaOrdinal,
            targetRowIndex = request.targetAnchorRowIndex,
        )
        enqueue(
            coordinator.observeLayout(
                frame = currentFrame,
                visibleTarget = target,
                visibleTargetRow = visibleTargetRow,
                viewportWidth = layout.viewportSize.width,
                viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
            ),
            request.interactionGeneration,
        )
        if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.VerifyingTarget) {
            controller.beginTargetReveal(request.interactionGeneration)
            coordinator.beginTargetReveal(request.interactionGeneration)
            host.publishHandoffSnapshot()
        } else if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.RevealingCurrent) {
            controller.beginCurrentReveal(request.interactionGeneration)
            host.publishHandoffSnapshot()
        } else if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.Cancelled) {
            controller.cancelHandoff(request.interactionGeneration)
            host.publishHandoffSnapshot()
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
                frameKey = frame.key,
                target = visibleTarget,
                visibleRow = captureMediaGridMorphVisibleTargetRow(
                    state = state,
                    frame = frame,
                    targetOrdinal = request.targetFocalMediaOrdinal ?: request.targetAnchor.mediaOrdinal,
                    targetRowIndex = request.targetAnchorRowIndex,
                ),
                viewportWidth = layout.viewportSize.width,
                viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
            )
        }.distinctUntilChanged().collect {
            observeCurrentHandoffLayout(request, frame)
        }
    }

    LaunchedEffect(host) {
        for (ack in host.drawAckChannel) {
            val snapshot = controller.snapshot()
            if (snapshot.interactionGeneration != ack.generation) continue
            when (ack.mode) {
                MediaGridSingleSurfaceMode.RevealTarget -> {
                    if (
                        snapshot.phase != MediaGridMorphPhase.RevealingTarget ||
                        coordinator.snapshot().phase != MediaGridMorphGridHandoffPhase.RevealingTarget
                    ) continue
                    controller.acknowledgeTargetReveal(ack.generation)
                    coordinator.completeAfterReveal(ack.generation, target = true)
                    host.publishHandoffSnapshot()
                }
                MediaGridSingleSurfaceMode.RevealCurrent -> {
                    if (
                        snapshot.phase != MediaGridMorphPhase.RevealingCurrent ||
                        coordinator.snapshot().phase != MediaGridMorphGridHandoffPhase.RevealingCurrent
                    ) continue
                    controller.acknowledgeCurrentReveal(ack.generation)
                    coordinator.completeAfterReveal(ack.generation, target = false)
                    host.publishHandoffSnapshot()
                }
                else -> Unit
            }
        }
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
                }
                is MediaGridMorphGridHandoffCommand.ScrollBy -> {
                    state.scrollBy(command.pixels)
                }
                is MediaGridMorphGridHandoffCommand.Cancel -> {
                    controller.cancelHandoff(command.interactionGeneration)
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
            onDrawn = {
                val snapshot = controller.snapshot()
                val surfaceMode = when (snapshot.drawMode) {
                    MediaGridMorphDrawMode.RevealCurrent -> MediaGridSingleSurfaceMode.RevealCurrent
                    MediaGridMorphDrawMode.RevealTarget -> MediaGridSingleSurfaceMode.RevealTarget
                    else -> MediaGridSingleSurfaceMode.Normal
                }
                host.drawAckDispatcher.dispatch(
                    generation = snapshot.interactionGeneration,
                    mode = surfaceMode,
                    frameNumber = MediaGridMorphTestTrace.currentFrameNumber(),
                )
            },
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
    var checkpointHandledGeneration by remember(host) { mutableStateOf<Long?>(null) }
    controller.updateIdentity(identity)

    val morphLocked = controller.interactionLocked.value || handoffSnapshot.suppressesUserScroll
    SideEffect { latestSuppression(morphLocked) }
    DisposableEffect(host) { onDispose { latestSuppression(false) } }

    fun publish(command: MediaGridMorphGridHandoffCommand?, generation: Long) {
        if (command != null) {
            val event = when (command) {
                is MediaGridMorphGridHandoffCommand.ChangeColumnCount,
                is MediaGridMorphGridHandoffCommand.RollbackColumnCount,
                    -> MediaGridMorphHandoffTraceEvent.ColumnCommand
                is MediaGridMorphGridHandoffCommand.ScrollToItem,
                is MediaGridMorphGridHandoffCommand.ScrollBy,
                    -> MediaGridMorphHandoffTraceEvent.PositionCommand
                is MediaGridMorphGridHandoffCommand.Cancel -> null
            }
            if (event != null) {
                MediaGridMorphTestTrace.recordHandoffTrace(
                    event = event,
                    generation = generation,
                    handoffPhase = coordinator.snapshot().phase,
                    interactionPhase = controller.snapshot().phase,
                )
            }
            host.commandChannel.trySend(MediaGridMorphProductionCommand(generation, command))
        }
        host.publishHandoffSnapshot()
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
            val staleGeneration = coordinator.snapshot().request?.interactionGeneration
            if (staleGeneration != null) controller.cancelHandoff(staleGeneration)
            host.publishHandoffSnapshot()
        } else if (frame.key.dataKey != request.sourceDataKey) {
            coordinator.cancelForStaleDisplay()
            controller.cancelHandoff(request.interactionGeneration)
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
            val targetTranslationX = request.finalCorrection.x.takeIf {
                coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.PositioningTarget
            } ?: 0f
            val targetTranslationY = request.finalCorrection.y.takeIf {
                coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.PositioningTarget
            } ?: 0f
            val visibleTarget = resolved?.let { target ->
                layout.visibleItemsInfo.firstNotNullOfOrNull { info ->
                    val key = info.key as? String ?: return@firstNotNullOfOrNull null
                    val assetId = latestFrame.assetIdByItemKey[key] ?: return@firstNotNullOfOrNull null
                    if (assetId != target.assetId) return@firstNotNullOfOrNull null
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
                frame = latestFrame,
                targetOrdinal = request.targetFocalMediaOrdinal ?: request.targetAnchor.mediaOrdinal,
                targetRowIndex = request.targetAnchorRowIndex,
            )
            MediaGridMorphProductionLayoutSample(
                frameKey = latestFrame.key,
                target = visibleTarget,
                visibleRow = visibleRow,
                viewportWidth = layout.viewportSize.width,
                viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
            )
        }.distinctUntilChanged().collect { sample ->
            val generation = request.interactionGeneration
            MediaGridMorphTestTrace.recordHandoffTrace(
                event = MediaGridMorphHandoffTraceEvent.TargetLayout,
                generation = generation,
                handoffPhase = coordinator.snapshot().phase,
                interactionPhase = controller.snapshot().phase,
            )
            val command = coordinator.observeLayout(
                frame = latestFrame,
                visibleTarget = sample.target,
                visibleTargetRow = sample.visibleRow,
                viewportWidth = sample.viewportWidth,
                viewportHeight = sample.viewportHeight,
            )
            publish(command, generation)
            if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.VerifyingTarget) {
                MediaGridMorphTestTrace.recordHandoffTrace(
                    event = MediaGridMorphHandoffTraceEvent.AlignedLayout,
                    generation = generation,
                    handoffPhase = coordinator.snapshot().phase,
                    interactionPhase = controller.snapshot().phase,
                )
                if (controller.beginTargetReveal(generation)) {
                    coordinator.beginTargetReveal(generation)
                }
                host.publishHandoffSnapshot()
            } else if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.RevealingCurrent) {
                if (controller.beginCurrentReveal(generation)) host.publishHandoffSnapshot()
            } else if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.Cancelled) {
                controller.cancelHandoff(generation)
                host.publishHandoffSnapshot()
            }
        }
    }

    val settleGeneration = controller.settleSignal.value
    LaunchedEffect(host, settleGeneration) {
        if (settleGeneration == 0L) return@LaunchedEffect
        while (controller.isSettling(settleGeneration)) withFrameNanos(controller::advanceSettleFrame)
    }

    LaunchedEffect(host) {
        for (ack in host.drawAckChannel) {
            val snapshot = controller.snapshot()
            if (snapshot.interactionGeneration != ack.generation) continue
            when (ack.mode) {
                MediaGridSingleSurfaceMode.RevealTarget -> {
                    if (
                        snapshot.phase != MediaGridMorphPhase.RevealingTarget ||
                        coordinator.snapshot().phase != MediaGridMorphGridHandoffPhase.RevealingTarget
                    ) continue
                    MediaGridMorphTestTrace.recordHandoffTrace(
                        event = MediaGridMorphHandoffTraceEvent.ActualDraw,
                        generation = ack.generation,
                        handoffPhase = coordinator.snapshot().phase,
                        interactionPhase = snapshot.phase,
                        frameNumber = ack.frameNumber,
                    )
                    controller.acknowledgeTargetReveal(ack.generation)
                    if (coordinator.completeAfterReveal(ack.generation, target = true)) {
                        MediaGridMorphTestTrace.recordHandoffTrace(
                            event = MediaGridMorphHandoffTraceEvent.Unlock,
                            generation = ack.generation,
                            handoffPhase = coordinator.snapshot().phase,
                            interactionPhase = controller.snapshot().phase,
                            frameNumber = ack.frameNumber,
                        )
                        host.publishHandoffSnapshot()
                    }
                }
                MediaGridSingleSurfaceMode.RevealCurrent -> {
                    if (
                        snapshot.phase != MediaGridMorphPhase.RevealingCurrent ||
                        coordinator.snapshot().phase != MediaGridMorphGridHandoffPhase.RevealingCurrent
                    ) continue
                    MediaGridMorphTestTrace.recordHandoffTrace(
                        event = MediaGridMorphHandoffTraceEvent.ActualDraw,
                        generation = ack.generation,
                        handoffPhase = coordinator.snapshot().phase,
                        interactionPhase = snapshot.phase,
                        frameNumber = ack.frameNumber,
                    )
                    controller.acknowledgeCurrentReveal(ack.generation)
                    if (coordinator.completeAfterReveal(ack.generation, target = false)) {
                        MediaGridMorphTestTrace.recordHandoffTrace(
                            event = MediaGridMorphHandoffTraceEvent.Unlock,
                            generation = ack.generation,
                            handoffPhase = coordinator.snapshot().phase,
                            interactionPhase = controller.snapshot().phase,
                            frameNumber = ack.frameNumber,
                        )
                        host.publishHandoffSnapshot()
                    }
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(host, handoffSnapshot.phase, handoffSnapshot.request?.interactionGeneration) {
        if (
            handoffSnapshot.phase != MediaGridMorphGridHandoffPhase.Completed &&
            handoffSnapshot.phase != MediaGridMorphGridHandoffPhase.Cancelled
        ) return@LaunchedEffect
        val request = coordinator.snapshot().request ?: return@LaunchedEffect
        if (checkpointHandledGeneration == request.interactionGeneration) return@LaunchedEffect
        if (!coordinator.consumeFinalAnchorCheckpointPermission()) return@LaunchedEffect
        checkpointHandledGeneration = request.interactionGeneration
        MediaGridMorphTestTrace.recordHandoffTrace(
            event = MediaGridMorphHandoffTraceEvent.Checkpoint,
            generation = request.interactionGeneration,
            handoffPhase = coordinator.snapshot().phase,
            interactionPhase = controller.snapshot().phase,
        )
        val key = latestSessionKey ?: return@LaunchedEffect
        captureClassifiedMediaGridScrollAnchor(state, latestFrame.assetIdByItemKey)?.let { latestCheckpoint(key, it) }
    }

    LaunchedEffect(host) {
        for (envelope in host.commandChannel) {
            val request = coordinator.snapshot().request
            if (request?.interactionGeneration != envelope.generation) continue
            when (val command = envelope.command) {
                is MediaGridMorphGridHandoffCommand.ChangeColumnCount -> latestColumnChange(command.columnCount)
                is MediaGridMorphGridHandoffCommand.RollbackColumnCount -> latestColumnChange(command.columnCount)
                is MediaGridMorphGridHandoffCommand.ScrollToItem -> {
                    state.scrollToItem(command.itemIndex, command.scrollOffset)
                }
                is MediaGridMorphGridHandoffCommand.ScrollBy -> {
                    state.scrollBy(command.pixels)
                }
                is MediaGridMorphGridHandoffCommand.Cancel -> {
                    controller.cancelHandoff(command.interactionGeneration)
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
): MediaGridMorphVisibleRowGeometry? {
    val layout = state.layoutInfo
    val visibleItems = layout.visibleItemsInfo
    var targetRowTop = Float.NaN
    var targetFound = false
    var index = 0
    while (index < visibleItems.size) {
        val info = visibleItems[index]
        val ordinal = frame.ordinalIndex.mediaOrdinalByItemIndex.getOrNull(info.index) ?: -1
        if (ordinal == targetOrdinal) {
            targetRowTop = info.offset.y.toFloat()
            targetFound = true
            break
        }
        index++
    }
    if (!targetFound) return null

    val rowOrdinals = IntArray(visibleItems.size)
    val rowX = IntArray(visibleItems.size)
    var rowCount = 0
    var rowTop = Float.POSITIVE_INFINITY
    var firstIndex = Int.MAX_VALUE
    var cellWidth = 0f
    var cellHeight = 0f
    index = 0
    while (index < visibleItems.size) {
        val info = visibleItems[index]
        val ordinal = frame.ordinalIndex.mediaOrdinalByItemIndex.getOrNull(info.index) ?: -1
        if (ordinal >= 0 && kotlin.math.abs(info.offset.y.toFloat() - targetRowTop) <= 1f) {
            var insertAt = rowCount
            while (insertAt > 0 && rowX[insertAt - 1] > info.offset.x) {
                rowOrdinals[insertAt] = rowOrdinals[insertAt - 1]
                rowX[insertAt] = rowX[insertAt - 1]
                insertAt--
            }
            rowOrdinals[insertAt] = ordinal
            rowX[insertAt] = info.offset.x
            rowCount++
            rowTop = minOf(rowTop, info.offset.y.toFloat())
            firstIndex = minOf(firstIndex, info.index)
            if (cellWidth == 0f) cellWidth = info.size.width.toFloat()
            if (cellHeight == 0f) cellHeight = info.size.height.toFloat()
        }
        index++
    }
    if (rowCount == 0) return null

    var headerKey: String? = null
    var headerTitle: String? = null
    index = visibleItems.size - 1
    while (index >= 0) {
        val info = visibleItems[index]
        if (info.index < firstIndex) {
            val item = frame.items.getOrNull(info.index)
            if (item is MediaGridHeaderItem) {
                headerKey = item.key
                headerTitle = item.label
                break
            }
        }
        index--
    }
    return MediaGridMorphVisibleRowGeometry(
        rowIndex = targetRowIndex ?: rowTop.toInt(),
        rowTop = rowTop,
        cellWidth = cellWidth,
        cellHeight = cellHeight,
        mediaOrdinals = rowOrdinals.copyOf(rowCount),
        headerKey = headerKey,
        headerTitle = headerTitle,
    )
}
