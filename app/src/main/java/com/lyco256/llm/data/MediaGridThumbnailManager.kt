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

sealed interface MediaGridThumbnailState { data object Waiting : MediaGridThumbnailState; data object Generating : MediaGridThumbnailState; data class Ready(val file: File) : MediaGridThumbnailState; data object Failed : MediaGridThumbnailState }
data class MediaGridViewportRequest(val source: MediaGridThumbnailSource, val distance: Int)

/** One serial worker. The queue is intentionally not materialized: every completion re-selects from the latest viewport. */
class MediaGridThumbnailManager(private val store: MediaGridThumbnailStore, private val scope: CoroutineScope) {
    private data class Work(val source: MediaGridThumbnailSource, val state: MutableStateFlow<MediaGridThumbnailState>)
    private val works = LinkedHashMap<Long, Work>()
    private var sources = emptyList<MediaGridThumbnailSource>()
    private var sourceRevision: Int? = null
    private var sourceIndex = emptyMap<Long, Int>()
    private var visible = emptyMap<Long, Int>()
    private var first = 0
    private var last = -1
    private var columns = 1
    private var foreground = true
    private var job: Job? = null

    @Synchronized fun updateSourceSnapshot(revision: Int, ordered: List<MediaGridThumbnailSource>) {
        if (sourceRevision == revision && sources == ordered.distinctBy { it.assetId }) return
        sourceRevision = revision
        sources = ordered.distinctBy { it.assetId }
        sourceIndex = sources.mapIndexed { i, source -> source.assetId to i }.toMap()
        works.entries.removeIf { it.key !in sourceIndex }
        sources.forEach { source ->
            val current = works[source.assetId]
            if (current != null && current.source != source && current.state.value !is MediaGridThumbnailState.Generating) {
                works[source.assetId] = Work(source, MutableStateFlow(MediaGridThumbnailState.Waiting))
            }
        }
        startIfNeeded()
    }

    @Synchronized fun updateViewport(requests: List<MediaGridViewportRequest>, columnCount: Int = columns) {
        columns = columnCount.coerceAtLeast(1)
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

    @Synchronized fun state(assetId: Long): StateFlow<MediaGridThumbnailState> = ensureWork(assetId).state

    private fun ensureWork(id: Long): Work = works.getOrPut(id) {
        Work(sources.firstOrNull { it.assetId == id } ?: MediaGridThumbnailSource(id, "", null, null, null), MutableStateFlow(MediaGridThumbnailState.Waiting))
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
        val candidate = visible.entries.asSequence().sortedBy { it.value }.mapNotNull { (id, _) -> works[id] }
            .firstOrNull { it.state.value is MediaGridThumbnailState.Waiting }
            ?: sources.indices.asSequence()
                .filter { it in (first - columns..last + columns) && sources[it].assetId !in visible }
                .sortedBy { kotlin.math.abs(it - center) }
                .mapNotNull { works[sources[it].assetId] }
                .firstOrNull { it.state.value is MediaGridThumbnailState.Waiting }
        if (candidate == null) {
            val source = sources.indices.asSequence()
                .filter { it in (first - columns * 50..last + columns * 50) }
                .sortedBy { kotlin.math.abs(it - center) }
                .mapNotNull { sources.getOrNull(it) }
                .firstOrNull { it.localPath?.let { path -> File(path).isFile } == true && it.assetId !in works }
            if (source == null) return
            generate(source, wide = true); return
        }
        generate(candidate.source, wide = candidate.source.assetId !in ui)
    }

    private fun generate(source: MediaGridThumbnailSource, wide: Boolean) {
        val work = if (wide) null else ensureWork(source.assetId)
        work?.let { it.state.value = MediaGridThumbnailState.Generating }
        job = scope.launch(Dispatchers.IO) {
            val result = runCatching { store.getOrCreate(source, allowRemote = !wide) }
            if (wide) { yield(); delay(50) }
            synchronized(this@MediaGridThumbnailManager) {
                if (work != null) work.state.value = result.fold({ MediaGridThumbnailState.Ready(it) }, { MediaGridThumbnailState.Failed })
                job = null; startIfNeeded()
            }
        }
    }
}
