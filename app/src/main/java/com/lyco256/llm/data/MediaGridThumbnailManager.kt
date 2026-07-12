package com.lyco256.llm.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface MediaGridThumbnailState { data object Waiting : MediaGridThumbnailState; data object Generating : MediaGridThumbnailState; data class Ready(val file: File) : MediaGridThumbnailState; data object Failed : MediaGridThumbnailState }
data class MediaGridViewportRequest(val source: MediaGridThumbnailSource, val distance: Int)

class MediaGridThumbnailManager(private val store: MediaGridThumbnailStore, private val scope: CoroutineScope) {
    private data class Work(var request: MediaGridViewportRequest, val state: MutableStateFlow<MediaGridThumbnailState>)
    private val works = LinkedHashMap<Long, Work>()
    private var viewport = emptyList<MediaGridViewportRequest>()
    private var job: Job? = null

    @Synchronized fun state(assetId: Long): StateFlow<MediaGridThumbnailState> = works.getOrPut(assetId) { Work(MediaGridViewportRequest(MediaGridThumbnailSource(assetId, "", null, null, null), Int.MAX_VALUE), MutableStateFlow(MediaGridThumbnailState.Waiting)) }.state

    @Synchronized fun updateViewport(requests: List<MediaGridViewportRequest>) {
        viewport = requests.distinctBy { it.source.assetId }.sortedBy { it.distance }
        viewport.forEach { request ->
            val work = works.getOrPut(request.source.assetId) { Work(request, MutableStateFlow(MediaGridThumbnailState.Waiting)) }
            if (work.request.source != request.source && (work.state.value is MediaGridThumbnailState.Ready || work.state.value is MediaGridThumbnailState.Failed)) {
                work.state.value = MediaGridThumbnailState.Waiting
            }
            work.request = request
        }
        startIfNeeded()
    }

    @Synchronized private fun startIfNeeded() {
        if (job?.isActive == true) return
        val work = viewport.asSequence().mapNotNull { works[it.source.assetId] }.firstOrNull { it.state.value is MediaGridThumbnailState.Waiting } ?: return
        work.state.value = MediaGridThumbnailState.Generating
        val request = work.request.source
        job = scope.launch(Dispatchers.IO) {
            val result = runCatching { store.getOrCreate(request) }
            synchronized(this@MediaGridThumbnailManager) {
                if (work.request.source != request) {
                    work.state.value = MediaGridThumbnailState.Waiting
                } else {
                    work.state.value = result.fold({ MediaGridThumbnailState.Ready(it) }, { MediaGridThumbnailState.Failed })
                }
                job = null
                startIfNeeded()
            }
        }
    }
}
