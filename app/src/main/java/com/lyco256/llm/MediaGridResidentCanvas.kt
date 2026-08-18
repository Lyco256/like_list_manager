package com.lyco256.llm

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.memory.MemoryCache
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.roundToInt
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal enum class MediaGridResidentCanvasMode {
    Disabled,
    Enabled,
    TestVisible,
}

/** One exclusive draw surface for Normal, Morph, and the two reveal frames. */
internal enum class MediaGridSingleSurfaceMode {
    Normal,
    Morph,
    RevealCurrent,
    RevealTarget,
}

/** TEST_HARNESS-only evidence that the unified surface executed the Morph branch. */
internal data class MediaGridMorphDrawObservation(
    val generation: Long,
    val phase: MediaGridMorphPhase,
    val direction: MediaGridMorphDirection?,
    val drawMode: MediaGridSingleSurfaceMode,
    val progress: Float,
    val modelIdentity: Int,
    val frameNumber: Long,
    val hasPlan: Boolean,
    val hasActiveRenderModel: Boolean,
    val protectedAssetCount: Int,
    val currentVisualItems: List<MediaGridMorphVisualItemObservation> = emptyList(),
    val sourceVisualItems: List<MediaGridMorphVisualItemObservation> = emptyList(),
    val targetVisualItems: List<MediaGridMorphVisualItemObservation> = emptyList(),
    val headerTransitions: List<MediaGridMorphHeaderTransitionObservation> = emptyList(),
    val performanceCounters: MediaGridMorphPerformanceCounters? = null,
)

internal data class MediaGridMorphVisualItemObservation(
    val key: String,
    val rect: androidx.compose.ui.geometry.Rect,
)

internal data class MediaGridMorphHeaderTransitionObservation(
    val relativeRow: Int,
    val startKey: String?,
    val endKey: String?,
    val startHeightPx: Float,
    val endHeightPx: Float,
    val currentHeightPx: Float,
    val startTextAlpha: Float?,
    val endTextAlpha: Float?,
)

internal data class MediaGridMorphClaimObservation(
    val generation: Long = 0L,
    val direction: MediaGridMorphDirection,
    val bundlePresent: Boolean,
    val directionPrepared: Boolean,
    val accepted: Boolean,
    val failureReason: MediaGridMorphFailureReason?,
    val readinessReason: MediaGridMorphClaimReadinessReason? = null,
    val readinessReport: MediaGridMorphClaimReadinessReport? = null,
)

internal data class MediaGridMorphIdleReadinessObservation(
    val generation: Long,
    val identity: MediaGridMorphInteractionIdentity,
    val directionCount: Int,
    val readyDirectionCount: Int,
    val ready: Boolean,
    val failureReasons: List<String> = emptyList(),
)

internal data class MediaGridMorphHandoffObservation(
    val generation: Long,
    val phase: MediaGridMorphGridHandoffPhase,
    val suppressesUserScroll: Boolean,
    val interactionLocked: Boolean,
)

internal data class MediaGridMorphHandoffCommandObservation(
    val generation: Long,
    val command: String,
    val directLayoutReevaluated: Boolean,
)

internal data class MediaGridMorphPerformanceCounters(
    val claimPairBuilds: Int,
    val selectedPlanBuilds: Int,
    val requiredRenderSetBuilds: Int,
    val renderModelBuilds: Int,
    val increaseRenderModelBuilds: Int,
    val decreaseRenderModelBuilds: Int,
    val textLayoutMeasures: Int,
    val drawRectHelperCalls: Int,
    val drawBlendHelperCalls: Int,
    val stableIdleSnapshotBuilds: Int,
    val focalEntryBuilds: Int,
    val resourceMembershipRechecks: Int,
    val claimFastPathHits: Int,
    val claimLiveCaptureFallbacks: Int,
    val claimTimeFullCaptures: Int,
)

internal object MediaGridMorphTestTrace {
    private val drawEvents = CopyOnWriteArrayList<MediaGridMorphDrawObservation>()
    private val claimEvents = CopyOnWriteArrayList<MediaGridMorphClaimObservation>()
    private val idleReadinessEvents = CopyOnWriteArrayList<MediaGridMorphIdleReadinessObservation>()
    private val handoffEvents = CopyOnWriteArrayList<MediaGridMorphHandoffObservation>()
    private val handoffCommandEvents = CopyOnWriteArrayList<MediaGridMorphHandoffCommandObservation>()
    private val exactTargetViewportGenerations = CopyOnWriteArrayList<Long>()
    private val rollbackColumnCountCommands = AtomicInteger()
    private val rollbackReasons = CopyOnWriteArrayList<String>()
    private val lightweightViewportSignatureBuilds = AtomicInteger()
    private val fullMorphCaptures = AtomicInteger()
    private val morphPairBuilds = AtomicInteger()
    private val morphUrgentAssetRequests = AtomicInteger()
    private val previewPreloaderReconciles = AtomicInteger()
    private val exactTargetLayoutIndexBuilds = AtomicInteger()
    private val claimPairBuilds = AtomicInteger()
    private val selectedPlanBuilds = AtomicInteger()
    private val requiredRenderSetBuilds = AtomicInteger()
    private val renderModelBuilds = AtomicInteger()
    private val increaseRenderModelBuilds = AtomicInteger()
    private val decreaseRenderModelBuilds = AtomicInteger()
    private val textLayoutMeasures = AtomicInteger()
    private val drawRectHelperCalls = AtomicInteger()
    private val drawBlendHelperCalls = AtomicInteger()
    private val stableIdleSnapshotBuilds = AtomicInteger()
    private val focalEntryBuilds = AtomicInteger()
    private val resourceMembershipRechecks = AtomicInteger()
    private val claimFastPathHits = AtomicInteger()
    private val claimLiveCaptureFallbacks = AtomicInteger()
    private val claimTimeFullCaptures = AtomicInteger()
    /** Monotonic test-only signal for stable-idle preparation cache activity. */
    private val preparationCacheMutations = AtomicLong()
    @Volatile private var fallbackCount = 0
    private var nextFrameNumber = 0L

    fun clear() {
        drawEvents.clear()
        claimEvents.clear()
        idleReadinessEvents.clear()
        handoffEvents.clear()
        handoffCommandEvents.clear()
        exactTargetViewportGenerations.clear()
        rollbackColumnCountCommands.set(0)
        rollbackReasons.clear()
        lightweightViewportSignatureBuilds.set(0)
        fullMorphCaptures.set(0)
        morphPairBuilds.set(0)
        morphUrgentAssetRequests.set(0)
        previewPreloaderReconciles.set(0)
        exactTargetLayoutIndexBuilds.set(0)
        claimPairBuilds.set(0)
        selectedPlanBuilds.set(0)
        requiredRenderSetBuilds.set(0)
        renderModelBuilds.set(0)
        increaseRenderModelBuilds.set(0)
        decreaseRenderModelBuilds.set(0)
        textLayoutMeasures.set(0)
        drawRectHelperCalls.set(0)
        drawBlendHelperCalls.set(0)
        stableIdleSnapshotBuilds.set(0)
        focalEntryBuilds.set(0)
        resourceMembershipRechecks.set(0)
        claimFastPathHits.set(0)
        claimLiveCaptureFallbacks.set(0)
        claimTimeFullCaptures.set(0)
        fallbackCount = 0
        nextFrameNumber = 0L
    }

    fun recordPreparationCacheMutation() {
        if (BuildConfig.TEST_HARNESS) preparationCacheMutations.incrementAndGet()
    }

    fun preparationCacheMutationVersion(): Long = preparationCacheMutations.get()

    fun recordDraw(event: MediaGridMorphDrawObservation) {
        if (BuildConfig.TEST_HARNESS) {
            drawEvents += event.copy(performanceCounters = performanceCounters())
        }
    }

    fun recordFallback() {
        if (BuildConfig.TEST_HARNESS) fallbackCount++
    }

    fun drawEvents(): List<MediaGridMorphDrawObservation> = drawEvents.toList()

    fun recordExactTargetViewportValidated(generation: Long) {
        if (BuildConfig.TEST_HARNESS) exactTargetViewportGenerations += generation
    }

    fun exactTargetViewportValidated(generation: Long): Boolean =
        generation in exactTargetViewportGenerations

    fun fallbackCount(): Int = fallbackCount

    fun nextFrameNumber(): Long {
        nextFrameNumber += 1L
        return nextFrameNumber
    }

    fun recordClaim(event: MediaGridMorphClaimObservation) {
        if (BuildConfig.TEST_HARNESS) claimEvents += event
    }

    fun claimEvents(): List<MediaGridMorphClaimObservation> = claimEvents.toList()

    fun recordIdleReadiness(event: MediaGridMorphIdleReadinessObservation) {
        if (BuildConfig.TEST_HARNESS) idleReadinessEvents += event
    }

    fun idleReadinessEvents(): List<MediaGridMorphIdleReadinessObservation> = idleReadinessEvents.toList()

    fun recordHandoff(event: MediaGridMorphHandoffObservation) {
        if (BuildConfig.TEST_HARNESS) handoffEvents += event
    }

    fun handoffEvents(): List<MediaGridMorphHandoffObservation> = handoffEvents.toList()

    fun recordHandoffCommand(event: MediaGridMorphHandoffCommandObservation) {
        if (BuildConfig.TEST_HARNESS) handoffCommandEvents += event
    }

    fun handoffCommandEvents(): List<MediaGridMorphHandoffCommandObservation> = handoffCommandEvents.toList()

    fun recordRollbackColumnCountCommand(reason: String? = null, detail: String? = null) {
        if (BuildConfig.TEST_HARNESS) {
            rollbackColumnCountCommands.incrementAndGet()
            if (reason != null) rollbackReasons += listOfNotNull(reason, detail).joinToString(":")
        }
    }

    fun rollbackColumnCountCommandCount(): Int = rollbackColumnCountCommands.get()

    fun rollbackReasons(): List<String> = rollbackReasons.toList()

    fun recordLightweightViewportSignatureBuild() {
        if (BuildConfig.TEST_HARNESS) lightweightViewportSignatureBuilds.incrementAndGet()
    }

    fun recordFullMorphCapture() {
        if (BuildConfig.TEST_HARNESS) fullMorphCaptures.incrementAndGet()
    }

    fun recordMorphPairBuild() {
        if (BuildConfig.TEST_HARNESS) morphPairBuilds.incrementAndGet()
    }

    fun recordClaimPairBuild() {
        if (BuildConfig.TEST_HARNESS) claimPairBuilds.incrementAndGet()
    }

    fun recordSelectedPlanBuild(direction: MediaGridMorphDirection) {
        if (BuildConfig.TEST_HARNESS) selectedPlanBuilds.incrementAndGet()
    }

    fun recordRequiredRenderSetBuild() {
        if (BuildConfig.TEST_HARNESS) requiredRenderSetBuilds.incrementAndGet()
    }

    fun recordRenderModelBuild(direction: MediaGridMorphDirection) {
        if (!BuildConfig.TEST_HARNESS) return
        renderModelBuilds.incrementAndGet()
        when (direction) {
            MediaGridMorphDirection.IncreaseColumns -> increaseRenderModelBuilds.incrementAndGet()
            MediaGridMorphDirection.DecreaseColumns -> decreaseRenderModelBuilds.incrementAndGet()
        }
    }

    fun recordTextLayoutMeasure() {
        if (BuildConfig.TEST_HARNESS) textLayoutMeasures.incrementAndGet()
    }

    fun recordDrawRectHelperCall() {
        if (BuildConfig.TEST_HARNESS) drawRectHelperCalls.incrementAndGet()
    }

    fun recordDrawBlendHelperCall() {
        if (BuildConfig.TEST_HARNESS) drawBlendHelperCalls.incrementAndGet()
    }

    fun recordStableIdleSnapshotBuild() {
        if (BuildConfig.TEST_HARNESS) stableIdleSnapshotBuilds.incrementAndGet()
    }

    fun recordFocalEntryBuild() {
        if (BuildConfig.TEST_HARNESS) focalEntryBuilds.incrementAndGet()
    }

    fun recordResourceMembershipRecheck() {
        if (BuildConfig.TEST_HARNESS) resourceMembershipRechecks.incrementAndGet()
    }

    fun recordClaimFastPathHit() {
        if (BuildConfig.TEST_HARNESS) claimFastPathHits.incrementAndGet()
    }

    fun recordClaimLiveCaptureFallback() {
        if (BuildConfig.TEST_HARNESS) claimLiveCaptureFallbacks.incrementAndGet()
    }

    fun recordClaimTimeFullCapture() {
        if (BuildConfig.TEST_HARNESS) claimTimeFullCaptures.incrementAndGet()
    }

    fun recordMorphUrgentAssetRequest() {
        if (BuildConfig.TEST_HARNESS) morphUrgentAssetRequests.incrementAndGet()
    }

    fun recordPreviewPreloaderReconcile() {
        if (BuildConfig.TEST_HARNESS) previewPreloaderReconciles.incrementAndGet()
    }

    fun recordExactTargetLayoutIndexBuild() {
        if (BuildConfig.TEST_HARNESS) exactTargetLayoutIndexBuilds.incrementAndGet()
    }

    fun lightweightViewportSignatureBuildCount(): Int = lightweightViewportSignatureBuilds.get()
    fun fullMorphCaptureCount(): Int = fullMorphCaptures.get()
    fun morphPairBuildCount(): Int = morphPairBuilds.get()
    fun morphUrgentAssetRequestCount(): Int = morphUrgentAssetRequests.get()
    fun previewPreloaderReconcileCount(): Int = previewPreloaderReconciles.get()
    fun exactTargetLayoutIndexBuildCount(): Int = exactTargetLayoutIndexBuilds.get()

    fun performanceCounters(): MediaGridMorphPerformanceCounters = MediaGridMorphPerformanceCounters(
        claimPairBuilds = claimPairBuilds.get(),
        selectedPlanBuilds = selectedPlanBuilds.get(),
        requiredRenderSetBuilds = requiredRenderSetBuilds.get(),
        renderModelBuilds = renderModelBuilds.get(),
        increaseRenderModelBuilds = increaseRenderModelBuilds.get(),
        decreaseRenderModelBuilds = decreaseRenderModelBuilds.get(),
        textLayoutMeasures = textLayoutMeasures.get(),
        drawRectHelperCalls = drawRectHelperCalls.get(),
        drawBlendHelperCalls = drawBlendHelperCalls.get(),
        stableIdleSnapshotBuilds = stableIdleSnapshotBuilds.get(),
        focalEntryBuilds = focalEntryBuilds.get(),
        resourceMembershipRechecks = resourceMembershipRechecks.get(),
        claimFastPathHits = claimFastPathHits.get(),
        claimLiveCaptureFallbacks = claimLiveCaptureFallbacks.get(),
        claimTimeFullCaptures = claimTimeFullCaptures.get(),
    )
}

internal data class MediaGridResidentCanvasImage(
    val identity: MediaGridResidentImageIdentity,
    val imageBitmap: ImageBitmap,
    val width: Int,
    val height: Int,
)

internal data class MediaGridResidentCanvasPreparedImage(
    val assetId: Long,
    val identity: MediaGridResidentImageIdentity,
    val image: ImageBitmap,
    val srcOffset: IntOffset,
    val srcSize: IntSize,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

internal data class MediaGridResidentCanvasPreparedIndex(
    val drawIndexVersion: Long,
    val preparedImageByAssetId: Map<Long, MediaGridResidentCanvasPreparedImage>,
) {
    val entryCount: Int get() = preparedImageByAssetId.size
}

private data class CachedResidentImage(
    val value: MemoryCache.Value,
    val image: MediaGridResidentCanvasImage,
)

/** Identity/value keyed adapter cache. It never owns or recycles the source Bitmap. */
internal class MediaGridResidentCanvasImageAdapter {
    private val entries = LinkedHashMap<MediaGridResidentImageIdentity, CachedResidentImage>()

    @Synchronized
    fun sync(index: MediaGridResidentDrawIndex) {
        entries.keys.retainAll(index.handlesByIdentity.keys)
    }

    @Synchronized
    fun resolve(handle: MediaGridResidentDrawHandle): MediaGridResidentCanvasImage? {
        val cached = entries[handle.identity]
        if (cached != null && cached.value === handle.value) return cached.image
        val bitmap = handle.value.bitmap
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            entries.remove(handle.identity)
            return null
        }
        val image = runCatching { bitmap.asImageBitmap() }.getOrNull() ?: run {
            entries.remove(handle.identity)
            return null
        }
        return MediaGridResidentCanvasImage(handle.identity, image, bitmap.width, bitmap.height).also {
            entries[handle.identity] = CachedResidentImage(handle.value, it)
        }
    }

    @Synchronized
    fun size(): Int = entries.size
}

internal fun mediaGridCropSourceRect(
    sourceWidth: Int,
    sourceHeight: Int,
    destinationWidth: Int,
    destinationHeight: Int,
): androidx.compose.ui.geometry.Rect? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0 || destinationHeight <= 0) return null
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val destinationAspect = destinationWidth.toDouble() / destinationHeight
    return if (sourceAspect > destinationAspect) {
        val cropWidth = (sourceHeight * destinationAspect).coerceIn(1.0, sourceWidth.toDouble())
        val left = (sourceWidth - cropWidth) / 2.0
        androidx.compose.ui.geometry.Rect(left.toFloat(), 0f, (left + cropWidth).toFloat(), sourceHeight.toFloat())
    } else {
        val cropHeight = (sourceWidth / destinationAspect).coerceIn(1.0, sourceHeight.toDouble())
        val top = (sourceHeight - cropHeight) / 2.0
        androidx.compose.ui.geometry.Rect(0f, top.toFloat(), sourceWidth.toFloat(), (top + cropHeight).toFloat())
    }
}

internal fun mediaGridResidentCanvasViewportRect(width: Int, height: Int): androidx.compose.ui.geometry.Rect =
    androidx.compose.ui.geometry.Rect(0f, 0f, width.coerceAtLeast(0).toFloat(), height.coerceAtLeast(0).toFloat())

internal fun mediaGridCanvasDestinationRect(
    offset: IntOffset,
    size: IntSize,
    viewportStartOffset: Int,
): androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect(
    offset.x.toFloat(),
    (offset.y - viewportStartOffset).toFloat(),
    (offset.x + size.width).toFloat(),
    (offset.y - viewportStartOffset + size.height).toFloat(),
)

internal fun buildMediaGridResidentCanvasPreparedIndex(
    drawIndex: MediaGridResidentDrawIndex,
    adapter: MediaGridResidentCanvasImageAdapter,
): MediaGridResidentCanvasPreparedIndex {
    adapter.sync(drawIndex)
    val prepared = LinkedHashMap<Long, MediaGridResidentCanvasPreparedImage>(drawIndex.handlesByIdentity.size)
    drawIndex.handlesByIdentity.values.forEach { handle ->
        if (prepared.size >= MEDIA_GRID_RETAINED_IMAGE_TARGET_ENTRIES || !handle.directDrawEligible) return@forEach
        val image = adapter.resolve(handle) ?: return@forEach
        val crop = mediaGridCropSourceRect(image.width, image.height, 1, 1) ?: return@forEach
        val srcOffset = IntOffset(crop.left.roundToInt(), crop.top.roundToInt())
        val srcSize = IntSize(crop.width.roundToInt(), crop.height.roundToInt())
        if (srcOffset.x < 0 || srcOffset.y < 0 || srcOffset.x + srcSize.width > image.width || srcOffset.y + srcSize.height > image.height) return@forEach
        prepared[handle.identity.assetId] = MediaGridResidentCanvasPreparedImage(
            assetId = handle.identity.assetId,
            identity = handle.identity,
            image = image.imageBitmap,
            srcOffset = srcOffset,
            srcSize = srcSize,
            sourceWidth = image.width,
            sourceHeight = image.height,
        )
    }
    return MediaGridResidentCanvasPreparedIndex(
        drawIndexVersion = drawIndex.version,
        preparedImageByAssetId = Collections.unmodifiableMap(prepared),
    )
}

/** The draw modifier only applies current LazyGrid coordinates to an immutable prepared index. */
internal fun Modifier.mediaGridResidentCanvas(
    state: LazyGridState,
    assetIdByItemKey: Map<String, Long>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    mode: MediaGridResidentCanvasMode = MediaGridResidentCanvasMode.Disabled,
): Modifier {
    if (mode == MediaGridResidentCanvasMode.Disabled) return this
    val tagged = if (mode == MediaGridResidentCanvasMode.TestVisible) testTag("media_grid_resident_canvas") else this
    return tagged.drawWithCache {
        onDrawWithContent {
            val layout = state.layoutInfo
            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                layout.visibleItemsInfo.forEach { info ->
                    val assetId = (info.key as? String)?.let(assetIdByItemKey::get) ?: return@forEach
                    val image = preparedIndex.preparedImageByAssetId[assetId] ?: return@forEach
                    val left = info.offset.x
                    val top = info.offset.y - layout.viewportStartOffset
                    val width = info.size.width
                    val height = info.size.height
                    val right = left + width
                    val bottom = top + height
                    if (right <= 0 || bottom <= 0 || left >= size.width || top >= size.height || width <= 0 || height <= 0) return@forEach
                    drawImage(
                        image = image.image,
                        srcOffset = image.srcOffset,
                        srcSize = image.srcSize,
                        dstOffset = IntOffset(left, top),
                        dstSize = IntSize(width, height),
                    )
                }
            }
            drawContent()
        }
    }
}

internal data class MediaGridResidentCanvasLayoutIdentity(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

/**
 * Unified production surface. Normal scrolling reads the current LazyGrid
 * layout directly while drawing, matching the allocation-free resident path.
 * Morph draws only its frozen model. There is no overlay, second grid, zIndex,
 * or translation.
 */
internal fun Modifier.mediaGridSingleSurface(
    state: LazyGridState,
    assetIdByItemKey: Map<String, Long>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    mode: State<MediaGridSingleSurfaceMode>,
    morphModel: MediaGridMorphRowRenderModel?,
    progress: State<Float>,
    layoutIdentity: MediaGridResidentCanvasLayoutIdentity,
    morphDrawObserver: ((MediaGridMorphDrawObservation) -> Unit)? = null,
    morphSnapshot: State<MediaGridMorphInteractionSnapshot>? = null,
): Modifier = drawWithCache {
    @Suppress("UNUSED_VARIABLE")
    val cacheInvalidationIdentity = layoutIdentity
    onDrawWithContent {
        val currentMode = mode.value
        val snapshot = morphSnapshot?.value
        val layout = state.layoutInfo
        val normalVisualItems = if (morphDrawObserver != null) {
            layout.visibleItemsInfo.mapNotNull { info ->
                val key = info.key as? String ?: return@mapNotNull null
                val visualKey = assetIdByItemKey[key]?.let { "asset:$it" } ?: "header:$key"
                val rect = mediaGridCanvasDestinationRect(info.offset, info.size, layout.viewportStartOffset)
                val visibleWidth = minOf(rect.right, size.width) - maxOf(rect.left, 0f)
                val visibleHeight = minOf(rect.bottom, size.height) - maxOf(rect.top, 0f)
                if (visibleWidth <= 1f || visibleHeight <= 1f) {
                    return@mapNotNull null
                }
                MediaGridMorphVisualItemObservation(visualKey, rect)
            }.sortedBy { it.key }
        } else {
            emptyList()
        }
        if (morphDrawObserver != null && snapshot != null) {
            morphDrawObserver(
                MediaGridMorphDrawObservation(
                    generation = snapshot.interactionGeneration,
                    phase = snapshot.phase,
                    direction = snapshot.direction,
                    drawMode = currentMode,
                    progress = progress.value,
                    modelIdentity = snapshot.activeRenderModel?.let { System.identityHashCode(it) } ?: 0,
                    frameNumber = MediaGridMorphTestTrace.nextFrameNumber(),
                    hasPlan = snapshot.plan != null,
                    hasActiveRenderModel = snapshot.activeRenderModel != null,
                    protectedAssetCount = snapshot.protectedAssetIds.size,
                    currentVisualItems = normalVisualItems,
                    sourceVisualItems = snapshot.activeRenderModel
                        ?.let(::mediaGridMorphSourceVisualItems)
                        .orEmpty(),
                    targetVisualItems = snapshot.activeRenderModel
                        ?.let(::mediaGridMorphTargetVisualItems)
                        .orEmpty(),
                    headerTransitions = snapshot.activeRenderModel
                        ?.let { mediaGridMorphHeaderTransitions(it, progress.value) }
                        .orEmpty(),
                ),
            )
        }
        when (currentMode) {
            MediaGridSingleSurfaceMode.Morph -> {
                val model = snapshot?.activeRenderModel ?: morphModel
                if (model == null) {
                    drawContent()
                } else {
                    drawRect(model.surfaceColor)
                    drawMediaGridMorphRow(model, progress.value)
                }
            }
            MediaGridSingleSurfaceMode.Normal,
            MediaGridSingleSurfaceMode.RevealCurrent,
            MediaGridSingleSurfaceMode.RevealTarget,
            -> {
                clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                    for (info in layout.visibleItemsInfo) {
                        val key = info.key as? String ?: continue
                        val assetId = assetIdByItemKey[key] ?: continue
                        val image = preparedIndex.preparedImageByAssetId[assetId] ?: continue
                        val width = info.size.width
                        val height = info.size.height
                        if (width <= 0 || height <= 0) continue
                        val left = info.offset.x
                        val top = info.offset.y - layout.viewportStartOffset
                        if (left + width <= 0 || top + height <= 0 || left >= size.width || top >= size.height) continue
                        drawImage(
                            image = image.image,
                            srcOffset = image.srcOffset,
                            srcSize = image.srcSize,
                            dstOffset = IntOffset(left, top),
                            dstSize = IntSize(width, height),
                        )
                    }
                }
                drawContent()
            }
        }
    }
}
