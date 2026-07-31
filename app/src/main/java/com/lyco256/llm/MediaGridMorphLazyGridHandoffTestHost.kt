package com.lyco256.llm

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel

/**
 * TEST_HARNESS-only one-grid host for the real LazyVerticalGrid handoff.
 * Production ClassifiedMediaGridContent does not call this TEST_HARNESS host.
 */
@Composable
internal fun MediaGridMorphLazyGridHandoffTestHost(
    sourceFrame: MediaGridFrameData,
    targetFrame: MediaGridFrameData,
    controller: MediaGridMorphInteractionController,
    preparedPairsSnapshot: () -> Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    targetFrameDelayFrames: Int = 0,
    completionAllowed: Boolean = true,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    onSnapshot: (MediaGridMorphGridHandoffSnapshot) -> Unit = {},
    onCommand: (MediaGridMorphGridHandoffCommand) -> Unit = {},
    onVisibleTargetGeometry: (MediaGridMorphVisibleItemGeometry) -> Unit = {},
    onRenderModelBuilt: (() -> Unit)? = null,
    onImageResolved: ((Long) -> Unit)? = null,
    onUserScrollEnabledChanged: ((Boolean) -> Unit)? = null,
) {
    check(BuildConfig.TEST_HARNESS) {
        "MediaGridMorphLazyGridHandoffTestHost is restricted to TEST_HARNESS"
    }
    val initialPair = remember(sourceFrame.key) {
        preparedPairsSnapshot().values.first { it.frameKey == sourceFrame.key }
    }
    val density = LocalDensity.current
    val coordinator = remember { MediaGridMorphGridHandoffCoordinator() }
    val commands = remember { Channel<MediaGridMorphGridHandoffCommand>(Channel.UNLIMITED) }
    var columnCount by remember { mutableIntStateOf(sourceFrame.key.columnCount) }
    var displayedFrame by remember(sourceFrame) { mutableStateOf(sourceFrame) }
    var handoffSnapshot by remember { mutableStateOf(coordinator.snapshot()) }
    val request = controller.handoffRequest.value
    val userScrollEnabled = !handoffSnapshot.suppressesUserScroll
    val handoffVisualTranslationX = handoffSnapshot.request
        ?.finalCorrection
        ?.x
        ?.takeIf {
            handoffSnapshot.phase == MediaGridMorphGridHandoffPhase.PositioningTarget ||
                handoffSnapshot.phase == MediaGridMorphGridHandoffPhase.VerifyingTarget ||
                handoffSnapshot.phase == MediaGridMorphGridHandoffPhase.ReadyToComplete ||
                handoffSnapshot.phase == MediaGridMorphGridHandoffPhase.Idle
        }
        ?: 0f
    val handoffVisualTranslationY = handoffSnapshot.request
        ?.finalCorrection
        ?.y
        ?.takeIf {
            handoffSnapshot.phase == MediaGridMorphGridHandoffPhase.PositioningTarget ||
                handoffSnapshot.phase == MediaGridMorphGridHandoffPhase.VerifyingTarget ||
                handoffSnapshot.phase == MediaGridMorphGridHandoffPhase.ReadyToComplete ||
                handoffSnapshot.phase == MediaGridMorphGridHandoffPhase.Idle
        }
        ?: 0f

    SideEffect {
        onUserScrollEnabledChanged?.invoke(userScrollEnabled)
    }

    fun publishSnapshot() {
        handoffSnapshot = coordinator.snapshot()
        onSnapshot(handoffSnapshot)
    }

    fun enqueue(command: MediaGridMorphGridHandoffCommand?) {
        if (command != null) {
            onCommand(command)
            commands.trySend(command)
        }
        publishSnapshot()
    }

    LaunchedEffect(request) {
        if (request != null) {
            enqueue(coordinator.start(request))
        } else {
            coordinator.cancelForStaleDisplay()
            publishSnapshot()
        }
    }

    LaunchedEffect(columnCount, request) {
        val active = request ?: return@LaunchedEffect
        when (columnCount) {
            active.toColumnCount -> {
                repeat(targetFrameDelayFrames.coerceAtLeast(0)) { withFrameNanos { } }
                displayedFrame = targetFrame
            }
            active.fromColumnCount -> displayedFrame = sourceFrame
        }
    }

    fun visibleTargetGeometry(): MediaGridMorphVisibleItemGeometry? {
        val snapshot = coordinator.snapshot()
        val target = snapshot.resolvedTarget ?: return null
        val targetTranslationX = snapshot.request
            ?.finalCorrection
            ?.x
            ?.takeIf { snapshot.phase == MediaGridMorphGridHandoffPhase.PositioningTarget }
            ?: 0f
        val targetTranslationY = snapshot.request
            ?.finalCorrection
            ?.y
            ?.takeIf { snapshot.phase == MediaGridMorphGridHandoffPhase.PositioningTarget }
            ?: 0f
        val layout = state.layoutInfo
        for (info in layout.visibleItemsInfo) {
            val key = info.key as? String ?: continue
            val assetId = displayedFrame.assetIdByItemKey[key] ?: continue
            if (assetId != target.assetId) continue
            return MediaGridMorphVisibleItemGeometry(
                    assetId = assetId,
                    itemIndex = info.index,
                    rect = Rect(
                        left = info.offset.x.toFloat() + targetTranslationX,
                        top = (info.offset.y - layout.viewportStartOffset).toFloat() + targetTranslationY,
                        right = (info.offset.x + info.size.width).toFloat() + targetTranslationX,
                        bottom = (info.offset.y - layout.viewportStartOffset + info.size.height).toFloat() + targetTranslationY,
                ),
            ).also(onVisibleTargetGeometry)
        }
        return null
    }

    suspend fun observeCurrentLayout() {
        if (
            coordinator.snapshot().phase != MediaGridMorphGridHandoffPhase.PositioningTarget &&
            coordinator.snapshot().phase != MediaGridMorphGridHandoffPhase.RollingBack
        ) return
        val layout = state.layoutInfo
        enqueue(
            coordinator.observeLayout(
                frame = displayedFrame,
                visibleTarget = visibleTargetGeometry(),
                viewportWidth = layout.viewportSize.width,
                viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset)
                    .coerceAtLeast(0),
            ),
        )
        if (coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.VerifyingTarget) {
            withFrameNanos { }
            coordinator.underlyingTargetGridDrawn()
            publishSnapshot()
        }
    }

    LaunchedEffect(handoffSnapshot.phase, completionAllowed) {
        if (
            completionAllowed &&
            coordinator.snapshot().phase == MediaGridMorphGridHandoffPhase.ReadyToComplete
        ) {
            withFrameNanos { }
            enqueue(coordinator.nextFrame())
        }
    }

    LaunchedEffect(displayedFrame.key, request) {
        if (request == null) return@LaunchedEffect
        enqueue(coordinator.observeFrame(displayedFrame))
        withFrameNanos { }
        observeCurrentLayout()
    }

    LaunchedEffect(commands, state) {
        for (command in commands) {
            when (command) {
                is MediaGridMorphGridHandoffCommand.ChangeColumnCount ->
                    columnCount = command.columnCount
                is MediaGridMorphGridHandoffCommand.RollbackColumnCount ->
                    columnCount = command.columnCount
                is MediaGridMorphGridHandoffCommand.ScrollToItem -> {
                    state.scrollToItem(command.itemIndex, command.scrollOffset)
                    withFrameNanos { }
                    observeCurrentLayout()
                }
                is MediaGridMorphGridHandoffCommand.ScrollBy -> {
                    state.scrollBy(command.pixels)
                    withFrameNanos { }
                    observeCurrentLayout()
                }
                is MediaGridMorphGridHandoffCommand.Complete ->
                    controller.completeHandoff(command.interactionGeneration)
                is MediaGridMorphGridHandoffCommand.Cancel ->
                    controller.cancelHandoff(command.interactionGeneration)
            }
            publishSnapshot()
        }
    }

    val interactionIdentity = if (request == null) {
        MediaGridMorphInteractionIdentity(
            sourceRevision = sourceFrame.key.dataKey.sourceRevision,
            frameKey = sourceFrame.key,
            currentColumnCount = sourceFrame.key.columnCount,
            viewportSignature = initialPair.viewportSignature,
        )
    } else {
        val reportedFrameKey = when {
            displayedFrame.key == request.expectedTargetFrameKey &&
                columnCount == request.toColumnCount -> request.expectedTargetFrameKey
            else -> request.sourceFrameKey
        }
        MediaGridMorphInteractionIdentity(
            sourceRevision = reportedFrameKey.dataKey.sourceRevision,
            frameKey = reportedFrameKey,
            currentColumnCount = columnCount,
            viewportSignature = initialPair.viewportSignature.copy(
                renderKey = reportedFrameKey,
                columnCount = columnCount,
            ),
        )
    }

    Box(modifier.fillMaxSize().testTag("media_grid_handoff_host")) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = state,
            userScrollEnabled = userScrollEnabled,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (handoffVisualTranslationX != 0f) {
                        Modifier.graphicsLayer {
                            translationX = handoffVisualTranslationX
                            translationY = handoffVisualTranslationY
                        }
                    } else if (handoffVisualTranslationY != 0f) {
                        Modifier.graphicsLayer { translationY = handoffVisualTranslationY }
                    } else {
                        Modifier
                    },
                )
                .testTag("media_grid_handoff_lazy_grid"),
        ) {
            items(
                displayedFrame.items,
                key = { it.key },
                contentType = { if (it is MediaGridHeaderItem) "Header" else "MediaCell" },
                span = {
                    when (it) {
                        is MediaGridHeaderItem -> GridItemSpan(maxLineSpan)
                        is MediaGridCellItem -> GridItemSpan(1)
                    }
                },
            ) { item ->
                when (item) {
                    is MediaGridHeaderItem -> Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(
                                with(density) {
                                    val layout = if (columnCount == initialPair.fromColumnCount) {
                                        initialPair.startLayout
                                    } else {
                                        initialPair.targetLayout
                                    }
                                    (layout.headers.firstOrNull()?.rect?.height ?: 40f).toDp()
                                },
                            )
                            .testTag(item.key),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Box(contentAlignment = Alignment.CenterStart) {
                            Text(
                                text = item.label,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    is MediaGridCellItem -> Box(
                        Modifier
                            .aspectRatio(1f)
                            .background(testAssetColor(item.entry.assetId))
                            .testTag("handoff_asset_${item.entry.assetId}"),
                    )
                }
            }
        }
        MediaGridMorphInteractiveTestLayer(
            controller = controller,
            identity = interactionIdentity,
            preparedPairsSnapshot = preparedPairsSnapshot,
            preparedIndex = preparedIndex,
            stopScroll = { state.stopScroll() },
            modifier = Modifier.fillMaxSize().testTag("media_grid_handoff_interaction"),
            onRenderModelBuilt = onRenderModelBuilt,
            onImageResolved = onImageResolved,
        )
    }
}

private fun testAssetColor(assetId: Long): Color {
    val mixed = assetId * 1_103_515_245L + 12_345L
    return Color(
        red = (((mixed ushr 16) and 0x7f) + 96).toInt(),
        green = (((mixed ushr 8) and 0x7f) + 96).toInt(),
        blue = (((mixed ushr 0) and 0x7f) + 96).toInt(),
    )
}
