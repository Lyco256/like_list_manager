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
import java.util.ArrayDeque
import java.util.BitSet
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal const val MEDIA_GRID_METADATA_MAX_CONCURRENCY = 4
internal const val MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY = 2
internal const val MEDIA_GRID_URGENT_RESERVED_CONCURRENCY = 2
internal const val MEDIA_GRID_BITMAP_MAX_CONCURRENCY = 4
internal const val MEDIA_GRID_HIDDEN_BITMAP_MAX_CONCURRENCY = 1
internal const val MEDIA_GRID_MEMORY_HIGH_WATERMARK = 0.75f
internal const val MEDIA_GRID_MEMORY_LOW_WATERMARK = 0.65f
internal const val MEDIA_GRID_ACTIVE_PREFETCH_ROWS = 3
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
    val firstVisibleMediaOrdinal: Int,
    val lastVisibleMediaOrdinal: Int,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val cellSizePx: Int,
    val columnCount: Int,
)

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
    val framePublicationDemand: Boolean = false,
)

internal fun mediaGridSameReadyCandidate(left: MediaGridCellLoadState?, right: MediaGridCellLoadState?): Boolean {
    val leftCandidate = left?.readyCandidate ?: return false
    val rightCandidate = right?.readyCandidate ?: return false
    return leftCandidate.cacheKey == rightCandidate.cacheKey &&
        leftCandidate.sourceIdentity == rightCandidate.sourceIdentity
}

/** Returns only visible new Ready attachments, ordered around the latest viewport center. */
internal fun mediaGridReadyAttachmentOrder(
    visibleAssetIds: LongArray,
    internalCells: Map<Long, MediaGridCellLoadState>,
    publishedCells: Map<Long, MediaGridCellLoadState>,
    mediaOrdinalByAssetId: Map<Long, Int>,
    centerMediaOrdinal: Int,
): List<Long> = visibleAssetIds.asSequence().mapIndexedNotNull { visibleIndex, assetId ->
    val internal = internalCells[assetId]
    if (internal?.status != MediaGridCellLoadStatus.Ready || mediaGridSameReadyCandidate(internal, publishedCells[assetId])) return@mapIndexedNotNull null
    val ordinal = mediaOrdinalByAssetId[assetId] ?: return@mapIndexedNotNull null
    Triple(assetId, ordinal, visibleIndex)
}.sortedWith(
    compareBy<Triple<Long, Int, Int>> { kotlin.math.abs(it.second - centerMediaOrdinal) }
        .thenBy { if (it.second >= centerMediaOrdinal) 0 else 1 }
        .thenBy { it.third },
).map { it.first }.toList()

internal fun selectMediaGridInitialWarmupIndices(frame: MediaGridFrameData, anchor: MediaGridViewportAnchor): IntArray {
    val index = frame.ordinalIndex
    if (anchor.cellSizePx <= 0 || anchor.columnCount <= 0 || index.itemIndexByMediaOrdinal.isEmpty()) return intArrayOf()
    val first = if (anchor.firstVisibleMediaOrdinal >= 0) {
        anchor.firstVisibleMediaOrdinal.coerceIn(0, index.itemIndexByMediaOrdinal.size)
    } else {
        lowerBoundMedia(index.itemIndexByMediaOrdinal, anchor.firstVisibleItemIndex)
            .coerceIn(0, index.itemIndexByMediaOrdinal.size)
    }
    val last = if (anchor.lastVisibleMediaOrdinal >= first) {
        (anchor.lastVisibleMediaOrdinal + 1).coerceIn(first, index.itemIndexByMediaOrdinal.size)
    } else first
    val perScreen = (kotlin.math.ceil(anchor.viewportHeightPx.toDouble() / anchor.cellSizePx).toInt().coerceAtLeast(1) * anchor.columnCount)
    val maxAssets = minOf(MEDIA_GRID_WARMUP_MAX_ASSETS, (MEDIA_GRID_WARMUP_MAX_BYTES / (256L * 256L * 4L)).toInt())
    val result = IntArray(maxAssets)
    var count = 0
    fun append(from: Int, to: Int) {
        var ordinal = from.coerceIn(0, index.itemIndexByMediaOrdinal.size)
        val end = to.coerceIn(ordinal, index.itemIndexByMediaOrdinal.size)
        while (ordinal < end && count < result.size) {
            result[count++] = index.itemIndexByMediaOrdinal[ordinal++]
        }
    }
    append(first, last); append(last, last + perScreen); append(last + perScreen, last + perScreen * 2)
    if (anchor.firstVisibleItemIndex > 0) append(first - anchor.columnCount, first)
    return result.copyOf(count)
}

internal data class MediaGridActiveWindowSnapshot(
    val epoch: Long,
    val generation: Long,
    val renderKey: MediaGridRenderKey,
    val visibleAssetIds: LongArray,
    val activeAssetIds: LongArray,
    val activeStartMediaOrdinal: Int,
    val activeEndMediaOrdinalExclusive: Int,
    val centerMediaOrdinal: Int,
) {
    fun isActive(assetId: Long, ordinalByAssetId: Map<Long, Int>): Boolean {
        val ordinal = ordinalByAssetId[assetId] ?: return false
        return ordinal in activeStartMediaOrdinal until activeEndMediaOrdinalExclusive
    }
}

internal class MediaGridOrdinalPendingSet(private val mediaCellCount: Int) {
    private val bits = BitSet(mediaCellCount)

    fun add(ordinal: Int) { if (ordinal in 0 until mediaCellCount) bits.set(ordinal) }
    fun remove(ordinal: Int) { if (ordinal >= 0) bits.clear(ordinal) }
    fun contains(ordinal: Int): Boolean = ordinal >= 0 && bits.get(ordinal)
    fun clear() { bits.clear() }
    fun snapshot(): Set<Int> = buildSet {
        var ordinal = bits.nextSetBit(0)
        while (ordinal >= 0) {
            add(ordinal)
            ordinal = bits.nextSetBit(ordinal + 1)
        }
    }
    fun isEmpty(): Boolean = bits.isEmpty

    fun peekNearest(centerOrdinal: Int): Int {
        val center = centerOrdinal.coerceIn(0, (mediaCellCount - 1).coerceAtLeast(0))
        val down = bits.nextSetBit(center)
        val up = if (center == 0) -1 else bits.previousSetBit(center - 1)
        return when {
            down < 0 -> up
            up < 0 -> down
            down - center <= center - up -> down
            else -> up
        }
    }

    fun pollNearest(centerOrdinal: Int): Int {
        val ordinal = peekNearest(centerOrdinal)
        if (ordinal >= 0) bits.clear(ordinal)
        return ordinal
    }
}

internal fun buildMediaGridActiveWindowSnapshot(
    frame: MediaGridFrameData,
    anchor: MediaGridViewportAnchor,
    epoch: Long,
    generation: Long,
): MediaGridActiveWindowSnapshot {
    val index = frame.ordinalIndex
    val visibleStart = anchor.firstVisibleMediaOrdinal.coerceIn(0, index.assetIdByMediaOrdinal.size)
    val visibleEnd = (anchor.lastVisibleMediaOrdinal + 1).coerceIn(visibleStart, index.assetIdByMediaOrdinal.size)
    val visibleAssetIds = mediaGridAssetIdsForOrdinalRange(index, visibleStart, visibleEnd)
    if (anchor.firstVisibleMediaOrdinal < 0 || anchor.lastVisibleMediaOrdinal < anchor.firstVisibleMediaOrdinal || anchor.columnCount <= 0) {
        return MediaGridActiveWindowSnapshot(epoch, generation, frame.key, visibleAssetIds, longArrayOf(), 0, 0, centerMediaOrdinal = -1)
    }
    val prefetchCells = anchor.columnCount * MEDIA_GRID_ACTIVE_PREFETCH_ROWS
    val start = (visibleStart - prefetchCells).coerceAtLeast(0)
    val end = (visibleEnd + prefetchCells).coerceAtMost(index.assetIdByMediaOrdinal.size)
    val activeAssetIds = mediaGridAssetIdsForOrdinalRange(index, start, end)
    val centerMediaOrdinal = (visibleStart + visibleEnd - 1) / 2
    return MediaGridActiveWindowSnapshot(epoch, generation, frame.key, visibleAssetIds, activeAssetIds, start, end, centerMediaOrdinal)
}

private fun mediaGridAssetIdsForOrdinalRange(index: MediaGridOrdinalIndex, start: Int, end: Int): LongArray {
    if (start >= end) return LongArray(0)
    var hasDuplicate = false
    var ordinal = start
    while (ordinal < end) {
        val assetId = index.assetIdByMediaOrdinal[ordinal]
        if (index.mediaOrdinalByAssetId[assetId] != ordinal) { hasDuplicate = true; break }
        ordinal++
    }
    if (!hasDuplicate) return index.assetIdByMediaOrdinal.copyOfRange(start, end)
    val seen = HashSet<Long>(end - start)
    val result = LongArray(end - start)
    var count = 0
    ordinal = start
    while (ordinal < end) {
        val assetId = index.assetIdByMediaOrdinal[ordinal++]
        if (seen.add(assetId)) result[count++] = assetId
    }
    return result.copyOf(count)
}

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
internal enum class QueueTaskStatus { Unregistered, BackgroundQueued, UrgentQueued, Running, Complete, Failed }
internal interface MediaGridMetadataPreparer {
    suspend fun prepareCellOrNull(frame: MediaGridFrameData, itemIndex: Int, cellSizePx: Int): MediaGridPreparedImage?
}

internal interface MediaGridBitmapGateway {
    fun isCached(candidate: MediaGridPreparedCandidate): Boolean
    fun isCached(assetId: Long, candidate: MediaGridPreparedCandidate): Boolean = isCached(candidate)
    suspend fun load(candidate: MediaGridPreparedCandidate): Boolean
    fun retain(assetId: Long, candidate: MediaGridPreparedCandidate) = Unit
    fun retain(assetId: Long, candidate: MediaGridPreparedCandidate, directDrawEligible: Boolean) {
        retain(assetId, candidate)
    }
}

internal interface MediaGridFramePublicationTarget {
    val framePublicationDemand: StateFlow<Boolean>
    fun publishOneReadyImageForFrame()
}

private class CoilMediaGridBitmapGateway(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val retainedImageStore: MediaGridRetainedImageStore?,
) : MediaGridBitmapGateway {
    override fun isCached(candidate: MediaGridPreparedCandidate): Boolean {
        val value = imageLoader.memoryCache?.get(MemoryCache.Key(candidate.cacheKey))
        if (value != null) return true
        return false
    }

    override fun isCached(assetId: Long, candidate: MediaGridPreparedCandidate): Boolean {
        if (isCached(candidate)) return true
        return retainedImageStore?.restore(assetId, candidate) == true
    }

    override fun retain(assetId: Long, candidate: MediaGridPreparedCandidate, directDrawEligible: Boolean) {
        imageLoader.memoryCache?.get(MemoryCache.Key(candidate.cacheKey))?.let { value ->
            retainedImageStore?.retain(assetId, candidate, value, directDrawEligible)
        }
    }

    override suspend fun load(candidate: MediaGridPreparedCandidate): Boolean = suspendCancellableCoroutine { continuation ->
        val request = buildMediaGridImageRequest(context, candidate).newBuilder().listener(object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { if (continuation.isActive) continuation.resume(true) }
            override fun onError(request: ImageRequest, result: ErrorResult) { if (continuation.isActive) continuation.resume(false) }
            override fun onCancel(request: ImageRequest) { if (continuation.isActive) continuation.resume(false) }
        }).build()
        val disposable: Disposable = imageLoader.enqueue(request)
        continuation.invokeOnCancellation { disposable.dispose() }
    }
}

internal data class MediaGridControllerStateSnapshot(
    val cells: Map<Long, MediaGridCellLoadState>,
    val publishedCells: Map<Long, MediaGridCellLoadState> = emptyMap(),
    val framePublicationDemand: Boolean = false,
    val metadata: Map<Long, MediaGridPreparedImage>,
    val records: Map<Long, AssetQueueRecord>,
    val metadataPendingOrdinals: Set<Int>,
    val bitmapPendingOrdinals: Set<Int>,
    val urgentMetadataAssetIds: List<Long>,
    val urgentBitmapAssetIds: List<Long>,
    val metadataRunningAssetIds: Set<Long>,
    val bitmapRunningAssetIds: Set<Long>,
    val metadataRunning: Int,
    val bitmapRunning: Int,
    val generation: Long,
    val visibleAssetIds: Set<Long>,
)
internal data class AssetQueueRecord(
    var metadataStatus: QueueTaskStatus = QueueTaskStatus.Unregistered,
    var bitmapStatus: QueueTaskStatus = QueueTaskStatus.Unregistered,
    var metadataToken: Long = 0L,
    var bitmapToken: Long = 0L,
    var generation: Long = 0L,
    var sourceIdentity: String? = null,
    var candidateIndex: Int = 0,
    var lastUrgentEpoch: Long = Long.MIN_VALUE,
    var metadataAttempts: Int = 0,
)

internal fun mediaGridControllerStateViolations(
    snapshot: MediaGridControllerStateSnapshot,
    ordinalByAssetId: Map<Long, Int>,
): List<String> = buildList {
    snapshot.records.forEach { (assetId, record) ->
        val ordinal = ordinalByAssetId[assetId]
        when (record.metadataStatus) {
            QueueTaskStatus.BackgroundQueued -> if (ordinal == null || ordinal !in snapshot.metadataPendingOrdinals) add("metadata pending missing: $assetId")
            QueueTaskStatus.UrgentQueued -> if (snapshot.urgentMetadataAssetIds.count { it == assetId } != 1) add("metadata urgent missing: $assetId")
            QueueTaskStatus.Running -> if (assetId !in snapshot.metadataRunningAssetIds) add("metadata running missing: $assetId")
            QueueTaskStatus.Complete -> if (assetId !in snapshot.metadata) add("metadata complete without prepared: $assetId")
            QueueTaskStatus.Unregistered, QueueTaskStatus.Failed -> Unit
        }
        when (record.bitmapStatus) {
            QueueTaskStatus.BackgroundQueued -> if (ordinal == null || ordinal !in snapshot.bitmapPendingOrdinals) add("bitmap pending missing: $assetId")
            QueueTaskStatus.UrgentQueued -> if (snapshot.urgentBitmapAssetIds.count { it == assetId } != 1) add("bitmap urgent missing: $assetId")
            QueueTaskStatus.Running -> if (assetId !in snapshot.bitmapRunningAssetIds) add("bitmap running missing: $assetId")
            QueueTaskStatus.Complete, QueueTaskStatus.Unregistered, QueueTaskStatus.Failed -> Unit
        }
        if (assetId in snapshot.visibleAssetIds && snapshot.cells[assetId]?.status == MediaGridCellLoadStatus.Pending) {
            val metadataWork = record.metadataStatus in setOf(QueueTaskStatus.BackgroundQueued, QueueTaskStatus.UrgentQueued, QueueTaskStatus.Running)
            val bitmapWork = record.bitmapStatus in setOf(QueueTaskStatus.BackgroundQueued, QueueTaskStatus.UrgentQueued, QueueTaskStatus.Running)
            if (assetId !in snapshot.metadata && !metadataWork) add("visible pending without metadata work: $assetId")
            if (assetId in snapshot.metadata && !bitmapWork) add("visible pending without bitmap work: $assetId")
        }
    }
    if (snapshot.metadataRunning != snapshot.metadataRunningAssetIds.size) add("metadata running count mismatch")
    if (snapshot.bitmapRunning != snapshot.bitmapRunningAssetIds.size) add("bitmap running count mismatch")
    if (snapshot.urgentMetadataAssetIds.size != snapshot.urgentMetadataAssetIds.distinct().size) add("duplicate metadata urgent entry")
    if (snapshot.urgentBitmapAssetIds.size != snapshot.urgentBitmapAssetIds.distinct().size) add("duplicate bitmap urgent entry")
}

internal fun assertConsistentState(
    snapshot: MediaGridControllerStateSnapshot,
    ordinalByAssetId: Map<Long, Int>,
) {
    val violations = mediaGridControllerStateViolations(snapshot, ordinalByAssetId)
    check(violations.isEmpty()) { violations.joinToString("; ") }
}

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

private fun mediaGridRetentionChangedAssets(
    previous: MediaGridFrameData,
    next: MediaGridFrameData,
): Set<Long> {
    val previousByAsset = previous.items.mapNotNull { (it as? MediaGridCellItem)?.entry }
        .associateBy { it.assetId }
    val nextEntries = next.items.mapNotNull { (it as? MediaGridCellItem)?.entry }
    val nextByAsset = nextEntries.associateBy { it.assetId }
    return (previousByAsset.keys + nextByAsset.keys).filter { assetId ->
        val old = previousByAsset[assetId]
        val current = nextByAsset[assetId]
        old == null || current == null || old.mediaKey != current.mediaKey || old.localPath != current.localPath ||
            old.previewUrl != current.previewUrl || old.remoteUrl != current.remoteUrl || old.displayUrl != current.displayUrl
    }.toSet()
}
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
    private val preparer: MediaGridMetadataPreparer,
    private val imageLoader: ImageLoader,
    private val onPreviewCandidateError: suspend (Long, MediaGridPreparedCandidate) -> Unit,
    bitmapGatewayOverride: MediaGridBitmapGateway? = null,
    private val retainedImageStore: MediaGridRetainedImageStore? = null,
    private val ownerToken: Long = 0L,
) : MediaGridFramePublicationTarget {
    private val bitmapGateway: MediaGridBitmapGateway =
        bitmapGatewayOverride ?: CoilMediaGridBitmapGateway(context, imageLoader, retainedImageStore)
    constructor(
        context: Context,
        frame: MediaGridFrameData,
        preparer: MediaGridImagePreparer,
        imageLoader: ImageLoader,
        onPreviewCandidateError: suspend (Long, MediaGridPreparedCandidate) -> Unit,
        retainedImageStore: MediaGridRetainedImageStore? = null,
        ownerToken: Long = 0L,
    ) : this(context, frame, object : MediaGridMetadataPreparer {
        override suspend fun prepareCellOrNull(frame: MediaGridFrameData, itemIndex: Int, cellSizePx: Int): MediaGridPreparedImage =
            preparer.prepareCell(frame, itemIndex, cellSizePx)
    }, imageLoader, onPreviewCandidateError, null, retainedImageStore, ownerToken)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val latestAnchor = AtomicReference<MediaGridViewportAnchor?>()
    private val anchorEpoch = java.util.concurrent.atomic.AtomicLong()
    private val anchorSignal = Channel<Unit>(Channel.CONFLATED)
    private val initialAnchorSignal = Channel<Unit>(Channel.CONFLATED)
    private val urgentMetadataQueue = ArrayDeque<LoadTask>()
    private val urgentBitmapQueue = ArrayDeque<BitmapTask>()
    private val metadataWake = Channel<Unit>(Channel.CONFLATED)
    private val bitmapWake = Channel<Unit>(Channel.CONFLATED)
    private val memorySignal = Channel<Unit>(Channel.CONFLATED)
    private val startupSignal = Channel<Unit>(Channel.CONFLATED)
    private val events = Channel<LoadEvent>(capacity = Channel.CONFLATED)
    private val metadata = HashMap<Long, MediaGridPreparedImage>()
    private val states = HashMap<Long, MediaGridCellLoadState>()
    private val publishedCells = LinkedHashMap<Long, MediaGridCellLoadState>()
    private val frameTransitionPreserved = HashSet<Long>()
    private val queueRecords = HashMap<Long, AssetQueueRecord>()
    private val invalidated = HashSet<Long>()
    private var metadataRunning = 0
    private var metadataBackgroundRunning = 0
    private var bitmapRunning = 0
    private var bitmapBackgroundRunning = 0
    private val metadataRunningAssets = HashSet<Long>()
    private val bitmapRunningAssets = HashSet<Long>()
    private var generation = 1L
    private var started = false
    private var paused = false
    private var disposed = false
    private var publicationPaused = false
    private var startupPending = 0
    private val startupAssets = HashSet<Long>()
    private var startupReady = false
    private var startupPublicationBatchPending = false
    private var startupState = MediaGridStartupState.PreparingFrame
    private var workerJobs = emptyList<Job>()
    private var ordinalIndex = frame.ordinalIndex
    private var metadataPending = MediaGridOrdinalPendingSet(ordinalIndex.assetIdByMediaOrdinal.size)
    private var bitmapPending = MediaGridOrdinalPendingSet(ordinalIndex.assetIdByMediaOrdinal.size)
    @Volatile private var activeSnapshot: MediaGridActiveWindowSnapshot? = null
    private val _uiState = MutableStateFlow(MediaGridControllerUiState())
    val uiState: StateFlow<MediaGridControllerUiState> = _uiState.asStateFlow()
    private val _framePublicationDemand = MutableStateFlow(false)
    override val framePublicationDemand: StateFlow<Boolean> = _framePublicationDemand.asStateFlow()
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
            ordinalIndex.assetIdByMediaOrdinal.forEachIndexed { ordinal, assetId -> enqueueMetadataLocked(assetId, ordinalIndex.itemIndexByMediaOrdinal[ordinal], LoadLane.Background) }
        }
        workerJobs = buildList {
            repeat(MEDIA_GRID_METADATA_MAX_CONCURRENCY) { add(scope.launch { metadataWorker() }) }
            repeat(MEDIA_GRID_BITMAP_MAX_CONCURRENCY) { add(scope.launch { bitmapWorker() }) }
            add(scope.launch { anchorConsumer() })
            add(scope.launch { uiPublicationConsumer() })
            add(scope.launch { startupCoordinator() })
        }
    }

    fun pause() {
        paused = true
        publicationPaused = true
        retainedImageStore?.updateProtection(ownerToken, longArrayOf(), longArrayOf())
        reconcilePublishedState(maxNewReady = 0)
        signalAll()
    }
    fun resume() {
        paused = false
        publicationPaused = false
        activeSnapshot?.let { retainedImageStore?.updateProtection(ownerToken, it.visibleAssetIds, it.activeAssetIds) }
        synchronized(lock) { activeSnapshot?.activeAssetIds?.forEach { reconcileAssetWorkLocked(it, LoadLane.Urgent) } }
        reconcilePublishedState(maxNewReady = 0)
        signalAll()
        emitEvent(LoadEvent.CacheSignal(0L, generation))
    }

    @Synchronized fun updateFrame(nextFrame: MediaGridFrameData) {
        if (disposed || nextFrame.key.dataKey.filter != frame.key.dataKey.filter || nextFrame.key.dataKey.sort != frame.key.dataKey.sort) return
        mediaGridRetentionChangedAssets(frame, nextFrame).forEach { retainedImageStore?.invalidateAsset(it) }
        generation++
        frame = nextFrame
        ordinalIndex = frame.ordinalIndex
        synchronized(lock) {
            val valid = ordinalIndex.mediaOrdinalByAssetId.keys
            frameTransitionPreserved.retainAll(valid)
            publishedCells.keys.forEach { assetId ->
                if (assetId in valid && publishedCells[assetId]?.status == MediaGridCellLoadStatus.Ready) {
                    frameTransitionPreserved += assetId
                }
            }
            metadata.entries.removeIf { (assetId, prepared) -> assetId !in valid || prepared.key != frame.key }
            states.keys.retainAll(valid)
            queueRecords.keys.retainAll(valid)
            publishedCells.keys.retainAll(valid)
            metadataPending = MediaGridOrdinalPendingSet(ordinalIndex.assetIdByMediaOrdinal.size)
            bitmapPending = MediaGridOrdinalPendingSet(ordinalIndex.assetIdByMediaOrdinal.size)
            urgentMetadataQueue.clear(); urgentBitmapQueue.clear()
            metadataPending.clear(); bitmapPending.clear()
            valid.forEach { assetId ->
                val record = queueRecords.getOrPut(assetId) { AssetQueueRecord(generation = generation) }
                record.generation = generation
                record.metadataToken++
                record.bitmapToken++
                record.metadataAttempts = 0
                record.metadataStatus = if (metadata.containsKey(assetId)) QueueTaskStatus.Complete else QueueTaskStatus.Unregistered
                record.bitmapStatus = QueueTaskStatus.Unregistered
                if (assetId !in metadata) {
                    states[assetId]?.let { states[assetId] = it.copy(status = MediaGridCellLoadStatus.Pending, prepared = null, candidateIndex = 0) }
                    enqueueMetadataLocked(assetId, ordinalIndex.itemIndexByAssetId.getValue(assetId), LoadLane.Background)
                }
                else if (assetId in states || assetId in metadata) reconcileAssetWorkLocked(assetId, LoadLane.Background)
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
        if (disposed || assetId !in ordinalIndex.mediaOrdinalByAssetId) return
        synchronized(lock) {
            invalidated += assetId
            frameTransitionPreserved.remove(assetId)
            applyInvalidationsLocked()
        }
        reconcilePublishedState(maxNewReady = 0)
        signalAll()
    }

    private suspend fun startupCoordinator() {
        synchronized(lock) { startupState = MediaGridStartupState.PreparingInitialWindow }
        emitEvent(LoadEvent.State(0L, generation))
        val anchor = awaitAnchor()
        if (anchor == null || disposed) return
        val initial = selectMediaGridInitialWarmupIndices(frame, anchor)
        synchronized(lock) {
            startupAssets.clear()
            initial.forEach { itemIndex ->
                val ordinal = frame.ordinalIndex.mediaOrdinalByItemIndex.getOrNull(itemIndex) ?: -1
                if (ordinal >= 0) startupAssets += frame.ordinalIndex.assetIdByMediaOrdinal[ordinal]
            }
            startupAssets.removeAll { it in metadata }
            startupPending = startupAssets.size
            if (startupPending == 0) { startupReady = true; startupSignal.trySend(Unit) }
        }
        synchronized(lock) { startupState = MediaGridStartupState.WarmingInitialWindow }
        emitEvent(LoadEvent.State(0L, generation))
        signalAll()
        withTimeoutOrNull(MEDIA_GRID_STARTUP_TIMEOUT_MS) {
            while (!disposed && !startupReady) {
                if (startupSignal.receiveCatching().isClosed) break
            }
        }
        hasCompletedInitialWarmup = true
        synchronized(lock) {
            startupPublicationBatchPending = true
            startupState = MediaGridStartupState.Ready
        }
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
                retainedImageStore?.updateProtection(ownerToken, snapshot.visibleAssetIds, snapshot.activeAssetIds)
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
            val prepared = try {
                preparer.prepareCellOrNull(
                    frame,
                    task.itemIndex,
                    latestAnchor.get()?.cellSizePx?.takeIf { it > 0 } ?: 256,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            synchronized(lock) {
                metadataRunning--
                if (task.lane == LoadLane.Background) metadataBackgroundRunning--
                metadataRunningAssets.remove(task.assetId)
                val record = queueRecords[task.assetId]
                if (prepared == null) {
                    if (record != null && record.metadataToken == task.token && record.generation == task.generation) {
                        if (record.metadataAttempts < 3) {
                            record.metadataStatus = QueueTaskStatus.Unregistered
                            enqueueMetadataLocked(task.assetId, task.itemIndex, task.lane)
                        } else {
                            record.metadataStatus = QueueTaskStatus.Failed
                            states[task.assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Failed)
                        }
                    }
                } else if (prepared.key == frame.key && task.generation == generation && task.assetId in ordinalIndex.mediaOrdinalByAssetId && record?.metadataToken == task.token) {
                    record.metadataStatus = QueueTaskStatus.Complete
                    record.metadataAttempts = 0
                    metadata[task.assetId] = prepared
                    states[task.assetId] = if (prepared.candidates.isEmpty()) MediaGridCellLoadState(MediaGridCellLoadStatus.Failed, prepared) else MediaGridCellLoadState(prepared = prepared)
                    if (startupAssets.remove(task.assetId)) startupPending--
                    if (startupPending == 0) { startupReady = true; startupSignal.trySend(Unit) }
                    reconcileAssetWorkLocked(task.assetId, task.lane)
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
                bitmapRunningAssets.remove(task.assetId)
                val record = queueRecords[task.assetId]
                if (record?.bitmapToken == task.token && record.generation == task.generation) {
                    record.bitmapStatus = QueueTaskStatus.Complete
                }
                if (task.generation == generation && task.assetId in ordinalIndex.mediaOrdinalByAssetId && record?.bitmapToken == task.token) {
                    val cacheReady = success && candidate != null && markCachedReadyLocked(
                        task.assetId, task.prepared, task.candidateIndex, task.token, task.generation,
                    )
                    if (!cacheReady) {
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
            if (!disposed) reconcilePublishedState(maxNewReady = 0)
        }
    }

    /**
     * Publishes non-image state changes immediately and leaves new Ready attachments pending.
     * The only exception is the initial startup batch, which intentionally retains the old
     * warm-up behavior. This method never starts image work or reads the image cache.
     */
    private fun reconcilePublishedState(maxNewReady: Int) {
        val nextState: MediaGridControllerUiState
        synchronized(lock) {
            if (disposed) return
            val snapshot = activeSnapshot?.takeIf { it.generation == generation }
            val visible = snapshot?.visibleAssetIds ?: longArrayOf()
            val startupBatch = startupState != MediaGridStartupState.Ready || startupPublicationBatchPending
            val previousPublished = publishedCells.toMap()
            val next = LinkedHashMap<Long, MediaGridCellLoadState>(visible.size)
            visible.forEachIndexed { _, assetId ->
                val internal = states[assetId] ?: return@forEachIndexed
                val published = publishedCells[assetId]
                if (internal.status != MediaGridCellLoadStatus.Ready) {
                    if (internal.status == MediaGridCellLoadStatus.Pending && assetId in frameTransitionPreserved && published?.status == MediaGridCellLoadStatus.Ready) {
                        next[assetId] = published
                    } else {
                        next[assetId] = internal
                        frameTransitionPreserved.remove(assetId)
                    }
                    return@forEachIndexed
                }
                frameTransitionPreserved.remove(assetId)
                if (mediaGridSameReadyCandidate(internal, published)) {
                    next[assetId] = published!!
                } else {
                    published?.let { next[assetId] = it }
                }
            }

            if (startupPublicationBatchPending && startupState == MediaGridStartupState.Ready) {
                visible.forEach { assetId ->
                    states[assetId]?.takeIf { it.status == MediaGridCellLoadStatus.Ready }?.let { next[assetId] = it }
                }
            } else if (!startupBatch && maxNewReady > 0) {
                val center = snapshot?.centerMediaOrdinal ?: 0
                mediaGridReadyAttachmentOrder(
                    visible, states, publishedCells, ordinalIndex.mediaOrdinalByAssetId, center,
                ).firstOrNull()?.let { assetId ->
                    states[assetId]?.let { next[assetId] = it }
                }
            }

            publishedCells.clear()
            publishedCells.putAll(next)
            next.forEach { (assetId, state) ->
                if (state.status == MediaGridCellLoadStatus.Ready && !mediaGridSameReadyCandidate(state, previousPublished[assetId])) {
                    state.readyCandidate?.let { candidate ->
                        retainedImageStore?.markDirectDrawEligible(assetId, candidate)
                    }
                }
            }
            if (startupBatch) startupPublicationBatchPending = false
            val demand = !publicationPaused && !disposed && visible.any { assetId ->
                val internal = states[assetId]
                internal?.status == MediaGridCellLoadStatus.Ready &&
                    !mediaGridSameReadyCandidate(internal, publishedCells[assetId]) &&
                    retainedImageStore?.lookupEligibleDrawHandle(assetId) == null
            }
            val old = _uiState.value
            nextState = old.copy(
                startup = startupState,
                cells = publishedCells.toMap(),
                framePublicationDemand = demand,
            )
        }
        if (_uiState.value != nextState) _uiState.value = nextState
        _framePublicationDemand.value = nextState.framePublicationDemand
    }

    /** Called once by the Compose frame runner. It performs no IO or image loading. */
    override fun publishOneReadyImageForFrame() {
        if (publicationPaused || disposed) return
        reconcilePublishedState(maxNewReady = 1)
    }

    private fun nextMetadataTask(): LoadTask? = synchronized(lock) {
        applyInvalidationsLocked()
        val limit = if (paused) MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY else MEDIA_GRID_METADATA_MAX_CONCURRENCY
        if (metadataRunning >= limit) return@synchronized null
        discardStaleUrgentMetadataLocked()
        val urgentAvailable = urgentMetadataQueue.isNotEmpty()
        val canUseUrgent = urgentAvailable && metadataRunning - metadataBackgroundRunning < MEDIA_GRID_URGENT_RESERVED_CONCURRENCY
        if (canUseUrgent) {
            val task = urgentMetadataQueue.first
            val record = queueRecords[task.assetId]
            if (task.assetId in metadataRunningAssets) return@synchronized null
            if (record == null || !mediaGridQueueTokenIsCurrent(record.metadataToken, task.token, record.generation, task.generation) || record.metadataStatus != QueueTaskStatus.UrgentQueued) {
                urgentMetadataQueue.removeFirst()
                if (record?.metadataStatus == QueueTaskStatus.UrgentQueued && record.generation == generation) enqueueMetadataLocked(task.assetId, task.itemIndex, LoadLane.Urgent)
                return@synchronized null
            }
            urgentMetadataQueue.removeFirst()
            record.metadataStatus = QueueTaskStatus.Running
            record.metadataAttempts++
            metadataRunningAssets += task.assetId
            metadataRunning++
            return@synchronized task
        }
        if (metadataBackgroundRunning >= MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY && urgentAvailable) return@synchronized null
        val center = activeSnapshot?.centerMediaOrdinal ?: 0
        while (!metadataPending.isEmpty()) {
            val ordinal = metadataPending.peekNearest(center)
            if (ordinal < 0) break
            val assetId = ordinalIndex.assetIdByMediaOrdinal.getOrNull(ordinal)
            if (assetId == null) { metadataPending.remove(ordinal); continue }
            val itemIndex = ordinalIndex.itemIndexByMediaOrdinal.getOrNull(ordinal)
            if (itemIndex == null) { metadataPending.remove(ordinal); continue }
            val record = queueRecords[assetId]
            if (record == null) { metadataPending.remove(ordinal); continue }
            if (assetId in metadataRunningAssets) return@synchronized null
            if (record.metadataStatus != QueueTaskStatus.BackgroundQueued) { metadataPending.remove(ordinal); continue }
            val task = LoadTask(assetId, itemIndex, LoadLane.Background, generation, record.metadataToken)
            metadataPending.remove(ordinal)
            record.metadataAttempts++
            record.metadataStatus = QueueTaskStatus.Running
            metadataRunningAssets += assetId
            metadataRunning++; metadataBackgroundRunning++
            return@synchronized task
        }
        null
    }

    private fun nextBitmapTask(): BitmapTask? = synchronized(lock) {
        if (bitmapRunning >= MEDIA_GRID_BITMAP_MAX_CONCURRENCY) return@synchronized null
        discardStaleUrgentBitmapLocked()
        val urgentAvailable = urgentBitmapQueue.isNotEmpty()
        if (urgentAvailable && bitmapRunning - bitmapBackgroundRunning < MEDIA_GRID_URGENT_RESERVED_CONCURRENCY) {
            val task = urgentBitmapQueue.first
            val record = queueRecords[task.assetId]
            val candidate = task.prepared.candidates.getOrNull(task.candidateIndex)
            if (task.assetId in bitmapRunningAssets) return@synchronized null
            if (record == null || candidate == null || !mediaGridQueueTokenIsCurrent(record.bitmapToken, task.token, record.generation, task.generation) || record.bitmapStatus != QueueTaskStatus.UrgentQueued) {
                urgentBitmapQueue.removeFirst()
                if (record?.bitmapStatus == QueueTaskStatus.UrgentQueued && record.generation == generation) reconcileAssetWorkLocked(task.assetId, LoadLane.Urgent)
                bitmapWake.trySend(Unit)
                return@synchronized null
            }
            if (isCached(task.assetId, candidate)) {
                urgentBitmapQueue.removeFirst()
                markCachedReadyLocked(task.assetId, task.prepared, task.candidateIndex, task.token, task.generation)
                bitmapWake.trySend(Unit)
                return@synchronized null
            }
            urgentBitmapQueue.removeFirst()
            record?.bitmapStatus = QueueTaskStatus.Running
            bitmapRunning++
            bitmapRunningAssets += task.assetId
            return@synchronized task
        }
        val maxBackground = if (paused) MEDIA_GRID_HIDDEN_BITMAP_MAX_CONCURRENCY else MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY
        if (bitmapBackgroundRunning >= maxBackground || (urgentAvailable && bitmapBackgroundRunning >= MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY)) return@synchronized null
        val center = activeSnapshot?.centerMediaOrdinal ?: 0
        while (!bitmapPending.isEmpty()) {
            val ordinal = bitmapPending.peekNearest(center)
            if (ordinal < 0) break
            val assetId = ordinalIndex.assetIdByMediaOrdinal.getOrNull(ordinal)
            if (assetId == null) { bitmapPending.remove(ordinal); continue }
            val record = queueRecords[assetId]
            if (record == null) { bitmapPending.remove(ordinal); continue }
            if (assetId in bitmapRunningAssets) return@synchronized null
            val prepared = metadata[assetId]
            if (prepared == null) { bitmapPending.remove(ordinal); continue }
            val candidate = prepared.candidates.getOrNull(record.candidateIndex)
            if (record.bitmapStatus != QueueTaskStatus.BackgroundQueued || candidate == null || candidate.sourceIdentity != record.sourceIdentity) {
                bitmapPending.remove(ordinal)
                if (record.bitmapStatus == QueueTaskStatus.BackgroundQueued) bitmapPending.add(ordinal)
                continue
            }
            if (isCached(assetId, candidate)) {
                markCachedReadyLocked(assetId, prepared, record.candidateIndex, record.bitmapToken, generation)
                continue
            }
            if (!mediaGridBackgroundBitmapAllowed(memoryUsed(), memoryMax(), mediaGridEstimatedBitmapBytes(candidate))) return@synchronized null
            bitmapPending.remove(ordinal)
            record.bitmapStatus = QueueTaskStatus.Running
            bitmapRunning++; bitmapBackgroundRunning++
            bitmapRunningAssets += assetId
            return@synchronized BitmapTask(assetId, prepared, record.candidateIndex, LoadLane.Background, generation, record.bitmapToken)
        }
        null
    }

    private fun discardStaleUrgentMetadataLocked() {
        while (urgentMetadataQueue.isNotEmpty()) {
            val task = urgentMetadataQueue.first
            val record = queueRecords[task.assetId]
            if (record != null && mediaGridQueueTokenIsCurrent(record.metadataToken, task.token, record.generation, task.generation) && record.metadataStatus == QueueTaskStatus.UrgentQueued) return
            urgentMetadataQueue.removeFirst()
        }
    }

    private fun discardStaleUrgentBitmapLocked() {
        while (urgentBitmapQueue.isNotEmpty()) {
            val task = urgentBitmapQueue.first
            val record = queueRecords[task.assetId]
            if (record != null && mediaGridQueueTokenIsCurrent(record.bitmapToken, task.token, record.generation, task.generation) && record.bitmapStatus == QueueTaskStatus.UrgentQueued) return
            urgentBitmapQueue.removeFirst()
            if (record?.bitmapStatus == QueueTaskStatus.UrgentQueued && record.generation == generation) {
                reconcileAssetWorkLocked(task.assetId, LoadLane.Urgent)
            }
        }
    }

    private fun queueFirstBitmapLocked(assetId: Long, prepared: MediaGridPreparedImage, originalLane: LoadLane) {
        reconcileAssetWorkLocked(assetId, originalLane)
    }

    private fun enqueueVisiblePreparedLocked(snapshot: MediaGridActiveWindowSnapshot) {
        snapshot.activeAssetIds.forEach { assetId ->
            val state = states[assetId] ?: return@forEach
            val prepared = state.prepared ?: metadata[assetId] ?: return@forEach
            reconcileAssetWorkLocked(assetId, LoadLane.Urgent)
        }
    }

    /** Completes a bitmap without starting an ImageRequest. Caller must hold lock. */
    private fun markCachedReadyLocked(
        assetId: Long,
        prepared: MediaGridPreparedImage,
        candidateIndex: Int,
        token: Long,
        taskGeneration: Long,
    ): Boolean {
        val record = queueRecords[assetId] ?: return false
        val candidate = prepared.candidates.getOrNull(candidateIndex) ?: return false
        if (taskGeneration != generation || record.generation != taskGeneration || record.bitmapToken != token || !isCached(assetId, candidate)) return false
        record.candidateIndex = candidateIndex
        record.sourceIdentity = candidate.sourceIdentity
        record.bitmapStatus = QueueTaskStatus.Complete
        val visibleNow = activeSnapshot?.visibleAssetIds?.any { it == assetId } == true
        bitmapGateway.retain(assetId, candidate, directDrawEligible = !visibleNow)
        ordinalIndex.mediaOrdinalByAssetId[assetId]?.let { bitmapPending.remove(it) }
        urgentBitmapQueue.removeAll { it.assetId == assetId }
        states[assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Ready, prepared, candidateIndex)
        emitEvent(LoadEvent.CacheSignal(assetId, taskGeneration))
        return true
    }

    /** Restores exactly the work required by one asset without scanning all assets. Caller must hold lock. */
    private fun reconcileAssetWorkLocked(assetId: Long, preferredLane: LoadLane = LoadLane.Background) {
        val record = queueRecords[assetId] ?: return
        if (record.generation != generation) return
        if (record.metadataStatus == QueueTaskStatus.UrgentQueued && urgentMetadataQueue.none { it.assetId == assetId && it.token == record.metadataToken && it.generation == generation }) {
            record.metadataStatus = QueueTaskStatus.Unregistered
        }
        if (record.bitmapStatus == QueueTaskStatus.UrgentQueued && urgentBitmapQueue.none { it.assetId == assetId && it.token == record.bitmapToken && it.generation == generation }) {
            record.bitmapStatus = QueueTaskStatus.Unregistered
        }
        val prepared = metadata[assetId]
        if (prepared == null) {
            if (record.metadataStatus == QueueTaskStatus.Failed) {
                states[assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Failed)
                return
            }
            val itemIndex = ordinalIndex.itemIndexByAssetId[assetId] ?: return
            if (record.metadataStatus !in setOf(QueueTaskStatus.Running, QueueTaskStatus.BackgroundQueued, QueueTaskStatus.UrgentQueued)) {
                enqueueMetadataLocked(assetId, itemIndex, if (activeSnapshot?.isActive(assetId, ordinalIndex.mediaOrdinalByAssetId) == true) LoadLane.Urgent else preferredLane)
            }
            return
        }
        if (prepared.candidates.isEmpty()) {
            record.bitmapStatus = QueueTaskStatus.Complete
            states[assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Failed, prepared)
            return
        }
        val firstLocalIndex = prepared.candidates.indexOfFirst(::isLocalCandidate)
        val currentIndex = states[assetId]?.candidateIndex ?: if (record.bitmapStatus == QueueTaskStatus.Unregistered) firstLocalIndex else record.candidateIndex
        val candidateIndex = currentIndex.coerceIn(0, prepared.candidates.lastIndex)
        val candidate = prepared.candidates[candidateIndex]
        record.candidateIndex = candidateIndex
        record.sourceIdentity = candidate.sourceIdentity
        if (isCached(assetId, candidate)) {
            record.bitmapToken++
            markCachedReadyLocked(assetId, prepared, candidateIndex, record.bitmapToken, generation)
            return
        }
        if (states[assetId]?.status == MediaGridCellLoadStatus.Ready) {
            states[assetId] = states[assetId]!!.copy(status = MediaGridCellLoadStatus.Pending, prepared = prepared, candidateIndex = candidateIndex)
        } else if (states[assetId] == null || states[assetId]?.prepared == null) {
            states[assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Pending, prepared, candidateIndex)
        }
        val active = activeSnapshot?.isActive(assetId, ordinalIndex.mediaOrdinalByAssetId) == true
        val lane = if (active) LoadLane.Urgent else preferredLane
        if (!isLocalCandidate(candidate) && !active) return
        if (!mediaGridBitmapQueueEntryMatches(record.bitmapStatus, record.candidateIndex, record.sourceIdentity, candidateIndex, candidate.sourceIdentity)) {
            addBitmapTaskLocked(assetId, prepared, candidateIndex, lane)
        }
    }

    private fun enqueueMetadataLocked(assetId: Long, itemIndex: Int, lane: LoadLane) {
        val record = queueRecords.getOrPut(assetId) { AssetQueueRecord(generation = generation) }
        if (record.generation != generation) { record.generation = generation; record.metadataToken++ }
        if (record.metadataStatus == QueueTaskStatus.Running || record.metadataStatus == QueueTaskStatus.Complete) return
        if (record.metadataStatus == QueueTaskStatus.BackgroundQueued && lane == LoadLane.Background) return
        record.metadataToken++
        record.metadataStatus = if (lane == LoadLane.Urgent) QueueTaskStatus.UrgentQueued else QueueTaskStatus.BackgroundQueued
        val task = LoadTask(assetId, itemIndex, lane, generation, record.metadataToken)
        val ordinal = ordinalIndex.mediaOrdinalByAssetId[assetId] ?: return
        if (lane == LoadLane.Urgent) {
            metadataPending.remove(ordinal)
            urgentMetadataQueue.addLast(task)
        } else {
            metadataPending.add(ordinal)
        }
    }

    private fun addBitmapTaskLocked(assetId: Long, prepared: MediaGridPreparedImage, candidateIndex: Int, lane: LoadLane) {
        val candidate = prepared.candidates.getOrNull(candidateIndex) ?: return
        val record = queueRecords.getOrPut(assetId) { AssetQueueRecord(generation = generation) }
        val urgent = lane == LoadLane.Urgent || activeSnapshot?.isActive(assetId, ordinalIndex.mediaOrdinalByAssetId) == true
        val normalizedLane = if (urgent) LoadLane.Urgent else LoadLane.Background
        if (mediaGridBitmapQueueEntryMatches(record.bitmapStatus, record.candidateIndex, record.sourceIdentity, candidateIndex, candidate.sourceIdentity)) return
        record.bitmapToken++
        record.bitmapStatus = if (normalizedLane == LoadLane.Urgent) QueueTaskStatus.UrgentQueued else QueueTaskStatus.BackgroundQueued
        record.generation = generation
        record.candidateIndex = candidateIndex
        record.sourceIdentity = candidate.sourceIdentity
        val ordinal = ordinalIndex.mediaOrdinalByAssetId[assetId] ?: return
        if (normalizedLane == LoadLane.Urgent) {
            bitmapPending.remove(ordinal)
            urgentBitmapQueue.addLast(BitmapTask(assetId, prepared, candidateIndex, normalizedLane, generation, record.bitmapToken))
        } else {
            bitmapPending.add(ordinal)
        }
    }

    private fun applyInvalidationsLocked() {
        if (invalidated.isEmpty()) return
        invalidated.forEach { assetId ->
            retainedImageStore?.invalidateAsset(assetId)
            metadata.remove(assetId)
            states[assetId] = MediaGridCellLoadState()
            ordinalIndex.mediaOrdinalByAssetId[assetId]?.let { ordinal ->
                metadataPending.remove(ordinal)
                bitmapPending.remove(ordinal)
            }
            val record = queueRecords.getOrPut(assetId) { AssetQueueRecord(generation = generation) }
            record.metadataToken++
            record.bitmapToken++
            record.metadataAttempts = 0
            record.metadataStatus = QueueTaskStatus.Unregistered
            record.bitmapStatus = QueueTaskStatus.Unregistered
            ordinalIndex.itemIndexByAssetId[assetId]?.let { itemIndex ->
                enqueueMetadataLocked(assetId, itemIndex, if (activeSnapshot?.isActive(assetId, ordinalIndex.mediaOrdinalByAssetId) == true) LoadLane.Urgent else LoadLane.Background)
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
                val ordinal = ordinalIndex.mediaOrdinalByAssetId[assetId] ?: return@forEach
                metadataPending.remove(ordinal)
                urgentMetadataQueue.addLast(LoadTask(assetId, ordinalIndex.itemIndexByAssetId.getValue(assetId), LoadLane.Urgent, generation, record.metadataToken))
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
                val ordinal = ordinalIndex.mediaOrdinalByAssetId[assetId] ?: return@forEach
                bitmapPending.remove(ordinal)
                urgentBitmapQueue.addLast(BitmapTask(assetId, prepared, record.candidateIndex, LoadLane.Urgent, generation, record.bitmapToken))
            }
        }
    }

    private fun isLocalCandidate(candidate: MediaGridPreparedCandidate): Boolean = candidate.kind in setOf(MediaGridImageSourceKind.Rgb565Pack, MediaGridImageSourceKind.PersistentPreview, MediaGridImageSourceKind.Local)
    private fun isCached(assetId: Long, candidate: MediaGridPreparedCandidate): Boolean = bitmapGateway.isCached(assetId, candidate)
    private fun estimatedBytes(task: BitmapTask): Long = task.prepared.candidates.getOrNull(task.candidateIndex)?.let { mediaGridEstimatedBitmapBytes(it) } ?: 0L
    private fun memoryUsed(): Int = imageLoader.memoryCache?.size ?: 0
    private fun memoryMax(): Int = imageLoader.memoryCache?.maxSize ?: 0
    private fun memoryWatermarkOpen(): Boolean = mediaGridMemoryWatermarkAllowsResume(memoryUsed(), memoryMax())

    private suspend fun loadCandidate(candidate: MediaGridPreparedCandidate): Boolean = bitmapGateway.load(candidate)

    internal fun stateSnapshot(): MediaGridControllerStateSnapshot = synchronized(lock) {
        MediaGridControllerStateSnapshot(
            cells = states.toMap(),
            publishedCells = publishedCells.toMap(),
            framePublicationDemand = framePublicationDemandLocked(),
            metadata = metadata.toMap(),
            records = queueRecords.mapValues { (_, value) -> value.copy() },
            metadataPendingOrdinals = metadataPending.snapshot(),
            bitmapPendingOrdinals = bitmapPending.snapshot(),
            urgentMetadataAssetIds = urgentMetadataQueue.map { it.assetId },
            urgentBitmapAssetIds = urgentBitmapQueue.map { it.assetId },
            metadataRunningAssetIds = metadataRunningAssets.toSet(),
            bitmapRunningAssetIds = bitmapRunningAssets.toSet(),
            metadataRunning = metadataRunning,
            bitmapRunning = bitmapRunning,
            generation = generation,
            visibleAssetIds = activeSnapshot?.visibleAssetIds?.toSet() ?: emptySet(),
        )
    }

    private fun framePublicationDemandLocked(): Boolean {
        val visible = activeSnapshot?.takeIf { it.generation == generation }?.visibleAssetIds ?: return false
        if (publicationPaused || disposed) return false
        return visible.any { assetId ->
            val internal = states[assetId]
            internal?.status == MediaGridCellLoadStatus.Ready &&
                !mediaGridSameReadyCandidate(internal, publishedCells[assetId]) &&
                retainedImageStore?.lookupEligibleDrawHandle(assetId) == null
        }
    }

    private fun emitEvent(event: LoadEvent) { if (!disposed) events.trySend(event) }
    private fun signalAll() { metadataWake.trySend(Unit); bitmapWake.trySend(Unit); memorySignal.trySend(Unit) }
    fun dispose() {
        disposed = true; generation++
        retainedImageStore?.removeOwner(ownerToken)
        workerJobs.forEach(Job::cancel)
        scope.cancel()
        anchorSignal.close(); initialAnchorSignal.close(); metadataWake.close(); bitmapWake.close(); memorySignal.close(); startupSignal.close(); events.close()
        synchronized(lock) { urgentMetadataQueue.clear(); urgentBitmapQueue.clear(); metadataPending.clear(); bitmapPending.clear(); metadata.clear(); states.clear(); publishedCells.clear(); frameTransitionPreserved.clear(); queueRecords.clear() }
        _uiState.value = _uiState.value.copy(cells = emptyMap(), framePublicationDemand = false)
        _framePublicationDemand.value = false
    }
}
