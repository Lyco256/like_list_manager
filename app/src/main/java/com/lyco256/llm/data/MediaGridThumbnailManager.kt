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
data class MediaGridViewportRequest(val source: MediaGridThumbnailSource, val distance: Int, val index: Int = 0)

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

    @Synchronized fun updateSourceSnapshot(revision: Long, ordered: List<MediaGridThumbnailSource>) {
        val nextSources = ordered.distinctBy { it.assetId }
        if (sourceRevision == revision && sources == nextSources) return
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
        startIfNeeded()
    }

    @Synchronized fun updateViewport(requests: List<MediaGridViewportRequest>, columnCount: Int = columns) {
        columns = columnCount.coerceAtLeast(1)
        val nextCenter = requests.map { it.index }.average().takeUnless { it.isNaN() }?.toFloat()
        if (nextCenter != null) {
            direction = (nextCenter - centerIndex).sign.toInt()
            centerIndex = nextCenter
        }
        visible = requests.associate { it.source.assetId to it.distance }
        if (visible.isNotEmpty()) {
            val indices = visible.keys.mapNotNull(sourceIndex::get)
            first = indices.minOrNull() ?: 0
            last = indices.maxOrNull() ?: -1
        }
        visible.forEach { (id, _) -> ensureWork(id) }
        // UI preparation is bounded to the current rows. It must be materialized
        // before wide preparation is considered, but never for the full snapshot.
        uiIds().forEach(::ensureWork)
        pruneUiState()
        startIfNeeded()
    }

    @Synchronized fun setForeground(active: Boolean) { foreground = active; if (active) startIfNeeded() }

    @Synchronized fun state(assetId: Long, source: MediaGridThumbnailSource): StateFlow<MediaGridThumbnailState> =
        requireNotNull(ensureWork(assetId, source)).state

    /** Read an already materialized cell state without creating work or changing the queue. */
    @Synchronized fun stateIfPresent(assetId: Long): StateFlow<MediaGridThumbnailState>? =
        works[assetId]?.state

    @Synchronized fun onDisplayError(source: MediaGridThumbnailSource, file: File) {
        if (sourceByAssetId[source.assetId] != source) return
        val key = cacheIdentity(source)
        store.invalidate(file)
        if ((displayRetries[key] ?: 0) >= 1) {
            ensureWork(source.assetId, source)?.state?.value = MediaGridThumbnailState.Failed
            return
        }
        displayRetries[key] = 1
        completedKeys.remove(key)
        ensureWork(source.assetId, source)?.state?.value = MediaGridThumbnailState.Waiting
        startIfNeeded()
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

    private fun pruneUiState() {
        val ui = uiIds()
        works.entries.removeIf { (id, work) -> id !in ui && work.state.value !is MediaGridThumbnailState.Generating }
    }

    private fun uiIds(): Set<Long> {
        if (visible.isEmpty() || last < first) return emptySet()
        val margin = columns
        return (first - margin..last + margin).mapNotNull { sources.getOrNull(it)?.assetId }.toSet()
    }

    @Synchronized private fun startIfNeeded() {
        if (!foreground || job?.isActive == true) return
        val ui = uiIds()
        val center = (first + last) / 2f
        val candidate = visible.entries.asSequence()
            .sortedWith(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { id ->
                val source = works[id.key]?.source
                if (source?.previewUrl == null && source?.remoteUrl == null) 0 else 1
            })
            .mapNotNull { (id, _) -> works[id] }
            .firstOrNull { it.state.value is MediaGridThumbnailState.Waiting && !isKnownFailure(it.source) }
            ?: sources.indices.asSequence()
                .filter { it in (first - columns..last + columns) && sources[it].assetId !in visible }
                .sortedWith(compareBy<Int> { kotlin.math.abs(it - center) }.thenBy { if (direction > 0) -it else it })
                .mapNotNull { works[sources[it].assetId] }
                .firstOrNull { it.state.value is MediaGridThumbnailState.Waiting && !isKnownFailure(it.source) }
        if (candidate == null) {
            val source = sources.indices.asSequence()
                .filter { it in (first - columns * 50..last + columns * 50) }
                .sortedWith(compareBy<Int> { kotlin.math.abs(it - center) }.thenBy { if (direction > 0) -it else it })
                .mapNotNull { sources.getOrNull(it) }
                .firstOrNull { it.localPath != null && cacheIdentity(it) !in completedKeys && !isKnownFailure(it) }
            if (source == null) return
            generate(source, wide = true); return
        }
        generate(candidate.source, wide = candidate.source.assetId !in ui)
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
                job = null; startIfNeeded()
            }
        }
    }

    private fun cacheIdentity(source: MediaGridThumbnailSource) = listOf(source.assetId, source.mediaKey, source.localPath, source.previewUrl, source.remoteUrl, source.size, source.modified).joinToString("|")

    private fun isKnownFailure(source: MediaGridThumbnailSource) =
        source.downloadState == "failed" || (source.localPath == null && source.previewUrl == null && source.remoteUrl == null)

    private fun initialState(source: MediaGridThumbnailSource) =
        if (isKnownFailure(source)) MediaGridThumbnailState.Failed else MediaGridThumbnailState.Waiting
}
