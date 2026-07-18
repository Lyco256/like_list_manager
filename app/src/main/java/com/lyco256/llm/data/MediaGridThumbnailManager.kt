package com.lyco256.llm.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import kotlin.math.sign

sealed interface MediaGridThumbnailState { data object Waiting : MediaGridThumbnailState; data object Generating : MediaGridThumbnailState; data class Ready(val file: File) : MediaGridThumbnailState; data object Failed : MediaGridThumbnailState }

private data class ViewportInputs(
    val sourceRevision: Long,
    val sources: List<MediaGridThumbnailSource>,
    val sourceByAssetId: Map<Long, MediaGridThumbnailSource>,
    val sourceIndex: Map<Long, Int>,
    val previousCenterIndex: Float,
)

private data class ViewportPlan(
    val sourceRevision: Long,
    val columnCount: Int,
    val direction: Int,
    val centerIndex: Float,
    val visible: Map<Long, Int>,
    val first: Int,
    val last: Int,
    val uiAssetIds: Set<Long>,
)

private data class SchedulerInput(
    val sources: List<MediaGridThumbnailSource>,
    val visible: Map<Long, Int>,
    val first: Int,
    val last: Int,
    val columns: Int,
    val direction: Int,
    val centerIndex: Float,
    val foreground: Boolean,
    val completedKeys: Set<String>,
    val workStates: Map<Long, MediaGridThumbnailState>,
)

private data class ThumbnailCandidate(val source: MediaGridThumbnailSource, val wide: Boolean)

/** One serial worker. The queue is intentionally not materialized: every completion re-selects from the latest viewport. */
class MediaGridThumbnailManager(
    private val store: MediaGridThumbnailStore,
    private val scope: CoroutineScope,
) {
    private class Work(var source: MediaGridThumbnailSource, val state: MutableStateFlow<MediaGridThumbnailState>) {
        var generation: Long = 0L
    }
    private val works = LinkedHashMap<Long, Work>()
    private var sources = emptyList<MediaGridThumbnailSource>()
    private var sourceRevision: Long? = null
    private var sourceByAssetId = emptyMap<Long, MediaGridThumbnailSource>()
    private var sourceIndex = emptyMap<Long, Int>()
    private var visible = emptyMap<Long, Int>()
    private var first = 0
    private var last = -1
    private var columns = 1
    private var direction = 0
    private var centerIndex = 0f
    private val completedKeys = mutableSetOf<String>()
    private val displayRetries = mutableMapOf<String, Int>()
    private var foreground = true
    private var job: Job? = null
    private var schedulerJob: Job? = null
    private var viewportActive = false
    private var viewportDispatcher: LatestValueDispatcher<MediaGridViewportSnapshot>? = null
    private val viewportRevisionGate = MediaGridViewportRevisionGate()

    fun updateSourceSnapshot(revision: Long, ordered: List<MediaGridThumbnailSource>) {
        val changed = synchronized(this) {
            viewportActive = true
            ensureViewportDispatcherLocked()
            viewportRevisionGate.setSourceRevision(revision)
            val nextSources = ordered.distinctBy { it.assetId }
            if (sourceRevision == revision && sources == nextSources) {
                false
            } else {
                sourceRevision = revision
                sources = nextSources
                sourceByAssetId = sources.associateBy { it.assetId }
                sourceIndex = sources.mapIndexed { i, source -> source.assetId to i }.toMap()
                works.entries.removeIf { it.key !in sourceIndex }
                sources.forEach { source ->
                    val current = works[source.assetId]
                    if (current != null && current.source != source) {
                        current.source = source
                        current.generation++
                        current.state.value = initialState(source)
                    }
                }
                true
            }
        }
        if (changed) requestWorkSchedule()
    }

    fun dispatchViewport(snapshot: MediaGridViewportSnapshot): Boolean {
        val dispatcher = synchronized(this) {
            if (!viewportActive || sourceRevision != snapshot.sourceRevision) return false
            ensureViewportDispatcherLocked()
        }
        return dispatcher.dispatch(snapshot)
    }

    fun disposeViewport() {
        val dispatcher = synchronized(this) {
            val current = viewportDispatcher
            viewportDispatcher = null
            viewportActive = false
            visible = emptyMap()
            first = 0
            last = -1
            schedulerJob?.cancel()
            schedulerJob = null
            job?.cancel()
            job = null
            current
        }
        dispatcher?.close()
    }

    private suspend fun processViewportSnapshot(snapshot: MediaGridViewportSnapshot) {
        val plan = buildViewportPlan(snapshot) ?: return
        val schedulerInput = synchronized(this) {
            if (!viewportActive || sourceRevision != plan.sourceRevision) return
            columns = plan.columnCount
            direction = plan.direction
            centerIndex = plan.centerIndex
            visible = plan.visible
            first = plan.first
            last = plan.last
            visible.keys.forEach(::ensureWork)
            plan.uiAssetIds.forEach(::ensureWork)
            pruneUiState(plan.uiAssetIds)
            schedulerInputLocked()
        }
        val candidate = selectNextWork(schedulerInput)
        synchronized(this) {
            if (!viewportActive || sourceRevision != plan.sourceRevision || job != null) return
            if (visible != plan.visible || first != plan.first || last != plan.last || columns != plan.columnCount) return
            startCandidateLocked(candidate)
        }
    }

    private fun buildViewportPlan(snapshot: MediaGridViewportSnapshot): ViewportPlan? {
        val inputs = synchronized(this) {
            if (!viewportActive || sourceRevision != snapshot.sourceRevision || !viewportRevisionGate.shouldApply(snapshot)) return null
            ViewportInputs(
                sourceRevision = sourceRevision ?: return null,
                sources = sources,
                sourceByAssetId = sourceByAssetId,
                sourceIndex = sourceIndex,
                previousCenterIndex = centerIndex,
            )
        }
        val visibleEntries = snapshot.items.mapNotNull { item ->
            val source = inputs.sourceByAssetId[item.assetId]
                ?.takeIf { inputs.sourceIndex[item.assetId] == item.sourceIndex }
                ?: return@mapNotNull null
            item to source
        }
        val visible = visibleEntries.associate { (item, source) -> source.assetId to item.centerDistance }
        val indices = visibleEntries.map { it.first.sourceIndex }
        val first = indices.minOrNull() ?: 0
        val last = indices.maxOrNull() ?: -1
        val centerIndex = visibleEntries.map { it.first.sourceIndex }.average().takeUnless { it.isNaN() }?.toFloat()
            ?: inputs.previousCenterIndex
        return ViewportPlan(
            sourceRevision = inputs.sourceRevision,
            columnCount = snapshot.columnCount.coerceAtLeast(1),
            direction = (centerIndex - inputs.previousCenterIndex).sign.toInt(),
            centerIndex = centerIndex,
            visible = visible,
            first = first,
            last = last,
            uiAssetIds = mediaGridViewportUiIndices(first, last, snapshot.columnCount, inputs.sources.size)
                .mapNotNull { index -> inputs.sources.getOrNull(index)?.assetId }
                .toSet(),
        )
    }

    private fun schedulerInputLocked(): SchedulerInput = SchedulerInput(
        sources = sources,
        visible = visible,
        first = first,
        last = last,
        columns = columns,
        direction = direction,
        centerIndex = (first + last) / 2f,
        foreground = foreground,
        completedKeys = completedKeys.toSet(),
        workStates = works.mapValues { (_, work) -> work.state.value },
    )

    private fun selectNextWork(input: SchedulerInput): ThumbnailCandidate? {
        if (!input.foreground || input.last < input.first) return null
        val sourceByAssetId = input.sources.associateBy { it.assetId }
        val uiIds = mediaGridViewportUiIndices(input.first, input.last, input.columns, input.sources.size)
            .mapNotNull { input.sources.getOrNull(it)?.assetId }
            .toSet()
        val visibleCandidate = input.visible.entries.asSequence()
            .sortedWith(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { id ->
                val source = sourceByAssetId[id.key]
                if (source?.previewUrl == null && source?.remoteUrl == null) 0 else 1
            })
            .mapNotNull { (id, _) -> sourceByAssetId[id] }
            .firstOrNull { isWaiting(input, it) }
        if (visibleCandidate != null) return ThumbnailCandidate(visibleCandidate, wide = false)

        val adjacentCandidate = (input.first - input.columns..input.last + input.columns)
            .filter { it in input.sources.indices }
            .filter { input.sources[it].assetId !in input.visible }
            .sortedWith(compareBy<Int> { kotlin.math.abs(it - input.centerIndex) }.thenBy { if (input.direction > 0) -it else it })
            .map { input.sources[it] }
            .firstOrNull { it.assetId in uiIds && isWaiting(input, it) }
        if (adjacentCandidate != null) return ThumbnailCandidate(adjacentCandidate, wide = false)

        val wideCandidate = (input.first - input.columns * 50..input.last + input.columns * 50)
            .filter { it in input.sources.indices }
            .sortedWith(compareBy<Int> { kotlin.math.abs(it - input.centerIndex) }.thenBy { if (input.direction > 0) -it else it })
            .map { input.sources[it] }
            .firstOrNull { it.localPath != null && cacheIdentity(it) !in input.completedKeys && !isKnownFailure(it) }
        return wideCandidate?.let { ThumbnailCandidate(it, wide = true) }
    }

    private fun isWaiting(input: SchedulerInput, source: MediaGridThumbnailSource): Boolean =
        input.workStates[source.assetId] is MediaGridThumbnailState.Waiting && !isKnownFailure(source)

    private fun startCandidateLocked(candidate: ThumbnailCandidate?) {
        if (candidate == null) return
        generate(candidate.source, candidate.wide)
    }

    @Synchronized private fun ensureViewportDispatcherLocked(): LatestValueDispatcher<MediaGridViewportSnapshot> {
        return viewportDispatcher ?: LatestValueDispatcher(
            scope = scope,
            workerDispatcher = Dispatchers.Default,
            process = ::processViewportSnapshot,
        ).also { viewportDispatcher = it }
    }

    fun setForeground(active: Boolean) {
        val shouldSchedule = synchronized(this) {
            foreground = active
            active && viewportActive
        }
        if (shouldSchedule) requestWorkSchedule()
    }

    @Synchronized fun state(assetId: Long, source: MediaGridThumbnailSource): StateFlow<MediaGridThumbnailState> =
        requireNotNull(ensureWork(assetId, source)).state

    /** Read an already materialized cell state without creating work or changing the queue. */
    @Synchronized fun stateIfPresent(assetId: Long): StateFlow<MediaGridThumbnailState>? =
        works[assetId]?.state

    fun onDisplayError(source: MediaGridThumbnailSource, file: File) {
        var shouldInvalidate = false
        val shouldSchedule = synchronized(this) {
            if (sourceByAssetId[source.assetId] != source) return@synchronized false
            shouldInvalidate = true
            val key = cacheIdentity(source)
            if ((displayRetries[key] ?: 0) >= 1) {
                ensureWork(source.assetId, source)?.state?.value = MediaGridThumbnailState.Failed
                false
            } else {
                displayRetries[key] = 1
                completedKeys.remove(key)
                ensureWork(source.assetId, source)?.state?.value = MediaGridThumbnailState.Waiting
                true
            }
        }
        if (shouldInvalidate) store.invalidate(file)
        if (shouldSchedule) requestWorkSchedule()
    }

    @Synchronized fun onDisplaySuccess(source: MediaGridThumbnailSource) {
        displayRetries.remove(cacheIdentity(source))
    }

    private fun ensureWork(id: Long, source: MediaGridThumbnailSource? = sourceByAssetId[id]): Work? {
        if (source == null || source.assetId != id) return null
        return works.getOrPut(id) { Work(source, MutableStateFlow(initialState(source))) }.also { work ->
            if (work.source != source) {
                work.source = source
                work.generation++
                work.state.value = initialState(source)
            }
        }
    }

    private fun pruneUiState(ui: Set<Long>) {
        works.entries.removeIf { (id, work) -> id !in ui && work.state.value !is MediaGridThumbnailState.Generating }
    }

    private fun requestWorkSchedule() {
        synchronized(this) {
            if (!viewportActive || !foreground || job?.isActive == true || schedulerJob?.isActive == true) return
            schedulerJob = scope.launch(Dispatchers.Default) {
                val input = synchronized(this@MediaGridThumbnailManager) {
                    if (!viewportActive || !foreground || job?.isActive == true) null else schedulerInputLocked()
                }
                val candidate = input?.let(::selectNextWork)
                synchronized(this@MediaGridThumbnailManager) {
                    schedulerJob = null
                    if (viewportActive && foreground && job == null) startCandidateLocked(candidate)
                }
            }
        }
    }

    private fun generate(source: MediaGridThumbnailSource, wide: Boolean) {
        val work = if (wide) null else ensureWork(source.assetId, source)
        val generation = work?.generation
        work?.let { it.state.value = MediaGridThumbnailState.Generating }
        job = scope.launch(Dispatchers.IO) {
            val startedSource = source
            val result: Result<File> = runCatching {
                store.getOrCreate(startedSource, allowRemote = !wide)
            }
            if (wide) { yield(); delay(50) }
            synchronized(this@MediaGridThumbnailManager) {
                val current = sourceByAssetId[startedSource.assetId]
                if (wide) {
                    // A wide preparation is cache-only. Record failures too so a missing
                    // local file is not retried on every 50ms scheduling pass.
                    completedKeys += cacheIdentity(startedSource)
                }
                if (work != null && generation == work.generation && current == startedSource) {
                    work.state.value = result.fold({
                        completedKeys += cacheIdentity(startedSource)
                        MediaGridThumbnailState.Ready(it)
                    }, { MediaGridThumbnailState.Failed })
                }
                job = null
            }
            requestWorkSchedule()
        }
    }

    private fun cacheIdentity(source: MediaGridThumbnailSource) = listOf(source.assetId, source.mediaKey, source.localPath, source.previewUrl, source.remoteUrl, source.size, source.modified).joinToString("|")

    private fun isKnownFailure(source: MediaGridThumbnailSource) =
        source.downloadState == "failed" || (source.localPath == null && source.previewUrl == null && source.remoteUrl == null)

    private fun initialState(source: MediaGridThumbnailSource) =
        if (isKnownFailure(source)) MediaGridThumbnailState.Failed else MediaGridThumbnailState.Waiting
}
