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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val MEDIA_GRID_CONTROLLER_TICK_MS = 50L
internal const val MEDIA_GRID_METADATA_PER_TICK = 2
internal const val MEDIA_GRID_REQUESTS_PER_TICK = 1
internal const val MEDIA_GRID_COMPLETIONS_PER_TICK = 4
internal const val MEDIA_GRID_MAX_REQUESTS = 2
internal const val MEDIA_GRID_WARMUP_ROWS = 6
internal const val MEDIA_GRID_WARMUP_MAX_ASSETS = 96
internal const val MEDIA_GRID_WARMUP_MAX_BYTES = 24L * 1024L * 1024L

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
    val visibleRows = kotlin.math.ceil(anchor.viewportHeightPx.toDouble() / anchor.cellSizePx).toInt() + 1
    val wanted = minOf(
        MEDIA_GRID_WARMUP_MAX_ASSETS,
        (MEDIA_GRID_WARMUP_MAX_BYTES / (256L * 256L * 4L)).toInt(),
        (visibleRows + MEDIA_GRID_WARMUP_ROWS * 2) * anchor.columnCount,
    )
    val centerItem = (anchor.firstVisibleItemIndex + anchor.lastVisibleItemIndex) / 2
    val center = lowerBoundMedia(frame.mediaCellIndices, centerItem)
    var left = (center - wanted / 2).coerceAtLeast(0)
    val end = (left + wanted).coerceAtMost(frame.mediaCellIndices.size)
    left = (end - wanted).coerceAtLeast(0)
    val result = IntArray(end - left)
    val middle = lowerBoundMedia(frame.mediaCellIndices, centerItem).coerceIn(left, (end - 1).coerceAtLeast(left))
    var output = 0
    var distance = 0
    while (output < result.size) {
        val right = middle + distance
        if (right in left until end) result[output++] = frame.mediaCellIndices[right]
        if (distance > 0) {
            val leftIndex = middle - distance
            if (leftIndex in left until end && output < result.size) result[output++] = frame.mediaCellIndices[leftIndex]
        }
        distance++
    }
    return result
}

internal fun selectMediaGridActiveWindow(frame: MediaGridFrameData, anchor: MediaGridViewportAnchor): Set<Int> {
    if (anchor.visibleMediaItemIndices.isEmpty() || anchor.columnCount <= 0) return emptySet()
    val first = lowerBoundMedia(frame.mediaCellIndices, anchor.visibleMediaItemIndices.min())
    val last = upperBoundMedia(frame.mediaCellIndices, anchor.visibleMediaItemIndices.max())
    val from = (first - anchor.columnCount).coerceAtLeast(0)
    val to = (last + anchor.columnCount).coerceAtMost(frame.mediaCellIndices.size)
    return frame.mediaCellIndices.copyOfRange(from, to).toSet()
}

internal fun <T> retainMediaGridActiveLoadStates(states: Map<Long, T>, activeAssetIds: Set<Long>): Map<Long, T> =
    states.filterKeys(activeAssetIds::contains)

private fun lowerBoundMedia(values: IntArray, target: Int): Int { var l=0; var h=values.size; while(l<h){val m=(l+h) ushr 1;if(values[m]<target)l=m+1 else h=m};return l }
private fun upperBoundMedia(values: IntArray, target: Int): Int { var l=0; var h=values.size; while(l<h){val m=(l+h) ushr 1;if(values[m]<=target)l=m+1 else h=m};return l }

internal class MediaGridSteadyLoadController(
    private val context: Context,
    private val frame: MediaGridFrameData,
    private val preparer: MediaGridImagePreparer,
    private val imageLoader: ImageLoader,
    private val onPersistentPreviewError: suspend (Long, MediaGridPreparedCandidate) -> Unit,
) {
    private data class Completion(val generation: Long, val assetId: Long, val candidateIndex: Int, val success: Boolean)
    private data class ActiveRequest(val disposable: Disposable, val itemIndex: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val latestAnchor = AtomicReference<MediaGridViewportAnchor?>()
    private val metadata = HashMap<Long, MediaGridPreparedImage>()
    private val states = HashMap<Long, MediaGridCellLoadState>()
    private val completions = ConcurrentHashMap<Long, Completion>()
    private val requests = HashMap<Long, ActiveRequest>()
    private val invalidated = ConcurrentHashMap.newKeySet<Long>()
    private val itemIndexByAsset = frame.mediaCellIndices.associateBy(
        keySelector = { (frame.items[it] as MediaGridCellItem).entry.assetId },
        valueTransform = { it },
    )
    private val _uiState = MutableStateFlow(MediaGridControllerUiState())
    val uiState: StateFlow<MediaGridControllerUiState> = _uiState.asStateFlow()
    private var generation = 1L
    private var loopJob: Job? = null
    private var cursorCenter = 0
    private var cursorDistance = 0
    private var cursorRight = true
    @Volatile private var disposed = false

    fun updateViewport(anchor: MediaGridViewportAnchor) { if (!disposed && anchor.renderKey == frame.key) latestAnchor.set(anchor) }

    fun start() { if (loopJob == null) loopJob = scope.launch { awaitInitialAnchorAndWarm(); runLoop() } }

    fun invalidate(assetId: Long) { if (!disposed && assetId in itemIndexByAsset) invalidated += assetId }

    private suspend fun awaitInitialAnchorAndWarm() {
        _uiState.value = MediaGridControllerUiState(MediaGridStartupState.PreparingInitialWindow)
        var anchor: MediaGridViewportAnchor?
        do { delay(10); anchor = latestAnchor.get() } while (anchor == null || anchor!!.cellSizePx <= 0)
        val initial = selectMediaGridInitialWarmupIndices(frame, anchor!!)
        for (index in initial) prepareIndex(index, anchor!!.cellSizePx)
        _uiState.value = MediaGridControllerUiState(MediaGridStartupState.WarmingInitialWindow)
        val warmCandidates = initial.asSequence().mapNotNull { index ->
            val cell = frame.items[index] as? MediaGridCellItem ?: return@mapNotNull null
            metadata[cell.entry.assetId]?.candidates?.firstOrNull { it.kind == MediaGridImageSourceKind.PersistentPreview }
                ?.let { cell.entry.assetId to it }
        }.distinctBy { it.second.cacheKey }.toList()
        withTimeoutOrNull(3_000L) {
            val pending = ArrayDeque(warmCandidates)
            while (pending.isNotEmpty() || requests.isNotEmpty()) {
                while (requests.size < MEDIA_GRID_MAX_REQUESTS && pending.isNotEmpty()) {
                    val (assetId, candidate) = pending.removeFirst()
                    if (isCached(candidate)) states[assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Ready, metadata[assetId], 0)
                    else startRequest(assetId, metadata[assetId]!!, 0)
                }
                applyCompletions(Int.MAX_VALUE)
                delay(10)
            }
        }
        applyCompletions(Int.MAX_VALUE)
        _uiState.value = MediaGridControllerUiState(MediaGridStartupState.Ready, states.toMap())
    }

    private suspend fun runLoop() {
        while (true) {
            val anchor = latestAnchor.get()
            if (anchor != null) tick(anchor)
            delay(MEDIA_GRID_CONTROLLER_TICK_MS)
        }
    }

    internal suspend fun tick(anchor: MediaGridViewportAnchor) {
        invalidated.toList().forEach { assetId ->
            invalidated.remove(assetId); metadata.remove(assetId); requests.remove(assetId)?.disposable?.dispose()
            states[assetId] = MediaGridCellLoadState()
        }
        val active = selectMediaGridActiveWindow(frame, anchor)
        val activeAssetIds = active.mapTo(HashSet(active.size)) { (frame.items[it] as MediaGridCellItem).entry.assetId }
        requests.entries.toList().filter { it.value.itemIndex !in active }.forEach { (assetId, request) ->
            request.disposable.dispose(); requests.remove(assetId); states[assetId] = states[assetId]?.copy(status = MediaGridCellLoadStatus.Pending) ?: MediaGridCellLoadState()
        }
        states.keys.retainAll(activeAssetIds)
        applyCompletions(MEDIA_GRID_COMPLETIONS_PER_TICK)
        resetCursor(anchor)
        val orderedActive = active.sortedBy { kotlin.math.abs(it - (anchor.firstVisibleItemIndex + anchor.lastVisibleItemIndex) / 2) }
        val activeMissing = ArrayDeque(orderedActive.filter { index ->
            val assetId = (frame.items[index] as MediaGridCellItem).entry.assetId
            assetId !in metadata
        })
        repeat(MEDIA_GRID_METADATA_PER_TICK) {
            val index = if (activeMissing.isNotEmpty()) activeMissing.removeFirst() else nextUnpreparedIndex()
            index?.let { prepareIndex(it, anchor.cellSizePx) }
        }
        orderedActive.forEach { index ->
            val assetId = (frame.items[index] as MediaGridCellItem).entry.assetId
            val prepared = metadata[assetId]
            if (prepared != null && assetId !in states) {
                states[assetId] = if (prepared.candidates.isEmpty()) MediaGridCellLoadState(MediaGridCellLoadStatus.Failed, prepared)
                else MediaGridCellLoadState(prepared = prepared)
            }
        }
        for (itemIndex in orderedActive) {
            if (requests.size >= MEDIA_GRID_MAX_REQUESTS) break
            val cell = frame.items[itemIndex] as? MediaGridCellItem ?: continue
            val state = states[cell.entry.assetId] ?: continue
            val candidate = state.prepared?.candidates?.getOrNull(state.candidateIndex) ?: continue
            if (state.status == MediaGridCellLoadStatus.Ready && !isCached(candidate)) states[cell.entry.assetId] = state.copy(status = MediaGridCellLoadStatus.Pending)
        }
        val next = orderedActive.asSequence().mapNotNull { frame.items[it] as? MediaGridCellItem }
            .firstOrNull { states[it.entry.assetId]?.status == MediaGridCellLoadStatus.Pending && metadata[it.entry.assetId]?.candidates?.isNotEmpty() == true }
        if (next != null && requests.size < MEDIA_GRID_MAX_REQUESTS) {
            val state = states[next.entry.assetId]!!
            startRequest(next.entry.assetId, state.prepared!!, state.candidateIndex)
        }
        publish()
    }

    private fun resetCursor(anchor: MediaGridViewportAnchor) {
        val center = lowerBoundMedia(frame.mediaCellIndices, (anchor.firstVisibleItemIndex + anchor.lastVisibleItemIndex) / 2)
        if (center != cursorCenter) { cursorCenter = center; cursorDistance = 0; cursorRight = true }
    }

    private fun nextUnpreparedIndex(): Int? {
        while (cursorDistance <= frame.mediaCellIndices.size) {
            val offset = if (cursorDistance == 0) 0 else if (cursorRight) cursorDistance else -cursorDistance
            if (cursorDistance > 0) { if (!cursorRight) cursorDistance++; cursorRight = !cursorRight } else cursorDistance++
            val pos = cursorCenter + offset
            if (pos in frame.mediaCellIndices.indices) {
                val index = frame.mediaCellIndices[pos]
                val asset = (frame.items[index] as MediaGridCellItem).entry.assetId
                if (asset !in metadata) return index
            }
        }
        return null
    }

    private suspend fun prepareIndex(itemIndex: Int, cellSize: Int) {
        if (cellSize <= 0) return
        val prepared = preparer.prepareCell(frame, itemIndex, cellSize)
        if (prepared.key != frame.key) return
        metadata[prepared.assetId] = prepared
        states[prepared.assetId] = if (prepared.candidates.isEmpty()) {
            MediaGridCellLoadState(MediaGridCellLoadStatus.Failed, prepared)
        } else {
            MediaGridCellLoadState(prepared = prepared)
        }
    }

    private fun isCached(candidate: MediaGridPreparedCandidate) = imageLoader.memoryCache?.get(MemoryCache.Key(candidate.cacheKey)) != null

    private fun startRequest(assetId: Long, prepared: MediaGridPreparedImage, candidateIndex: Int) {
        val candidate = prepared.candidates.getOrNull(candidateIndex) ?: run { states[assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Failed, prepared, candidateIndex); return }
        if (isCached(candidate)) { states[assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Ready, prepared, candidateIndex); return }
        val requestGeneration = generation
        val request = buildMediaGridImageRequest(context, candidate).newBuilder().listener(object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                if (!disposed) completions[assetId] = Completion(requestGeneration, assetId, candidateIndex, true)
            }
            override fun onError(request: ImageRequest, result: ErrorResult) {
                if (!disposed) completions[assetId] = Completion(requestGeneration, assetId, candidateIndex, false)
            }
            override fun onCancel(request: ImageRequest) = Unit
        }).build()
        val disposable = imageLoader.enqueue(request)
        requests[assetId] = ActiveRequest(disposable, prepared.itemIndex)
        states[assetId] = MediaGridCellLoadState(MediaGridCellLoadStatus.Loading, prepared, candidateIndex)
    }

    private fun applyCompletions(limit: Int) {
        completions.entries.take(limit).forEach { (assetId, event) ->
            if (!completions.remove(assetId, event) || event.generation != generation) return@forEach
            requests.remove(assetId)
            val old = states[assetId] ?: return@forEach
            val candidate = old.prepared?.candidates?.getOrNull(event.candidateIndex)
            if (event.success && candidate != null && isCached(candidate)) states[assetId] = old.copy(status = MediaGridCellLoadStatus.Ready)
            else {
                if (candidate?.kind == MediaGridImageSourceKind.PersistentPreview) scope.launch { onPersistentPreviewError(assetId, candidate) }
                val next = event.candidateIndex + 1
                states[assetId] = if (old.prepared?.candidates?.getOrNull(next) != null) old.copy(status = MediaGridCellLoadStatus.Pending, candidateIndex = next)
                else old.copy(status = MediaGridCellLoadStatus.Failed)
            }
        }
    }

    private suspend fun publish() = withContext(Dispatchers.Main.immediate) { _uiState.value = MediaGridControllerUiState(MediaGridStartupState.Ready, states.toMap()) }

    fun dispose() { disposed = true; generation++; requests.values.forEach { it.disposable.dispose() }; requests.clear(); completions.clear(); metadata.clear(); states.clear(); scope.cancel() }
}
