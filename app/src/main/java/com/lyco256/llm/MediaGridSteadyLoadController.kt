package com.lyco256.llm

import android.content.Context
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lyco256.llm.data.MediaGridImagePreparer
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import com.lyco256.llm.data.MediaGridPreparedImage
import com.lyco256.llm.data.buildMediaGridImageRequest
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal const val MEDIA_GRID_METADATA_MAX_CONCURRENCY = 4
internal const val MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY = 2
internal const val MEDIA_GRID_URGENT_RESERVED_CONCURRENCY = 2
internal const val MEDIA_GRID_BITMAP_MAX_CONCURRENCY = 4
internal const val MEDIA_GRID_HIDDEN_BITMAP_MAX_CONCURRENCY = 1
internal const val MEDIA_GRID_MEMORY_HIGH_WATERMARK = 0.75f
internal const val MEDIA_GRID_MEMORY_LOW_WATERMARK = 0.65f
internal const val MEDIA_GRID_WARMUP_ROWS = 2
internal const val MEDIA_GRID_WARMUP_MAX_ASSETS = 128
internal const val MEDIA_GRID_WARMUP_MAX_BYTES = 32L * 1024L * 1024L
internal const val MEDIA_GRID_STARTUP_TIMEOUT_MS = 2_500L

internal enum class MediaGridStartupState { PreparingFrame, PreparingInitialWindow, WarmingInitialWindow, Ready }
internal enum class MediaGridCellLoadStatus { Pending, Loading, Ready, Failed }

internal data class MediaGridViewportAnchor(
    val renderKey: MediaGridRenderKey,
    val firstVisibleItemIndex: Int,
    val lastVisibleItemIndex: Int,
    val visibleMediaItemIndices: IntArray,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val cellSizePx: Int,
    val columnCount: Int,
) {
    override fun equals(other: Any?): Boolean = other is MediaGridViewportAnchor &&
        renderKey == other.renderKey && firstVisibleItemIndex == other.firstVisibleItemIndex &&
        lastVisibleItemIndex == other.lastVisibleItemIndex && visibleMediaItemIndices.contentEquals(other.visibleMediaItemIndices) &&
        viewportWidthPx == other.viewportWidthPx && viewportHeightPx == other.viewportHeightPx &&
        cellSizePx == other.cellSizePx && columnCount == other.columnCount
    override fun hashCode(): Int = 31 * renderKey.hashCode() + firstVisibleItemIndex
}

internal data class MediaGridCellLoadState(
    val status: MediaGridCellLoadStatus = MediaGridCellLoadStatus.Pending,
    val prepared: MediaGridPreparedImage? = null,
    val candidateIndex: Int = 0,
) {
    val readyCandidate: MediaGridPreparedCandidate?
        get() = if (status == MediaGridCellLoadStatus.Ready) prepared?.candidates?.getOrNull(candidateIndex) else null
}

internal data class MediaGridControllerUiState(
    val startup: MediaGridStartupState = MediaGridStartupState.PreparingFrame,
    val cells: Map<Long, MediaGridCellLoadState> = emptyMap(),
)

internal fun selectMediaGridInitialWarmupIndices(frame: MediaGridFrameData, anchor: MediaGridViewportAnchor): IntArray {
    if (anchor.cellSizePx <= 0 || anchor.columnCount <= 0 || frame.mediaCellIndices.isEmpty()) return intArrayOf()
    val visible = anchor.visibleMediaItemIndices.toList().sorted()
    val first = visible.firstOrNull()?.let { lowerBoundMedia(frame.mediaCellIndices, it) } ?: lowerBoundMedia(frame.mediaCellIndices, anchor.firstVisibleItemIndex)
    val last = visible.lastOrNull()?.let { upperBoundMedia(frame.mediaCellIndices, it) } ?: first
    val perScreen = (kotlin.math.ceil(anchor.viewportHeightPx.toDouble() / anchor.cellSizePx).toInt().coerceAtLeast(1) * anchor.columnCount)
    val ordered = ArrayList<Int>()
    fun append(from: Int, to: Int) {
        val start = from.coerceIn(0, frame.mediaCellIndices.size)
        val end = to.coerceIn(start, frame.mediaCellIndices.size)
        if (start < end) frame.mediaCellIndices.copyOfRange(start, end).forEach { if (it !in ordered) ordered += it }
    }
    append(first, last); append(last, last + perScreen); append(last + perScreen, last + perScreen * 2)
    if (anchor.firstVisibleItemIndex > 0) append(first - anchor.columnCount, first)
    val maxAssets = minOf(MEDIA_GRID_WARMUP_MAX_ASSETS, (MEDIA_GRID_WARMUP_MAX_BYTES / (256L * 256L * 4L)).toInt())
    return ordered.take(maxAssets).toIntArray()
}

internal fun selectMediaGridActiveWindow(frame: MediaGridFrameData, anchor: MediaGridViewportAnchor): Set<Int> {
    if (anchor.visibleMediaItemIndices.isEmpty() || anchor.columnCount <= 0) return emptySet()
    val first = lowerBoundMedia(frame.mediaCellIndices, anchor.visibleMediaItemIndices.min())
    val last = upperBoundMedia(frame.mediaCellIndices, anchor.visibleMediaItemIndices.max())
    return frame.mediaCellIndices.copyOfRange((first - anchor.columnCount).coerceAtLeast(0), (last + anchor.columnCount).coerceAtMost(frame.mediaCellIndices.size)).toSet()
}

internal fun <T> retainMediaGridActiveLoadStates(states: Map<Long, T>, activeAssetIds: Set<Long>): Map<Long, T> = states.filterKeys(activeAssetIds::contains)

internal fun mediaGridBackgroundBitmapAllowed(usedBytes: Int, maxBytes: Int, estimatedBytes: Int): Boolean =
    maxBytes <= 0 || usedBytes.toFloat() + estimatedBytes <= maxBytes.toFloat() * MEDIA_GRID_MEMORY_HIGH_WATERMARK

internal fun mediaGridMemoryWatermarkAllowsResume(usedBytes: Int, maxBytes: Int): Boolean =
    maxBytes <= 0 || usedBytes.toFloat() < maxBytes.toFloat() * MEDIA_GRID_MEMORY_LOW_WATERMARK

private fun lowerBoundMedia(values: IntArray, target: Int): Int { var l = 0; var h = values.size; while (l < h) { val m = (l + h) ushr 1; if (values[m] < target) l = m + 1 else h = m }; return l }
private fun upperBoundMedia(values: IntArray, target: Int): Int { var l = 0; var h = values.size; while (l < h) { val m = (l + h) ushr 1; if (values[m] <= target) l = m + 1 else h = m }; return l }

private enum class LoadLane { Urgent, Background }
private data class LoadTask(val assetId: Long, val itemIndex: Int, val lane: LoadLane, val generation: Long)
private data class BitmapTask(val assetId: Long, val prepared: MediaGridPreparedImage, val candidateIndex: Int, val lane: LoadLane, val generation: Long)
private sealed interface LoadEvent {
    val assetId: Long
    val generation: Long
    data class State(override val assetId: Long, override val generation: Long) : LoadEvent
    data class CacheSignal(override val assetId: Long, override val generation: Long) : LoadEvent
}

/** Event-driven media-grid controller. Heavy preparation and UI publication have separate consumers. */
internal class MediaGridSteadyLoadController(
    private val context: Context,
    private var frame: MediaGridFrameData,
    private val preparer: MediaGridImagePreparer,
    private val imageLoader: ImageLoader,
    private val onPreviewCandidateError: suspend (Long, MediaGridPreparedCandidate) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val latestAnchor = AtomicReference<MediaGridViewportAnchor?>()
    private val anchorEpoch = java.util.concurrent.atomic.AtomicLong()
    private val urgentMetadataQueue = ArrayList<LoadTask>()
    private val backgroundMetadataQueue = ArrayList<LoadTask>()
    private val urgentBitmapQueue = ArrayList<BitmapTask>()
    private val backgroundBitmapQueue = ArrayList<BitmapTask>()
    private val metadataWake = Channel<Unit>(Channel.CONFLATED)
    private val bitmapWake = Channel<Unit>(Channel.CONFLATED)
    private val memorySignal = Channel<Unit>(Channel.CONFLATED)
    private val startupSignal = Channel<Unit>(Channel.CONFLATED)
    private val events = Channel<LoadEvent>(capacity = 256)
    private val metadata = HashMap<Long, MediaGridPreparedImage>()
    private val states = HashMap<Long, MediaGridCellLoadState>()
    private val invalidated = HashSet<Long>()
    private var metadataRunning = 0
    private var metadataBackgroundRunning = 0
    private var bitmapRunning = 0
    private var bitmapBackgroundRunning = 0
    private var generation = 1L
    private var started = false
    private var paused = false
    private var disposed = false
    private var publicationPaused = false
    private var startupPending = 0
    private val startupAssets = HashSet<Long>()
    private var startupReady = false
    private var workerJobs = emptyList<Job>()
    private var itemIndexByAsset = mediaGridAssetIndex(frame)
    private val _uiState = MutableStateFlow(MediaGridControllerUiState())
    val uiState: StateFlow<MediaGridControllerUiState> = _uiState.asStateFlow()
    @Volatile var hasCompletedInitialWarmup: Boolean = false
        private set

    fun updateViewport(anchor: MediaGridViewportAnchor) {
        if (disposed || anchor.renderKey != frame.key) return
        latestAnchor.set(anchor)
        anchorEpoch.incrementAndGet()
        synchronized(lock) {
            promoteUrgentMetadataLocked()
            promoteUrgentBitmapLocked()
            enqueueVisiblePreparedLocked()
        }
        emitEvent(LoadEvent.CacheSignal(0L, generation))
        signalAll()
    }

    fun start() {
        if (started || disposed) return
        started = true
        synchronized(lock) {
            backgroundMetadataQueue.addAll(itemIndexByAsset.map { (assetId, itemIndex) ->
                LoadTask(assetId, itemIndex, LoadLane.Background, generation)
            })
        }
        workerJobs = buildList {
            repeat(MEDIA_GRID_METADATA_MAX_CONCURRENCY) { add(scope.launch { metadataWorker() }) }
            repeat(MEDIA_GRID_BITMAP_MAX_CONCURRENCY) { add(scope.launch { bitmapWorker() }) }
            add(scope.launch { uiPublicationConsumer() })
            add(scope.launch { startupCoordinator() })
        }
    }

    fun pause() { paused = true; publicationPaused = true; signalAll() }
    fun resume() { paused = false; publicationPaused = false; signalAll(); emitEvent(LoadEvent.CacheSignal(0L, generation)) }

    @Synchronized fun updateFrame(nextFrame: MediaGridFrameData) {
        if (disposed || nextFrame.key.dataKey.filter != frame.key.dataKey.filter || nextFrame.key.dataKey.sort != frame.key.dataKey.sort) return
        generation++
        frame = nextFrame
        itemIndexByAsset = mediaGridAssetIndex(frame)
        synchronized(lock) {
            val valid = itemIndexByAsset.keys
            metadata.keys.retainAll(valid)
            states.keys.retainAll(valid)
            urgentMetadataQueue.removeIf { it.assetId !in valid }
            backgroundMetadataQueue.removeIf { it.assetId !in valid }
            urgentBitmapQueue.removeIf { it.assetId !in valid }
            backgroundBitmapQueue.removeIf { it.assetId !in valid }
            backgroundMetadataQueue.addAll(valid.filter { it !in metadata }.map { LoadTask(it, itemIndexByAsset.getValue(it), LoadLane.Background, generation) })
            startupPending = 0
            startupReady = false
        }
        latestAnchor.set(null)
        signalAll()
    }

    fun invalidate(assetId: Long) {
        if (disposed || assetId !in itemIndexByAsset) return
        synchronized(lock) { invalidated += assetId }
        signalAll()
    }

    private suspend fun startupCoordinator() {
        _uiState.value = MediaGridControllerUiState(MediaGridStartupState.PreparingInitialWindow)
        val anchor = awaitAnchor()
        if (anchor == null || disposed) return
        val initial = selectMediaGridInitialWarmupIndices(frame, anchor)
        synchronized(lock) {
            startupAssets.clear()
            initial.asSequence().mapNotNull { index ->
                (frame.items.getOrNull(index) as? MediaGridCellItem)?.entry?.assetId
            }.forEach(startupAssets::add)
            startupAssets.removeAll { it in metadata }
            startupPending = startupAssets.size
            if (startupPending == 0) { startupReady = true; startupSignal.trySend(Unit) }
        }
        _uiState.value = MediaGridControllerUiState(MediaGridStartupState.WarmingInitialWindow)
        signalAll()
        withTimeoutOrNull(MEDIA_GRID_STARTUP_TIMEOUT_MS) {
            while (!disposed && !startupReady) startupSignal.receive()
        }
        hasCompletedInitialWarmup = true
        _uiState.value = MediaGridControllerUiState(MediaGridStartupState.Ready)
        emitEvent(LoadEvent.State(0L, generation))
    }

    private suspend fun awaitAnchor(): MediaGridViewportAnchor? {
        latestAnchor.get()?.takeIf { it.cellSizePx > 0 }?.let { return it }
        while (!disposed) {
            metadataWake.receive()
            latestAnchor.get()?.takeIf { it.cellSizePx > 0 }?.let { return it }
        }
        return null
    }

    private suspend fun metadataWorker() {
        while (!disposed) {
            val task = nextMetadataTask()
            if (task == null) {
                metadataWake.receive()
                continue
            }
            val prepared = runCatching { preparer.prepareCell(frame, task.itemIndex, latestAnchor.get()?.cellSizePx ?: 256) }.getOrNull()
            synchronized(lock) {
                metadataRunning--
                if (task.lane == LoadLane.Background) metadataBackgroundRunning--
                if (prepared != null && prepared.key == frame.key && task.generation == generation && task.assetId in itemIndexByAsset) {
                    metadata[task.assetId] = prepared
                    states[task.assetId] = if (prepared.candidates.isEmpty()) MediaGridCellLoadState(MediaGridCellLoadStatus.Failed, prepared) else MediaGridCellLoadState(prepared = prepared)
                    if (startupAssets.remove(task.assetId)) startupPending--
                    if (startupPending == 0) { startupReady = true; startupSignal.trySend(Unit) }
                    queueFirstBitmapLocked(task.assetId, prepared, task.lane)
                }
            }
            emitEvent(LoadEvent.State(task.assetId, task.generation)); signalAll()
        }
    }

    private suspend fun bitmapWorker() {
        while (!disposed) {
            val task = nextBitmapTask()
            if (task == null) {
                bitmapWake.receive()
                continue
            }
            val candidate = task.prepared.candidates.getOrNull(task.candidateIndex)
            val success = candidate != null && loadCandidate(candidate)
            synchronized(lock) {
                bitmapRunning--
                if (task.lane == LoadLane.Background) bitmapBackgroundRunning--
                if (task.generation == generation && task.assetId in itemIndexByAsset) {
                    val old = states[task.assetId]
                    if (success && candidate != null && isCached(candidate)) {
                        states[task.assetId] = old?.copy(status = MediaGridCellLoadStatus.Ready, prepared = task.prepared, candidateIndex = task.candidateIndex)
                            ?: MediaGridCellLoadState(MediaGridCellLoadStatus.Ready, task.prepared, task.candidateIndex)
                    } else {
                        if (candidate != null && candidate.kind in setOf(MediaGridImageSourceKind.Rgb565Pack, MediaGridImageSourceKind.PersistentPreview)) {
                            scope.launch { onPreviewCandidateError(task.assetId, candidate) }
                        }
                        val next = task.candidateIndex + 1
                        val nextCandidate = task.prepared.candidates.getOrNull(next)
                        if (nextCandidate != null && (task.lane == LoadLane.Urgent || isLocalCandidate(nextCandidate))) {
                            states[task.assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Pending, task.prepared, next)
                            addBitmapTaskLocked(BitmapTask(task.assetId, task.prepared, next, task.lane, task.generation))
                        } else {
                            states[task.assetId] = if (task.lane == LoadLane.Background) MediaGridCellLoadState(MediaGridCellLoadStatus.Pending, task.prepared, next)
                            else MediaGridCellLoadState(MediaGridCellLoadStatus.Failed, task.prepared, next)
                        }
                    }
                }
            }
            emitEvent(LoadEvent.State(task.assetId, task.generation)); signalAll(); memorySignal.trySend(Unit)
        }
    }

    private suspend fun uiPublicationConsumer() {
        for (event in events) {
            val batch = ArrayList<LoadEvent>(4)
            batch += event
            while (true) {
                val next: ChannelResult<LoadEvent> = events.tryReceive()
                if (next.isFailure) break
                next.getOrNull()?.let(batch::add)
            }
            if (!publicationPaused && !disposed) {
                val visible = visibleAssetIds()
                val cells = synchronized(lock) {
                    visible.mapNotNull { assetId -> states[assetId]?.let { assetId to it } }.toMap()
                }
                withContext(Dispatchers.Main.immediate) {
                    val old = _uiState.value
                    if (old.cells != cells) _uiState.value = MediaGridControllerUiState(if (hasCompletedInitialWarmup) MediaGridStartupState.Ready else old.startup, cells)
                }
            }
        }
    }

    private fun nextMetadataTask(): LoadTask? = synchronized(lock) {
        applyInvalidationsLocked()
        val metadataLimit = if (paused) MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY else MEDIA_GRID_METADATA_MAX_CONCURRENCY
        if (metadataRunning >= metadataLimit) return@synchronized null
        val urgent = urgentMetadataQueue.removeFirstOrNull()
        val task = if (urgent != null && metadataRunning - metadataBackgroundRunning < MEDIA_GRID_URGENT_RESERVED_CONCURRENCY) {
            urgent
        } else {
            val index = nextBackgroundIndex(backgroundMetadataQueue.map { it.itemIndex })
            if (index < 0 || (metadataBackgroundRunning >= MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY && urgent != null)) {
                if (urgent != null) urgentMetadataQueue.add(0, urgent)
                return@synchronized null
            }
            backgroundMetadataQueue.removeAt(index)
        }
        metadataRunning++
        if (task.lane == LoadLane.Background) metadataBackgroundRunning++
        task
    }

    private fun nextBitmapTask(): BitmapTask? = synchronized(lock) {
        enqueueVisiblePreparedLocked()
        if (bitmapRunning >= MEDIA_GRID_BITMAP_MAX_CONCURRENCY) return@synchronized null
        val urgent = urgentBitmapQueue.removeFirstOrNull()
        val task = if (urgent != null && bitmapRunning - bitmapBackgroundRunning < MEDIA_GRID_URGENT_RESERVED_CONCURRENCY) {
            urgent
        } else {
            val maxBackground = if (paused) MEDIA_GRID_HIDDEN_BITMAP_MAX_CONCURRENCY else MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY
            val index = nextBackgroundIndex(backgroundBitmapQueue.map { it.prepared.itemIndex })
            if (index < 0 || bitmapBackgroundRunning >= maxBackground) {
                if (urgent != null) urgentBitmapQueue.add(0, urgent)
                return@synchronized null
            }
            val candidate = backgroundBitmapQueue[index]
            if (!mediaGridBackgroundBitmapAllowed(memoryUsed(), memoryMax(), estimatedBytes(candidate))) {
                if (urgent != null) urgentBitmapQueue.add(0, urgent)
                return@synchronized null
            }
            backgroundBitmapQueue.removeAt(index)
        }
        bitmapRunning++
        if (task.lane == LoadLane.Background) bitmapBackgroundRunning++
        task
    }

    private fun queueFirstBitmapLocked(assetId: Long, prepared: MediaGridPreparedImage, originalLane: LoadLane) {
        val candidateIndex = prepared.candidates.indexOfFirst { it.kind in setOf(MediaGridImageSourceKind.Rgb565Pack, MediaGridImageSourceKind.PersistentPreview, MediaGridImageSourceKind.Local) }
        if (candidateIndex >= 0 && states[assetId]?.status != MediaGridCellLoadStatus.Failed) {
            addBitmapTaskLocked(BitmapTask(assetId, prepared, candidateIndex, if (isUrgent(assetId)) LoadLane.Urgent else originalLane, generation))
        }
    }

    private fun enqueueVisiblePreparedLocked() {
        val anchor = latestAnchor.get() ?: return
        val active = selectMediaGridActiveWindow(frame, anchor)
        val visible = active.asSequence()
            .mapNotNull { index -> (frame.items.getOrNull(index) as? MediaGridCellItem)?.entry?.assetId }
            .toSet()
        visible.forEach { assetId ->
            val state = states[assetId] ?: return@forEach
            val prepared = state.prepared ?: metadata[assetId] ?: return@forEach
            if (state.status == MediaGridCellLoadStatus.Ready) {
                val candidate = prepared.candidates.getOrNull(state.candidateIndex)
                if (candidate != null && !isCached(candidate)) states[assetId] = state.copy(status = MediaGridCellLoadStatus.Pending)
            }
            if (states[assetId]?.status != MediaGridCellLoadStatus.Pending) return@forEach
            val candidateIndex = states[assetId]?.candidateIndex ?: 0
            if (prepared.candidates.getOrNull(candidateIndex) == null) return@forEach
            if (urgentBitmapQueue.none { it.assetId == assetId } && backgroundBitmapQueue.none { it.assetId == assetId }) {
                addBitmapTaskLocked(BitmapTask(assetId, prepared, candidateIndex, LoadLane.Urgent, generation))
            }
        }
    }

    private fun addBitmapTaskLocked(task: BitmapTask) {
        val urgent = task.lane == LoadLane.Urgent || isUrgent(task.assetId)
        val queue = if (urgent) urgentBitmapQueue else backgroundBitmapQueue
        if (queue.none { it.assetId == task.assetId && it.generation == task.generation }) {
            queue.add(task.copy(lane = if (urgent) LoadLane.Urgent else LoadLane.Background))
        }
    }

    private fun applyInvalidationsLocked() {
        if (invalidated.isEmpty()) return
        invalidated.forEach { assetId ->
            metadata.remove(assetId)
            states[assetId] = MediaGridCellLoadState()
            urgentMetadataQueue.removeIf { it.assetId == assetId }
            backgroundMetadataQueue.removeIf { it.assetId == assetId }
            urgentBitmapQueue.removeIf { it.assetId == assetId }
            backgroundBitmapQueue.removeIf { it.assetId == assetId }
            itemIndexByAsset[assetId]?.let { itemIndex ->
                val task = LoadTask(assetId, itemIndex, if (isUrgent(assetId)) LoadLane.Urgent else LoadLane.Background, generation)
                if (task.lane == LoadLane.Urgent) urgentMetadataQueue.add(task) else backgroundMetadataQueue.add(task)
            }
        }
        invalidated.clear()
    }

    private fun promoteUrgentMetadataLocked() {
        val iterator = backgroundMetadataQueue.listIterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (isUrgent(task.assetId)) {
                iterator.remove()
                urgentMetadataQueue.add(task.copy(lane = LoadLane.Urgent))
            }
        }
    }

    private fun promoteUrgentBitmapLocked() {
        val iterator = backgroundBitmapQueue.listIterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (isUrgent(task.assetId)) {
                iterator.remove()
                urgentBitmapQueue.add(task.copy(lane = LoadLane.Urgent))
            }
        }
    }

    private fun nextBackgroundIndex(indices: List<Int>): Int {
        if (indices.isEmpty()) return -1
        val anchor = latestAnchor.get() ?: return 0
        val center = lowerBoundMedia(frame.mediaCellIndices, (anchor.firstVisibleItemIndex + anchor.lastVisibleItemIndex) / 2)
        return indices.indices.minWithOrNull(compareBy<Int>({
            kotlin.math.abs(lowerBoundMedia(frame.mediaCellIndices, indices[it]) - center)
        }, {
            if (lowerBoundMedia(frame.mediaCellIndices, indices[it]) >= center) 0 else 1
        })) ?: -1
    }

    private fun visibleAssetIds(): Set<Long> = latestAnchor.get()?.visibleMediaItemIndices?.asSequence()?.mapNotNull { index -> (frame.items.getOrNull(index) as? MediaGridCellItem)?.entry?.assetId }?.toSet() ?: emptySet()
    private fun isUrgent(assetId: Long): Boolean {
        val anchor = latestAnchor.get() ?: return false
        val active = selectMediaGridActiveWindow(frame, anchor)
        return itemIndexByAsset[assetId] in active
    }
    private fun isLocalCandidate(candidate: MediaGridPreparedCandidate): Boolean = candidate.kind in setOf(MediaGridImageSourceKind.Rgb565Pack, MediaGridImageSourceKind.PersistentPreview, MediaGridImageSourceKind.Local)
    private fun isCached(candidate: MediaGridPreparedCandidate): Boolean = imageLoader.memoryCache?.get(MemoryCache.Key(candidate.cacheKey)) != null
    private fun estimatedBytes(task: BitmapTask): Int = task.prepared.candidates.getOrNull(task.candidateIndex)?.let { it.width * it.height * 4 } ?: 0
    private fun memoryUsed(): Int = imageLoader.memoryCache?.size ?: 0
    private fun memoryMax(): Int = imageLoader.memoryCache?.maxSize ?: 0
    private fun memoryWatermarkOpen(): Boolean = mediaGridMemoryWatermarkAllowsResume(memoryUsed(), memoryMax())

    private suspend fun loadCandidate(candidate: MediaGridPreparedCandidate): Boolean = suspendCancellableCoroutine { continuation ->
        val request = buildMediaGridImageRequest(context, candidate).newBuilder().listener(object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { if (continuation.isActive) continuation.resume(true) }
            override fun onError(request: ImageRequest, result: ErrorResult) { if (continuation.isActive) continuation.resume(false) }
            override fun onCancel(request: ImageRequest) { if (continuation.isActive) continuation.resume(false) }
        }).build()
        val disposable: Disposable = imageLoader.enqueue(request)
        continuation.invokeOnCancellation { disposable.dispose() }
    }

    private fun emitEvent(event: LoadEvent) { if (!disposed) events.trySend(event) }
    private fun signalAll() { metadataWake.trySend(Unit); bitmapWake.trySend(Unit); memorySignal.trySend(Unit) }
    private fun mediaGridAssetIndex(value: MediaGridFrameData): Map<Long, Int> = value.mediaCellIndices.asSequence().mapNotNull { index -> (value.items.getOrNull(index) as? MediaGridCellItem)?.entry?.assetId?.let { it to index } }.toMap()

    fun dispose() {
        disposed = true; generation++
        workerJobs.forEach(Job::cancel)
        scope.cancel()
        metadataWake.close(); bitmapWake.close(); memorySignal.close(); startupSignal.close(); events.close()
        synchronized(lock) { urgentMetadataQueue.clear(); backgroundMetadataQueue.clear(); urgentBitmapQueue.clear(); backgroundBitmapQueue.clear(); metadata.clear(); states.clear() }
    }
}
