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

internal data class MediaGridActiveWindowSnapshot(
    val epoch: Long,
    val generation: Long,
    val renderKey: MediaGridRenderKey,
    val visibleAssetIds: LongArray,
    val activeAssetIds: LongArray,
    val activeAssetMembership: Set<Long>,
) {
    fun isActive(assetId: Long): Boolean = assetId in activeAssetMembership
}

internal fun buildMediaGridActiveWindowSnapshot(
    frame: MediaGridFrameData,
    anchor: MediaGridViewportAnchor,
    epoch: Long,
    generation: Long,
): MediaGridActiveWindowSnapshot {
    val visibleIndices = anchor.visibleMediaItemIndices
    val visibleAssetIds = visibleIndices.asSequence()
        .mapNotNull { index -> (frame.items.getOrNull(index) as? MediaGridCellItem)?.entry?.assetId }
        .distinct()
        .toList()
        .toLongArray()
    if (visibleIndices.isEmpty() || anchor.columnCount <= 0) {
        return MediaGridActiveWindowSnapshot(epoch, generation, frame.key, visibleAssetIds, longArrayOf(), emptySet())
    }
    val first = lowerBoundMedia(frame.mediaCellIndices, visibleIndices.min())
    val last = upperBoundMedia(frame.mediaCellIndices, visibleIndices.max())
    val start = (first - anchor.columnCount).coerceAtLeast(0)
    val end = (last + anchor.columnCount).coerceAtMost(frame.mediaCellIndices.size)
    val activeAssetIds = frame.mediaCellIndices.copyOfRange(start, end)
        .asSequence()
        .mapNotNull { index -> (frame.items.getOrNull(index) as? MediaGridCellItem)?.entry?.assetId }
        .distinct()
        .toList()
        .toLongArray()
    return MediaGridActiveWindowSnapshot(epoch, generation, frame.key, visibleAssetIds, activeAssetIds, activeAssetIds.toSet())
}

internal fun <T> retainMediaGridActiveLoadStates(states: Map<Long, T>, activeAssetIds: Set<Long>): Map<Long, T> = states.filterKeys(activeAssetIds::contains)

internal fun mediaGridEstimatedBitmapBytes(candidate: MediaGridPreparedCandidate): Long {
    if (candidate.width <= 0 || candidate.height <= 0) return 0L
    val bytesPerPixel = if (candidate.kind == MediaGridImageSourceKind.Rgb565Pack) 2L else 4L
    return candidate.width.toLong() * candidate.height.toLong() * bytesPerPixel
}

internal fun mediaGridBackgroundBitmapAllowed(usedBytes: Int, maxBytes: Int, estimatedBytes: Long): Boolean =
    maxBytes <= 0 || usedBytes.toLong() + estimatedBytes <= maxBytes.toLong() * MEDIA_GRID_MEMORY_HIGH_WATERMARK

internal fun mediaGridBackgroundBitmapAllowed(usedBytes: Int, maxBytes: Int, estimatedBytes: Int): Boolean =
    mediaGridBackgroundBitmapAllowed(usedBytes, maxBytes, estimatedBytes.toLong())

internal fun mediaGridMemoryWatermarkAllowsResume(usedBytes: Int, maxBytes: Int): Boolean =
    maxBytes <= 0 || usedBytes.toFloat() < maxBytes.toFloat() * MEDIA_GRID_MEMORY_LOW_WATERMARK

private fun lowerBoundMedia(values: IntArray, target: Int): Int { var l = 0; var h = values.size; while (l < h) { val m = (l + h) ushr 1; if (values[m] < target) l = m + 1 else h = m }; return l }
private fun upperBoundMedia(values: IntArray, target: Int): Int { var l = 0; var h = values.size; while (l < h) { val m = (l + h) ushr 1; if (values[m] <= target) l = m + 1 else h = m }; return l }

private enum class LoadLane { Urgent, Background }
internal enum class QueueTaskStatus { Unregistered, BackgroundQueued, UrgentQueued, Running, Complete }
internal data class AssetQueueRecord(
    var metadataStatus: QueueTaskStatus = QueueTaskStatus.Unregistered,
    var bitmapStatus: QueueTaskStatus = QueueTaskStatus.Unregistered,
    var metadataToken: Long = 0L,
    var bitmapToken: Long = 0L,
    var generation: Long = 0L,
    var sourceIdentity: String? = null,
    var candidateIndex: Int = 0,
    var lastUrgentEpoch: Long = Long.MIN_VALUE,
)

internal fun mediaGridQueueTokenIsCurrent(recordToken: Long, taskToken: Long, recordGeneration: Long, taskGeneration: Long): Boolean =
    recordToken == taskToken && recordGeneration == taskGeneration

internal fun mediaGridBitmapQueueEntryMatches(
    status: QueueTaskStatus,
    recordCandidateIndex: Int,
    recordSourceIdentity: String?,
    candidateIndex: Int,
    sourceIdentity: String,
): Boolean = status in setOf(QueueTaskStatus.BackgroundQueued, QueueTaskStatus.UrgentQueued, QueueTaskStatus.Running) &&
    recordCandidateIndex == candidateIndex && recordSourceIdentity == sourceIdentity
private data class LoadTask(val assetId: Long, val itemIndex: Int, val lane: LoadLane, val generation: Long, val token: Long)
private data class BitmapTask(val assetId: Long, val prepared: MediaGridPreparedImage, val candidateIndex: Int, val lane: LoadLane, val generation: Long, val token: Long)
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
    private val anchorSignal = Channel<Unit>(Channel.CONFLATED)
    private val initialAnchorSignal = Channel<Unit>(Channel.CONFLATED)
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
    private val queueRecords = HashMap<Long, AssetQueueRecord>()
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
    @Volatile private var activeSnapshot: MediaGridActiveWindowSnapshot? = null
    private val _uiState = MutableStateFlow(MediaGridControllerUiState())
    val uiState: StateFlow<MediaGridControllerUiState> = _uiState.asStateFlow()
    @Volatile var hasCompletedInitialWarmup: Boolean = false
        private set

    fun updateViewport(anchor: MediaGridViewportAnchor) {
        if (disposed || anchor.renderKey != frame.key) return
        while (!disposed) {
            val previous = latestAnchor.get()
            if (previous == anchor) return
            if (latestAnchor.compareAndSet(previous, anchor)) {
                anchorEpoch.incrementAndGet()
                anchorSignal.trySend(Unit)
                return
            }
        }
    }

    fun start() {
        if (started || disposed) return
        started = true
        synchronized(lock) {
            itemIndexByAsset.forEach { (assetId, itemIndex) -> enqueueMetadataLocked(assetId, itemIndex, LoadLane.Background) }
        }
        workerJobs = buildList {
            repeat(MEDIA_GRID_METADATA_MAX_CONCURRENCY) { add(scope.launch { metadataWorker() }) }
            repeat(MEDIA_GRID_BITMAP_MAX_CONCURRENCY) { add(scope.launch { bitmapWorker() }) }
            add(scope.launch { anchorConsumer() })
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
            queueRecords.keys.retainAll(valid)
            urgentMetadataQueue.clear(); backgroundMetadataQueue.clear()
            urgentBitmapQueue.clear(); backgroundBitmapQueue.clear()
            valid.forEach { assetId ->
                val record = queueRecords.getOrPut(assetId) { AssetQueueRecord(generation = generation) }
                record.generation = generation
                record.metadataToken++
                record.bitmapToken++
                record.metadataStatus = if (metadata.containsKey(assetId)) QueueTaskStatus.Complete else QueueTaskStatus.Unregistered
                record.bitmapStatus = QueueTaskStatus.Unregistered
                if (assetId !in metadata) enqueueMetadataLocked(assetId, itemIndexByAsset.getValue(assetId), LoadLane.Background)
            }
            startupPending = 0
            startupReady = false
        }
        latestAnchor.set(null)
        activeSnapshot = null
        anchorEpoch.incrementAndGet()
        anchorSignal.trySend(Unit)
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
            while (!disposed && !startupReady) {
                if (startupSignal.receiveCatching().isClosed) break
            }
        }
        hasCompletedInitialWarmup = true
        _uiState.value = MediaGridControllerUiState(MediaGridStartupState.Ready)
        emitEvent(LoadEvent.State(0L, generation))
    }

    private suspend fun awaitAnchor(): MediaGridViewportAnchor? {
        latestAnchor.get()?.takeIf { it.cellSizePx > 0 }?.let { return it }
        while (!disposed) {
            if (initialAnchorSignal.receiveCatching().isClosed) return null
            latestAnchor.get()?.takeIf { it.cellSizePx > 0 }?.let { return it }
        }
        return null
    }

    private suspend fun anchorConsumer() {
        while (!disposed) {
            if (anchorSignal.receiveCatching().isClosed) return
            val anchor = latestAnchor.get() ?: continue
            val epoch = anchorEpoch.get()
            val snapshot = buildMediaGridActiveWindowSnapshot(frame, anchor, epoch, generation)
            if (disposed || latestAnchor.get() != anchor || anchorEpoch.get() != epoch || snapshot.renderKey != frame.key) continue
            synchronized(lock) {
                if (disposed || latestAnchor.get() != anchor || anchorEpoch.get() != epoch || generation != snapshot.generation) return@synchronized
                if (activeSnapshot?.epoch == epoch && activeSnapshot?.generation == generation) return@synchronized
                activeSnapshot = snapshot
                promoteUrgentMetadataLocked(snapshot)
                promoteUrgentBitmapLocked(snapshot)
                enqueueVisiblePreparedLocked(snapshot)
            }
            if (activeSnapshot === snapshot) {
                initialAnchorSignal.trySend(Unit)
                emitEvent(LoadEvent.CacheSignal(0L, snapshot.generation))
                signalAll()
            }
        }
    }

    private suspend fun metadataWorker() {
        while (!disposed) {
            val task = nextMetadataTask()
            if (task == null) {
                if (metadataWake.receiveCatching().isClosed) return
                continue
            }
            val prepared = runCatching { preparer.prepareCell(frame, task.itemIndex, latestAnchor.get()?.cellSizePx ?: 256) }.getOrNull()
            synchronized(lock) {
                metadataRunning--
                if (task.lane == LoadLane.Background) metadataBackgroundRunning--
                val record = queueRecords[task.assetId]
                if (record?.metadataToken == task.token && record.generation == task.generation) {
                    record.metadataStatus = QueueTaskStatus.Complete
                }
                if (prepared != null && prepared.key == frame.key && task.generation == generation && task.assetId in itemIndexByAsset && record?.metadataToken == task.token) {
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
                if (bitmapWake.receiveCatching().isClosed) return
                continue
            }
            val candidate = task.prepared.candidates.getOrNull(task.candidateIndex)
            val success = candidate != null && loadCandidate(candidate)
            synchronized(lock) {
                bitmapRunning--
                if (task.lane == LoadLane.Background) bitmapBackgroundRunning--
                val record = queueRecords[task.assetId]
                if (record?.bitmapToken == task.token && record.generation == task.generation) {
                    record.bitmapStatus = QueueTaskStatus.Complete
                }
                if (task.generation == generation && task.assetId in itemIndexByAsset && record?.bitmapToken == task.token) {
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
                            addBitmapTaskLocked(task.assetId, task.prepared, next, task.lane)
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
                val visible = activeSnapshot?.takeIf { it.generation == generation }?.visibleAssetIds ?: longArrayOf()
                val cells: Map<Long, MediaGridCellLoadState> = synchronized(lock) {
                    visible.asSequence().mapNotNull { assetId -> states[assetId]?.let { assetId to it } }.toMap()
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
        val urgent = takeValidMetadataLocked(urgentMetadataQueue)
        val task = if (urgent != null && metadataRunning - metadataBackgroundRunning < MEDIA_GRID_URGENT_RESERVED_CONCURRENCY) {
            urgent
        } else {
            val index = nextBackgroundIndex(backgroundMetadataQueue.map { it.itemIndex })
            if (index < 0 || (metadataBackgroundRunning >= MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY && urgent != null)) {
                if (urgent != null) requeueUrgentMetadataLocked(urgent)
                return@synchronized null
            }
            takeValidMetadataAtLocked(index)
        }
        if (task == null) return@synchronized null
        queueRecords[task.assetId]?.metadataStatus = QueueTaskStatus.Running
        metadataRunning++
        if (task.lane == LoadLane.Background) metadataBackgroundRunning++
        task
    }

    private fun nextBitmapTask(): BitmapTask? = synchronized(lock) {
        if (bitmapRunning >= MEDIA_GRID_BITMAP_MAX_CONCURRENCY) return@synchronized null
        val urgent = takeValidBitmapLocked(urgentBitmapQueue)
        val task = if (urgent != null && bitmapRunning - bitmapBackgroundRunning < MEDIA_GRID_URGENT_RESERVED_CONCURRENCY) {
            urgent
        } else {
            val maxBackground = if (paused) MEDIA_GRID_HIDDEN_BITMAP_MAX_CONCURRENCY else MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY
            val index = nextBackgroundIndex(backgroundBitmapQueue.map { it.prepared.itemIndex })
            if (index < 0 || bitmapBackgroundRunning >= maxBackground) {
                if (urgent != null) requeueUrgentBitmapLocked(urgent)
                return@synchronized null
            }
            val candidate = backgroundBitmapQueue.getOrNull(index) ?: return@synchronized null
            if (!mediaGridBackgroundBitmapAllowed(memoryUsed(), memoryMax(), estimatedBytes(candidate))) {
                if (urgent != null) requeueUrgentBitmapLocked(urgent)
                return@synchronized null
            }
            takeValidBitmapAtLocked(index)
        }
        if (task == null) return@synchronized null
        queueRecords[task.assetId]?.bitmapStatus = QueueTaskStatus.Running
        bitmapRunning++
        if (task.lane == LoadLane.Background) bitmapBackgroundRunning++
        task
    }

    private fun takeValidMetadataLocked(queue: ArrayList<LoadTask>): LoadTask? {
        while (queue.isNotEmpty()) {
            val task = queue.removeAt(0)
            val record = queueRecords[task.assetId]
            if (record != null && mediaGridQueueTokenIsCurrent(record.metadataToken, task.token, record.generation, task.generation) &&
                ((task.lane == LoadLane.Urgent && record.metadataStatus == QueueTaskStatus.UrgentQueued) ||
                    (task.lane == LoadLane.Background && record.metadataStatus == QueueTaskStatus.BackgroundQueued))) return task
        }
        return null
    }

    private fun takeValidMetadataAtLocked(index: Int): LoadTask? {
        if (index < 0 || index >= backgroundMetadataQueue.size) return null
        val task = backgroundMetadataQueue.removeAt(index)
        val record = queueRecords[task.assetId]
        return if (record != null && mediaGridQueueTokenIsCurrent(record.metadataToken, task.token, record.generation, task.generation) && record.metadataStatus == QueueTaskStatus.BackgroundQueued) task
        else takeValidMetadataLocked(backgroundMetadataQueue)
    }

    private fun requeueUrgentMetadataLocked(task: LoadTask) {
        val record = queueRecords[task.assetId] ?: return
        if (record.metadataToken == task.token && record.metadataStatus == QueueTaskStatus.UrgentQueued) urgentMetadataQueue.add(0, task)
    }

    private fun takeValidBitmapLocked(queue: ArrayList<BitmapTask>): BitmapTask? {
        while (queue.isNotEmpty()) {
            val task = queue.removeAt(0)
            val record = queueRecords[task.assetId]
            if (record != null && mediaGridQueueTokenIsCurrent(record.bitmapToken, task.token, record.generation, task.generation) &&
                ((task.lane == LoadLane.Urgent && record.bitmapStatus == QueueTaskStatus.UrgentQueued) ||
                    (task.lane == LoadLane.Background && record.bitmapStatus == QueueTaskStatus.BackgroundQueued))) return task
        }
        return null
    }

    private fun takeValidBitmapAtLocked(index: Int): BitmapTask? {
        if (index < 0 || index >= backgroundBitmapQueue.size) return null
        val task = backgroundBitmapQueue.removeAt(index)
        val record = queueRecords[task.assetId]
        return if (record != null && mediaGridQueueTokenIsCurrent(record.bitmapToken, task.token, record.generation, task.generation) && record.bitmapStatus == QueueTaskStatus.BackgroundQueued) task
        else takeValidBitmapLocked(backgroundBitmapQueue)
    }

    private fun requeueUrgentBitmapLocked(task: BitmapTask) {
        val record = queueRecords[task.assetId] ?: return
        if (record.bitmapToken == task.token && record.bitmapStatus == QueueTaskStatus.UrgentQueued) urgentBitmapQueue.add(0, task)
    }

    private fun queueFirstBitmapLocked(assetId: Long, prepared: MediaGridPreparedImage, originalLane: LoadLane) {
        val candidateIndex = prepared.candidates.indexOfFirst { it.kind in setOf(MediaGridImageSourceKind.Rgb565Pack, MediaGridImageSourceKind.PersistentPreview, MediaGridImageSourceKind.Local) }
        if (candidateIndex >= 0 && states[assetId]?.status != MediaGridCellLoadStatus.Failed) {
            val urgent = activeSnapshot?.isActive(assetId) == true
            addBitmapTaskLocked(assetId, prepared, candidateIndex, if (urgent) LoadLane.Urgent else originalLane)
        }
    }

    private fun enqueueVisiblePreparedLocked(snapshot: MediaGridActiveWindowSnapshot) {
        snapshot.activeAssetIds.forEach { assetId ->
            val state = states[assetId] ?: return@forEach
            val prepared = state.prepared ?: metadata[assetId] ?: return@forEach
            if (state.status == MediaGridCellLoadStatus.Ready) {
                val candidate = prepared.candidates.getOrNull(state.candidateIndex)
                if (candidate != null && !isCached(candidate)) states[assetId] = state.copy(status = MediaGridCellLoadStatus.Pending)
            }
            if (states[assetId]?.status != MediaGridCellLoadStatus.Pending) return@forEach
            val candidateIndex = states[assetId]?.candidateIndex ?: 0
            if (prepared.candidates.getOrNull(candidateIndex) == null) return@forEach
            if (!isCached(prepared.candidates[candidateIndex])) addBitmapTaskLocked(assetId, prepared, candidateIndex, LoadLane.Urgent)
        }
    }

    private fun enqueueMetadataLocked(assetId: Long, itemIndex: Int, lane: LoadLane) {
        val record = queueRecords.getOrPut(assetId) { AssetQueueRecord(generation = generation) }
        if (record.generation != generation) { record.generation = generation; record.metadataToken++ }
        if (record.metadataStatus == QueueTaskStatus.Running || record.metadataStatus == QueueTaskStatus.Complete) return
        record.metadataToken++
        record.metadataStatus = if (lane == LoadLane.Urgent) QueueTaskStatus.UrgentQueued else QueueTaskStatus.BackgroundQueued
        val task = LoadTask(assetId, itemIndex, lane, generation, record.metadataToken)
        if (lane == LoadLane.Urgent) urgentMetadataQueue.add(task) else backgroundMetadataQueue.add(task)
    }

    private fun addBitmapTaskLocked(assetId: Long, prepared: MediaGridPreparedImage, candidateIndex: Int, lane: LoadLane) {
        val candidate = prepared.candidates.getOrNull(candidateIndex) ?: return
        val record = queueRecords.getOrPut(assetId) { AssetQueueRecord(generation = generation) }
        val urgent = lane == LoadLane.Urgent || activeSnapshot?.isActive(assetId) == true
        val normalizedLane = if (urgent) LoadLane.Urgent else LoadLane.Background
        if (mediaGridBitmapQueueEntryMatches(record.bitmapStatus, record.candidateIndex, record.sourceIdentity, candidateIndex, candidate.sourceIdentity)) return
        record.bitmapToken++
        record.bitmapStatus = if (normalizedLane == LoadLane.Urgent) QueueTaskStatus.UrgentQueued else QueueTaskStatus.BackgroundQueued
        record.generation = generation
        record.candidateIndex = candidateIndex
        record.sourceIdentity = candidate.sourceIdentity
        val task = BitmapTask(assetId, prepared, candidateIndex, normalizedLane, generation, record.bitmapToken)
        if (normalizedLane == LoadLane.Urgent) urgentBitmapQueue.add(task) else backgroundBitmapQueue.add(task)
    }

    private fun applyInvalidationsLocked() {
        if (invalidated.isEmpty()) return
        invalidated.forEach { assetId ->
            metadata.remove(assetId)
            states[assetId] = MediaGridCellLoadState()
            val record = queueRecords.getOrPut(assetId) { AssetQueueRecord(generation = generation) }
            record.metadataToken++
            record.bitmapToken++
            record.metadataStatus = QueueTaskStatus.Unregistered
            record.bitmapStatus = QueueTaskStatus.Unregistered
            itemIndexByAsset[assetId]?.let { itemIndex ->
                enqueueMetadataLocked(assetId, itemIndex, if (activeSnapshot?.isActive(assetId) == true) LoadLane.Urgent else LoadLane.Background)
            }
        }
        invalidated.clear()
    }

    private fun promoteUrgentMetadataLocked(snapshot: MediaGridActiveWindowSnapshot) {
        snapshot.activeAssetIds.forEach { assetId ->
            val record = queueRecords[assetId] ?: return@forEach
            if (record.lastUrgentEpoch == snapshot.epoch) return@forEach
            record.lastUrgentEpoch = snapshot.epoch
            if (record.metadataStatus == QueueTaskStatus.BackgroundQueued) {
                record.metadataToken++
                record.metadataStatus = QueueTaskStatus.UrgentQueued
                urgentMetadataQueue.add(LoadTask(assetId, itemIndexByAsset[assetId] ?: return@forEach, LoadLane.Urgent, generation, record.metadataToken))
            }
        }
    }

    private fun promoteUrgentBitmapLocked(snapshot: MediaGridActiveWindowSnapshot) {
        snapshot.activeAssetIds.forEach { assetId ->
            val record = queueRecords[assetId] ?: return@forEach
            if (record.bitmapStatus == QueueTaskStatus.BackgroundQueued) {
                val prepared = metadata[assetId] ?: return@forEach
                record.bitmapToken++
                record.bitmapStatus = QueueTaskStatus.UrgentQueued
                urgentBitmapQueue.add(BitmapTask(assetId, prepared, record.candidateIndex, LoadLane.Urgent, generation, record.bitmapToken))
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

    private fun isLocalCandidate(candidate: MediaGridPreparedCandidate): Boolean = candidate.kind in setOf(MediaGridImageSourceKind.Rgb565Pack, MediaGridImageSourceKind.PersistentPreview, MediaGridImageSourceKind.Local)
    private fun isCached(candidate: MediaGridPreparedCandidate): Boolean = imageLoader.memoryCache?.get(MemoryCache.Key(candidate.cacheKey)) != null
    private fun estimatedBytes(task: BitmapTask): Long = task.prepared.candidates.getOrNull(task.candidateIndex)?.let { mediaGridEstimatedBitmapBytes(it) } ?: 0L
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
        anchorSignal.close(); initialAnchorSignal.close(); metadataWake.close(); bitmapWake.close(); memorySignal.close(); startupSignal.close(); events.close()
        synchronized(lock) { urgentMetadataQueue.clear(); backgroundMetadataQueue.clear(); urgentBitmapQueue.clear(); backgroundBitmapQueue.clear(); metadata.clear(); states.clear(); queueRecords.clear() }
    }
}
