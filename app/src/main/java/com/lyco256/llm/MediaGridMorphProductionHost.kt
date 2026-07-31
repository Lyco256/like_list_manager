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
    val coordinator = MediaGridMorphGridHandoffCoordinator()
    val controller = MediaGridMorphInteractionController { request ->
        requestChannel.trySend(request)
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

@Composable
internal fun MediaGridMorphProductionHost(
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

    if (activePlan != null) {
        val protectedAssetIds = remember(activePlan) {
            activePlan!!.slots.asSequence()
                .flatMap { sequenceOf(it.startContent.assetIdOrNull(), it.endContent.assetIdOrNull()) }
                .filterNotNull()
                .distinct()
                .toList()
                .toLongArray()
        }
        DisposableEffect(host, activePlan) {
            host.retainedImageStore.updateProtection(
                ownerToken = host.ownerToken,
                visibleAssetIds = LongArray(0),
                activeAssetIds = protectedAssetIds,
            )
            onDispose { host.retainedImageStore.removeOwner(host.ownerToken) }
        }
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
                }
                is MediaGridMorphGridHandoffCommand.ScrollBy -> {
                    state.scrollBy(command.pixels)
                    withFrameNanos { }
                    coordinator.snapshot().request?.let { request ->
                        observeCurrentHandoffLayout(request, latestFrame)
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
            mode = MediaGridMorphCanvasMode.ProductionVisible,
            modifier = modifier,
        )
    }
}
