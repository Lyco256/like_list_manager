package com.lyco256.llm

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.Layout

internal const val MediaGridScrollbarMinThumbHeightDp = 32
internal const val MediaGridScrollbarTouchSlopDp = 8
private const val MediaGridScrollbarLabelGapDp = 6

internal data class MediaGridScrollbarGeometry(
    val isScrollable: Boolean,
    val totalMediaCount: Int,
    val visibleMediaCount: Int,
    val firstVisibleMediaOrdinal: Int,
    val thumbHeightPx: Float,
    val maxThumbTopPx: Float,
    val thumbTopPx: Float,
    val fraction: Float,
)

internal fun calculateMediaGridScrollbarGeometry(
    totalMediaCount: Int,
    firstVisibleMediaOrdinal: Int,
    lastVisibleMediaOrdinal: Int,
    trackHeightPx: Float,
    minThumbHeightPx: Float,
): MediaGridScrollbarGeometry {
    val total = totalMediaCount.coerceAtLeast(0)
    val visible = if (total == 0 || firstVisibleMediaOrdinal < 0 || lastVisibleMediaOrdinal < firstVisibleMediaOrdinal) {
        0
    } else {
        (lastVisibleMediaOrdinal - firstVisibleMediaOrdinal + 1).coerceIn(1, total)
    }
    val track = trackHeightPx.coerceAtLeast(0f)
    if (total <= 0 || visible <= 0 || total <= visible || track <= 0f) {
        return MediaGridScrollbarGeometry(
            isScrollable = false,
            totalMediaCount = total,
            visibleMediaCount = visible,
            firstVisibleMediaOrdinal = firstVisibleMediaOrdinal,
            thumbHeightPx = 0f,
            maxThumbTopPx = 0f,
            thumbTopPx = 0f,
            fraction = 0f,
        )
    }
    val thumbHeight = (track * visible.toFloat() / total.toFloat())
        .coerceAtLeast(minThumbHeightPx.coerceAtLeast(0f))
        .coerceAtMost(track)
    val maxThumbTop = (track - thumbHeight).coerceAtLeast(0f)
    val maxStartOrdinal = (total - visible).coerceAtLeast(1)
    val fraction = (firstVisibleMediaOrdinal.coerceIn(0, maxStartOrdinal).toFloat() / maxStartOrdinal)
        .coerceIn(0f, 1f)
    return MediaGridScrollbarGeometry(
        isScrollable = true,
        totalMediaCount = total,
        visibleMediaCount = visible,
        firstVisibleMediaOrdinal = firstVisibleMediaOrdinal,
        thumbHeightPx = thumbHeight,
        maxThumbTopPx = maxThumbTop,
        thumbTopPx = maxThumbTop * fraction,
        fraction = fraction,
    )
}

internal fun mediaGridScrollbarTargetOrdinal(
    fraction: Float,
    totalMediaCount: Int,
    visibleMediaCount: Int,
): Int? {
    val total = totalMediaCount.coerceAtLeast(0)
    val visible = visibleMediaCount.coerceIn(0, total)
    if (total <= 0 || visible <= 0 || total <= visible) return null
    val maxStartOrdinal = total - visible
    return (fraction.coerceIn(0f, 1f) * maxStartOrdinal.toFloat())
        .roundToInt()
        .coerceIn(0, maxStartOrdinal)
}

internal fun mediaGridScrollbarFractionForOrdinal(
    mediaOrdinal: Int,
    totalMediaCount: Int,
    visibleMediaCount: Int,
): Float? {
    val total = totalMediaCount.coerceAtLeast(0)
    val visible = visibleMediaCount.coerceIn(0, total)
    if (total <= 0 || visible <= 0 || total <= visible) return null
    val maxStartOrdinal = total - visible
    return mediaOrdinal.coerceIn(0, maxStartOrdinal).toFloat() / maxStartOrdinal.toFloat()
}

internal fun mediaGridScrollbarTopForOrdinal(
    mediaOrdinal: Int,
    totalMediaCount: Int,
    visibleMediaCount: Int,
    maxThumbTopPx: Float,
): Float? = mediaGridScrollbarFractionForOrdinal(
    mediaOrdinal = mediaOrdinal,
    totalMediaCount = totalMediaCount,
    visibleMediaCount = visibleMediaCount,
)?.let { fraction -> fraction * maxThumbTopPx.coerceAtLeast(0f) }

internal data class MediaGridScrollbarHeaderPillPosition(
    val bucketKey: String,
    val label: String,
    val startMediaOrdinal: Int,
    val topPx: Float,
)

/**
 * Prepares every inline header's fixed scrollbar position from the current frame.
 * The caller caches this result; pointer target changes are intentionally not an input.
 */
internal fun mediaGridScrollbarHeaderPillPositions(
    frame: MediaGridFrameData,
    totalMediaCount: Int,
    visibleMediaCount: Int,
    maxThumbTopPx: Float,
): List<MediaGridScrollbarHeaderPillPosition> = frame.headerBoundaryIndex.boundaries.mapNotNull { boundary ->
    mediaGridScrollbarTopForOrdinal(
        mediaOrdinal = boundary.startMediaOrdinal,
        totalMediaCount = totalMediaCount,
        visibleMediaCount = visibleMediaCount,
        maxThumbTopPx = maxThumbTopPx,
    )?.let { topPx ->
        MediaGridScrollbarHeaderPillPosition(
            bucketKey = boundary.bucketKey,
            label = boundary.label,
            startMediaOrdinal = boundary.startMediaOrdinal,
            topPx = topPx,
        )
    }
}

internal fun mediaGridScrollbarTargetItemIndex(
    fraction: Float,
    totalMediaCount: Int,
    visibleMediaCount: Int,
    itemIndexByMediaOrdinal: IntArray,
): Int? = mediaGridScrollbarTargetOrdinal(fraction, totalMediaCount, visibleMediaCount)
    ?.let { itemIndexByMediaOrdinal.getOrNull(it) }

internal data class MediaGridScrollbarDragSnapshot(
    val isDragging: Boolean = false,
    val frameKey: MediaGridRenderKey? = null,
    val targetMediaOrdinal: Int? = null,
    val targetItemIndex: Int? = null,
    val fraction: Float? = null,
    val thumbTopPx: Float? = null,
    val thumbHeightPx: Float? = null,
    val isFinalTargetPending: Boolean = false,
    val endReason: MediaGridScrollbarDragEnd? = null,
    val completionId: Long = 0L,
    val totalMediaCount: Int = 0,
    val visibleMediaCount: Int = 0,
    val maxThumbTopPx: Float? = null,
)

internal enum class MediaGridScrollbarDragEnd {
    Completed,
    Cancelled,
}

internal enum class MediaGridScrollbarRequestKind {
    Drag,
    Final,
}

internal data class MediaGridScrollbarRequest(
    val requestId: Long,
    val sessionId: Long,
    val targetItemIndex: Int,
    val kind: MediaGridScrollbarRequestKind,
)

private data class MediaGridScrollbarDragSession(
    val sessionId: Long,
    val frameKey: MediaGridRenderKey,
    val itemIndexByMediaOrdinal: IntArray,
    val totalMediaCount: Int,
    val visibleMediaCount: Int,
    val thumbHeightPx: Float,
    val maxThumbTopPx: Float,
    val pointerGrabOffsetPx: Float,
    val thumbTopPx: Float,
    val targetMediaOrdinal: Int,
    val targetItemIndex: Int,
    val fraction: Float,
)

internal class MediaGridScrollbarState {
    var dragSnapshot by mutableStateOf(MediaGridScrollbarDragSnapshot())
        private set

    internal val targetRequests = MutableStateFlow<MediaGridScrollbarRequest?>(null)

    private var session: MediaGridScrollbarDragSession? = null
    private var pendingFinalRequest: MediaGridScrollbarRequest? = null
    private var nextSessionId: Long = 0L
    private var nextRequestId: Long = 0L
    private var requestConsumerToken: Long = 0L
    private var activeRequestConsumerToken: Long? = null
    private var completionId: Long = 0L

    internal val isDragging: Boolean
        get() = dragSnapshot.isDragging

    internal val hasActivePointer: Boolean
        get() = session != null

    internal val hasPendingFinalTarget: Boolean
        get() = pendingFinalRequest != null

    internal val currentTargetItemIndex: Int?
        get() = targetRequests.value?.targetItemIndex ?: pendingFinalRequest?.targetItemIndex ?: session?.targetItemIndex

    internal fun beginRequestConsumer(): Long {
        requestConsumerToken += 1L
        return requestConsumerToken.also { activeRequestConsumerToken = it }
    }

    internal fun shouldProcessRequest(request: MediaGridScrollbarRequest): Boolean {
        if (targetRequests.value != request) return false
        return when (request.kind) {
            MediaGridScrollbarRequestKind.Drag ->
                dragSnapshot.isDragging && session?.let { it.sessionId == request.sessionId } == true
            MediaGridScrollbarRequestKind.Final ->
                !dragSnapshot.isDragging && pendingFinalRequest == request
        }
    }

    internal fun beginDrag(
        frame: MediaGridFrameData,
        geometry: MediaGridScrollbarGeometry,
        pointerY: Float,
    ): Boolean {
        if (!geometry.isScrollable) return false
        // A new pointer always owns the state. An older final request may still
        // be inside scrollToItem, but it must not be allowed to complete this
        // new session.
        pendingFinalRequest = null
        targetRequests.value = null
        val sessionId = nextSessionId + 1L
        nextSessionId = sessionId
        val maxTop = geometry.maxThumbTopPx
        val thumbTop = geometry.thumbTopPx
        val pointerGrabOffset = (pointerY - thumbTop).coerceIn(0f, geometry.thumbHeightPx)
        val targetOrdinal = mediaGridScrollbarTargetOrdinal(
            geometry.fraction,
            geometry.totalMediaCount,
            geometry.visibleMediaCount,
        ) ?: return false
        val targetItemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal.getOrNull(targetOrdinal) ?: return false
        session = MediaGridScrollbarDragSession(
            sessionId = sessionId,
            frameKey = frame.key,
            itemIndexByMediaOrdinal = frame.ordinalIndex.itemIndexByMediaOrdinal,
            totalMediaCount = geometry.totalMediaCount,
            visibleMediaCount = geometry.visibleMediaCount,
            thumbHeightPx = geometry.thumbHeightPx,
            maxThumbTopPx = maxTop,
            pointerGrabOffsetPx = pointerGrabOffset,
            thumbTopPx = thumbTop,
            targetMediaOrdinal = targetOrdinal,
            targetItemIndex = targetItemIndex,
            fraction = geometry.fraction,
        )
        dragSnapshot = MediaGridScrollbarDragSnapshot(
            isDragging = true,
            frameKey = frame.key,
            targetMediaOrdinal = targetOrdinal,
            targetItemIndex = targetItemIndex,
            fraction = geometry.fraction,
            thumbTopPx = thumbTop,
            thumbHeightPx = geometry.thumbHeightPx,
            totalMediaCount = geometry.totalMediaCount,
            visibleMediaCount = geometry.visibleMediaCount,
            maxThumbTopPx = geometry.maxThumbTopPx,
        )
        publishRequest(
            sessionId = sessionId,
            targetItemIndex = targetItemIndex,
            kind = MediaGridScrollbarRequestKind.Drag,
        )
        return true
    }

    internal fun updateDrag(pointerY: Float) {
        val current = session ?: return
        val thumbTop = (pointerY - current.pointerGrabOffsetPx).coerceIn(0f, current.maxThumbTopPx)
        val fraction = if (current.maxThumbTopPx <= 0f) 0f else thumbTop / current.maxThumbTopPx
        val targetOrdinal = mediaGridScrollbarTargetOrdinal(
            fraction,
            current.totalMediaCount,
            current.visibleMediaCount,
        ) ?: return
        val targetItemIndex = current.itemIndexByMediaOrdinal.getOrNull(targetOrdinal) ?: return
        session = current.copy(
            thumbTopPx = thumbTop,
            targetMediaOrdinal = targetOrdinal,
            targetItemIndex = targetItemIndex,
            fraction = fraction,
        )
        dragSnapshot = dragSnapshot.copy(
            targetMediaOrdinal = targetOrdinal,
            targetItemIndex = targetItemIndex,
            fraction = fraction,
            thumbTopPx = thumbTop,
            endReason = null,
        )
        publishRequest(
            sessionId = current.sessionId,
            targetItemIndex = targetItemIndex,
            kind = MediaGridScrollbarRequestKind.Drag,
        )
    }

    internal fun finishDrag() {
        val current = session ?: return
        session = null
        val finalRequest = nextRequest(
            sessionId = current.sessionId,
            targetItemIndex = current.targetItemIndex,
            kind = MediaGridScrollbarRequestKind.Final,
        )
        pendingFinalRequest = finalRequest
        dragSnapshot = dragSnapshot.copy(
            isDragging = false,
            isFinalTargetPending = true,
        )
        targetRequests.value = finalRequest
    }

    internal fun cancelDrag() {
        pendingFinalRequest = null
        session = null
        dragSnapshot = MediaGridScrollbarDragSnapshot(endReason = MediaGridScrollbarDragEnd.Cancelled)
        targetRequests.value = null
    }

    internal fun isFinalTargetPending(targetItemIndex: Int): Boolean =
        pendingFinalRequest?.targetItemIndex == targetItemIndex

    internal fun completeFinalTarget(request: MediaGridScrollbarRequest): Boolean {
        if (
            request.kind != MediaGridScrollbarRequestKind.Final ||
            pendingFinalRequest != request ||
            targetRequests.value != request
        ) return false
        pendingFinalRequest = null
        completionId += 1L
        targetRequests.value = null
        dragSnapshot = dragSnapshot.copy(
            isDragging = false,
            isFinalTargetPending = false,
            endReason = MediaGridScrollbarDragEnd.Completed,
            completionId = completionId,
            thumbTopPx = null,
            thumbHeightPx = null,
            totalMediaCount = 0,
            visibleMediaCount = 0,
            maxThumbTopPx = null,
        )
        return true
    }

    internal fun cancelIfFrameChanged(frameKey: MediaGridRenderKey) {
        val activeFrameKey = session?.frameKey ?: pendingFinalRequest?.let { dragSnapshot.frameKey }
        if (activeFrameKey != null && activeFrameKey != frameKey) cancelDrag()
    }

    internal fun cancelForDisposedFrame(frameKey: MediaGridRenderKey) {
        val activeFrameKey = session?.frameKey ?: pendingFinalRequest?.let { dragSnapshot.frameKey }
        if (activeFrameKey == frameKey) cancelDrag()
    }

    internal fun cancelForCoroutine(frameKey: MediaGridRenderKey, consumerToken: Long) {
        if (activeRequestConsumerToken != consumerToken) return
        activeRequestConsumerToken = null
        val activeFrameKey = session?.frameKey ?: pendingFinalRequest?.let { dragSnapshot.frameKey }
        if (activeFrameKey == frameKey && (session != null || pendingFinalRequest != null)) cancelDrag()
    }

    internal fun thumbTopPx(fallback: MediaGridScrollbarGeometry): Float =
        session?.thumbTopPx
            ?: dragSnapshot.takeIf { it.isDragging || it.isFinalTargetPending }?.thumbTopPx
            ?: fallback.thumbTopPx

    private fun publishRequest(
        sessionId: Long,
        targetItemIndex: Int,
        kind: MediaGridScrollbarRequestKind,
    ) {
        targetRequests.value = nextRequest(sessionId, targetItemIndex, kind)
    }

    private fun nextRequest(
        sessionId: Long,
        targetItemIndex: Int,
        kind: MediaGridScrollbarRequestKind,
    ): MediaGridScrollbarRequest = MediaGridScrollbarRequest(
        requestId = nextRequestId + 1L,
        sessionId = sessionId,
        targetItemIndex = targetItemIndex,
        kind = kind,
    ).also { nextRequestId = it.requestId }
}

@Composable
internal fun rememberMediaGridScrollbarState(): MediaGridScrollbarState =
    remember { MediaGridScrollbarState() }

@Composable
internal fun MediaGridScrollbar(
    frame: MediaGridFrameData,
    anchor: MediaGridViewportAnchorSignature?,
    state: LazyGridState,
    scrollbarState: MediaGridScrollbarState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val minThumbHeightPx = with(density) { MediaGridScrollbarMinThumbHeightDp.dp.toPx() }
    val touchSlopPx = with(density) { MediaGridScrollbarTouchSlopDp.dp.toPx() }
    var trackHeightPx by remember { mutableStateOf(0f) }
    val geometry = calculateMediaGridScrollbarGeometry(
        totalMediaCount = frame.ordinalIndex.assetIdByMediaOrdinal.size,
        firstVisibleMediaOrdinal = anchor?.takeIf { it.renderKey == frame.key }?.firstVisibleMediaOrdinal ?: -1,
        lastVisibleMediaOrdinal = anchor?.takeIf { it.renderKey == frame.key }?.lastVisibleMediaOrdinal ?: -1,
        trackHeightPx = trackHeightPx,
        minThumbHeightPx = minThumbHeightPx,
    )
    val latestGeometry by rememberUpdatedState(geometry)
    val latestFrame by rememberUpdatedState(frame)
    val latestDragState by rememberUpdatedState(scrollbarState)

    LaunchedEffect(frame.key) {
        scrollbarState.cancelIfFrameChanged(frame.key)
    }
    LaunchedEffect(geometry.isScrollable, enabled, frame.key) {
        if (!geometry.isScrollable || !enabled) scrollbarState.cancelDrag()
    }
    LaunchedEffect(scrollbarState, state, frame.key) {
        val consumerToken = scrollbarState.beginRequestConsumer()
        try {
            scrollbarState.targetRequests.collect { request ->
                if (request == null) return@collect
                withFrameNanos { }
                if (!scrollbarState.shouldProcessRequest(request)) return@collect
                state.scrollToItem(request.targetItemIndex)
                if (request.kind == MediaGridScrollbarRequestKind.Final) {
                    scrollbarState.completeFinalTarget(request)
                }
            }
        } finally {
            scrollbarState.cancelForCoroutine(frame.key, consumerToken)
        }
    }
    DisposableEffect(frame.key) {
        onDispose {
            scrollbarState.cancelForDisposedFrame(frame.key)
        }
    }

    if (!enabled || frame.ordinalIndex.assetIdByMediaOrdinal.isEmpty()) return
    val dragSnapshot = scrollbarState.dragSnapshot
    val thumbTopPx = scrollbarState.thumbTopPx(geometry)
    val thumbHeightPx = dragSnapshot
        .takeIf { it.isDragging || it.isFinalTargetPending }
        ?.thumbHeightPx
        ?: geometry.thumbHeightPx
    val headerPillPositions = remember(
        frame.key,
        trackHeightPx,
        geometry.totalMediaCount,
        geometry.visibleMediaCount,
        geometry.maxThumbTopPx,
    ) {
        if (!geometry.isScrollable) {
            emptyList()
        } else {
            mediaGridScrollbarHeaderPillPositions(
                frame = frame,
                totalMediaCount = geometry.totalMediaCount,
                visibleMediaCount = geometry.visibleMediaCount,
                maxThumbTopPx = geometry.maxThumbTopPx,
            )
        }
    }
    val showHeaderPills = dragSnapshot.isDragging && dragSnapshot.frameKey == frame.key
    val touchHeightDp = with(density) { (geometry.thumbHeightPx + touchSlopPx * 2f).toDp() }
    val touchOffsetPx = (thumbTopPx - touchSlopPx).coerceAtLeast(0f)
    val labelGapPx = with(density) { MediaGridScrollbarLabelGapDp.dp.toPx() }
    val thumbHalfWidthPx = with(density) { 2.dp.toPx() }
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(32.dp)
                    .onSizeChanged { trackHeightPx = it.height.toFloat() }
                    .pointerInput(frame.key) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val currentGeometry = latestGeometry
                            val hitTop = (currentGeometry.thumbTopPx - touchSlopPx).coerceAtLeast(0f)
                            val hitBottom = currentGeometry.thumbTopPx + currentGeometry.thumbHeightPx + touchSlopPx
                            if (!currentGeometry.isScrollable || down.position.y !in hitTop..hitBottom) {
                                return@awaitEachGesture
                            }
                            down.consume()
                            if (!latestDragState.beginDrag(latestFrame, currentGeometry, down.position.y)) {
                                return@awaitEachGesture
                            }
                            try {
                                drag(down.id) { change ->
                                    change.consume()
                                    latestDragState.updateDrag(change.position.y)
                                }
                                latestDragState.finishDrag()
                            } finally {
                                if (latestDragState.hasActivePointer) latestDragState.cancelDrag()
                            }
                        }
                    }
                    .testTag("media_grid_scrollbar"),
            ) {
                if (geometry.isScrollable) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxHeight()
                            .width(4.dp)
                            .testTag("media_grid_scrollbar_track")
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, touchOffsetPx.toInt()) }
                            .width(32.dp)
                            .height(touchHeightDp)
                            .zIndex(2f),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(with(density) { thumbHeightPx.toDp() })
                                .testTag("media_grid_scrollbar_thumb")
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (scrollbarState.isDragging || scrollbarState.hasPendingFinalTarget) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    },
                                ),
                        )
                    }
                }
            }
            if (showHeaderPills) {
                headerPillPositions.forEach { position ->
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .zIndex(1f)
                            .testTag("media_grid_scrollbar_header_pill"),
                    ) {
                        Text(
                            text = position.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        modifier = modifier.fillMaxHeight().width(32.dp),
    ) { measurables, constraints ->
        val track = measurables.first().measure(constraints)
        val pills = measurables.drop(1).map { measurable ->
            measurable.measure(
                constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = 0),
            )
        }
        layout(track.width, track.height) {
            track.place(0, 0)
            if (showHeaderPills) {
                pills.forEachIndexed { index, pill ->
                    val position = headerPillPositions[index]
                    val pillTop = position.topPx.coerceIn(
                        0f,
                        (track.height - pill.height).coerceAtLeast(0).toFloat(),
                    )
                    val pillLeft = (
                        track.width - thumbHalfWidthPx * 2f - labelGapPx - pill.width
                    ).roundToInt()
                    pill.place(pillLeft, pillTop.roundToInt())
                }
            }
        }
    }
}

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
