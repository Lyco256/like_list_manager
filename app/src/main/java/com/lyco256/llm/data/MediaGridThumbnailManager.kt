package com.lyco256.llm.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

sealed interface MediaGridThumbnailState {
    data object Waiting : MediaGridThumbnailState
    data object Generating : MediaGridThumbnailState
    data class Ready(val file: File) : MediaGridThumbnailState
    data object Failed : MediaGridThumbnailState
}

private data class ActiveGeneration(
    val token: Long,
    val sourceRevision: Long,
    val source: MediaGridThumbnailSource,
    val wide: Boolean,
    val holderGeneration: Long?,
)

private data class CacheHydrationCandidate(
    val source: MediaGridThumbnailSource,
    val cacheKey: String,
)

private data class ActiveCacheHydration(
    val token: Long,
    val sourceRevision: Long,
    val viewportToken: Long,
    val candidates: List<CacheHydrationCandidate>,
)

private data class CacheHydrationHit(
    val source: MediaGridThumbnailSource,
    val cacheKey: String,
    val file: File,
)

enum class MediaGridScrollOperationState {
    Idle,
    Dragging,
    Flinging,
}

private sealed interface CoordinatorEvent {
    data class SourceUpdated(val revision: Long, val sources: List<MediaGridThumbnailSource>) : CoordinatorEvent
    data object ViewportAvailable : CoordinatorEvent
    data class ForegroundChanged(val active: Boolean) : CoordinatorEvent
    data class ScrollOperationChanged(val state: MediaGridScrollOperationState) : CoordinatorEvent
    data class IdleResumeReady(val token: Long) : CoordinatorEvent
    data class HolderObserved(val assetId: Long, val source: MediaGridThumbnailSource) : CoordinatorEvent
    data class DisplayError(val source: MediaGridThumbnailSource, val file: File) : CoordinatorEvent
    data class DisplaySuccess(val source: MediaGridThumbnailSource) : CoordinatorEvent
    data class GenerationFinished(
        val token: Long,
        val sourceRevision: Long,
        val source: MediaGridThumbnailSource,
        val wide: Boolean,
        val holderGeneration: Long?,
        val result: Result<File>,
    ) : CoordinatorEvent
    data class CacheHydrationFinished(
        val token: Long,
        val sourceRevision: Long,
        val viewportToken: Long,
        val hits: List<CacheHydrationHit>,
        val misses: List<String>,
    ) : CoordinatorEvent
    data object Dispose : CoordinatorEvent
}

private data class ThumbnailCandidate(
    val source: MediaGridThumbnailSource,
    val wide: Boolean,
)

/**
 * Owns all scheduler state on one serial coordinator. UI reads only the stable holder flows;
 * viewport notifications use a separate latest-value mailbox and never wait for coordinator work.
 */
class MediaGridThumbnailManager(
    private val store: MediaGridThumbnailStoreGateway,
    private val scope: CoroutineScope,
) {
    // This registry is deliberately separate from scheduler state. Its values are the only
    // mutable objects exposed to Compose, and state()/stateIfPresent() never enter the coordinator.
    private val holders = ConcurrentHashMap<Long, MutableStateFlow<MediaGridThumbnailState>>()

    private val events = Channel<CoordinatorEvent>(Channel.UNLIMITED)
    private val latestViewport = AtomicReference<MediaGridViewportSnapshot?>(null)
    private val lastDispatchedViewport = AtomicReference<MediaGridViewportSnapshot?>(null)
    private val viewportSignalPending = AtomicBoolean(false)
    private val latestSourceRevision = AtomicReference<Long?>(null)
    private val viewportActiveFlag = AtomicBoolean(false)
    private val disposedFlag = AtomicBoolean(false)

    // The following fields are accessed only from coordinatorJob.
    private val coordinatorJob: Job = scope.launch(Dispatchers.Default) {
        for (event in events) processEvent(event)
    }
    private var sourceRevision: Long? = null
    private var sources: List<MediaGridThumbnailSource> = emptyList()
    private val sourceByAssetId = HashMap<Long, MediaGridThumbnailSource>()
    private val sourceIndex = HashMap<Long, Int>()
    private val holderSources = HashMap<Long, MediaGridThumbnailSource>()
    private val holderGenerations = HashMap<Long, Long>()
    private val completedKeys = mutableSetOf<String>()
    private val displayRetries = mutableMapOf<String, Int>()
    private var currentViewport: MediaGridViewportSnapshot? = null
    private var first = 0
    private var last = -1
    private var columns = 1
    private var direction = 0
    private var centerIndex = 0f
    private var foreground = true
    private var scrollOperationState = MediaGridScrollOperationState.Idle
    private var idleResumePending = false
    private var idleResumeToken = 0L
    private var idleResumeJob: Job? = null
    private var activeGeneration: ActiveGeneration? = null
    private var generationJob: Job? = null
    private var nextGenerationToken = 0L
    private val cacheMisses = mutableSetOf<String>()
    private var viewportToken = 0L
    private var activeCacheHydration: ActiveCacheHydration? = null
    private var cacheHydrationJob: Job? = null
    private var nextCacheHydrationToken = 0L

    fun updateSourceSnapshot(revision: Long, ordered: List<MediaGridThumbnailSource>) {
        val previousRevision = latestSourceRevision.getAndSet(revision)
        if (previousRevision != revision) {
            latestViewport.set(null)
            lastDispatchedViewport.set(null)
        }
        disposedFlag.set(false)
        viewportActiveFlag.set(true)
        events.trySend(CoordinatorEvent.SourceUpdated(revision, ordered.distinctBy { it.assetId }))
    }

    fun dispatchViewport(snapshot: MediaGridViewportSnapshot): Boolean {
        if (disposedFlag.get() || !viewportActiveFlag.get() || latestSourceRevision.get() != snapshot.sourceRevision) return false

        while (true) {
            if (disposedFlag.get() || !viewportActiveFlag.get() || latestSourceRevision.get() != snapshot.sourceRevision) return false
            val previous = lastDispatchedViewport.get()
            if (previous != null && previous.sameStructureAs(snapshot)) return false
            if (lastDispatchedViewport.compareAndSet(previous, snapshot)) break
        }

        latestViewport.set(snapshot)
        signalViewport()
        return true
    }

    fun disposeViewport() {
        if (!disposedFlag.compareAndSet(false, true)) return
        viewportActiveFlag.set(false)
        latestViewport.set(null)
        lastDispatchedViewport.set(null)
        events.trySend(CoordinatorEvent.Dispose)
    }

    fun setForeground(active: Boolean) {
        events.trySend(CoordinatorEvent.ForegroundChanged(active))
    }

    fun setScrollOperationState(state: MediaGridScrollOperationState) {
        events.trySend(CoordinatorEvent.ScrollOperationChanged(state))
    }

    /** Returns a stable UI holder without waiting for viewport planning or candidate selection. */
    fun state(assetId: Long, source: MediaGridThumbnailSource): StateFlow<MediaGridThumbnailState> {
        var created = false
        val state = holders.computeIfAbsent(assetId) {
            created = true
            MutableStateFlow(initialState(source))
        }
        if (created) events.trySend(CoordinatorEvent.HolderObserved(assetId, source))
        return state
    }

    /** Reads an already materialized holder without creating work or changing scheduler state. */
    fun stateIfPresent(assetId: Long): StateFlow<MediaGridThumbnailState>? = holders[assetId]

    fun onDisplayError(source: MediaGridThumbnailSource, file: File) {
        events.trySend(CoordinatorEvent.DisplayError(source, file))
    }

    fun onDisplaySuccess(source: MediaGridThumbnailSource) {
        events.trySend(CoordinatorEvent.DisplaySuccess(source))
    }

    /** Test-only proof that holder reads remain non-blocking when the coordinator is stopped. */
    internal fun stopCoordinatorForTest() {
        coordinatorJob.cancel()
    }

    private suspend fun processEvent(event: CoordinatorEvent) {
        when (event) {
            is CoordinatorEvent.SourceUpdated -> handleSourceUpdated(event)
            CoordinatorEvent.ViewportAvailable -> handleViewportAvailable()
            is CoordinatorEvent.ForegroundChanged -> {
                foreground = event.active
                if (!foreground) {
                    cancelIdleResume()
                    cancelCacheHydration()
                } else if (activeGeneration == null) {
                    selectAndStartNext()
                }
            }
            is CoordinatorEvent.ScrollOperationChanged -> handleScrollOperationChanged(event.state)
            is CoordinatorEvent.IdleResumeReady -> handleIdleResumeReady(event.token)
            is CoordinatorEvent.HolderObserved -> handleHolderObserved(event)
            is CoordinatorEvent.DisplayError -> handleDisplayError(event)
            is CoordinatorEvent.DisplaySuccess -> displayRetries.remove(cacheIdentity(event.source))
            is CoordinatorEvent.GenerationFinished -> handleGenerationFinished(event)
            is CoordinatorEvent.CacheHydrationFinished -> handleCacheHydrationFinished(event)
            CoordinatorEvent.Dispose -> handleDispose()
        }
    }

    private fun handleSourceUpdated(event: CoordinatorEvent.SourceUpdated) {
        if (disposedFlag.get() || latestSourceRevision.get() != event.revision) return
        if (sourceRevision == event.revision && sources == event.sources) return

        cancelIdleResume()
        cancelActiveGeneration()
        cancelCacheHydration()
        cacheMisses.clear()
        completedKeys.clear()
        displayRetries.clear()
        sourceRevision = event.revision
        sources = event.sources
        sourceByAssetId.clear()
        sourceIndex.clear()
        event.sources.forEachIndexed { index, source ->
            sourceByAssetId[source.assetId] = source
            sourceIndex[source.assetId] = index
        }
        currentViewport = null
        first = 0
        last = -1
        direction = 0
        centerIndex = 0f

        val holderIterator = holderSources.keys.iterator()
        while (holderIterator.hasNext()) {
            val assetId = holderIterator.next()
            if (assetId !in sourceByAssetId) {
                holders.remove(assetId)
                holderGenerations.remove(assetId)
                holderIterator.remove()
            }
        }
        for (source in event.sources) {
            val holder = holders[source.assetId] ?: continue
            val previous = holderSources[source.assetId]
            if (previous != source) {
                holderSources[source.assetId] = source
                holderGenerations[source.assetId] = (holderGenerations[source.assetId] ?: 0L) + 1L
                holder.value = initialState(source)
            }
        }
    }

    private fun handleViewportAvailable() {
        val snapshot = takeLatestViewport() ?: return
        if (!isCurrentSnapshot(snapshot)) return
        applyViewport(snapshot, startCandidate = activeGeneration == null)
    }

    private fun applyViewport(snapshot: MediaGridViewportSnapshot, startCandidate: Boolean) {
        if (!isCurrentSnapshot(snapshot)) return
        if (currentViewport?.sameStructureAs(snapshot) == true) return

        val oldFirst = first
        val oldLast = last
        var nextFirst = Int.MAX_VALUE
        var nextLast = -1
        var validItems = 0
        for (item in snapshot.items) {
            if (sourceByAssetId[item.assetId] == null || sourceIndex[item.assetId] != item.sourceIndex) continue
            nextFirst = minOf(nextFirst, item.sourceIndex)
            nextLast = maxOf(nextLast, item.sourceIndex)
            validItems++
        }
        first = if (validItems == 0) 0 else nextFirst
        last = nextLast
        columns = snapshot.columnCount.coerceAtLeast(1)
        centerIndex = if (last >= first) (first + last) / 2f else centerIndex
        direction = when {
            first > oldFirst || last > oldLast -> 1
            first < oldFirst || last < oldLast -> -1
            else -> 0
        }
        currentViewport = snapshot
        viewportToken++
        cancelCacheHydration()
        pruneHolders()
        if (startCandidate) selectAndStartNext()
    }

    private fun handleScrollOperationChanged(next: MediaGridScrollOperationState) {
        if (scrollOperationState == next) return
        scrollOperationState = next
        if (next == MediaGridScrollOperationState.Idle) {
            idleResumeJob?.cancel()
            idleResumeJob = null
            idleResumePending = true
            val token = ++idleResumeToken
            idleResumeJob = scope.launch {
                delay(100)
                events.trySend(CoordinatorEvent.IdleResumeReady(token))
            }
        } else {
            cancelIdleResume()
            cancelCacheHydration()
            if (activeGeneration?.wide == true) cancelActiveGeneration()
        }
    }

    private fun handleIdleResumeReady(token: Long) {
        if (token != idleResumeToken || scrollOperationState != MediaGridScrollOperationState.Idle ||
            !foreground || disposedFlag.get()
        ) return
        idleResumeJob = null
        idleResumePending = false
        takeLatestViewport()?.let { snapshot -> applyViewport(snapshot, startCandidate = false) }
        selectAndStartNext()
    }

    private fun handleHolderObserved(event: CoordinatorEvent.HolderObserved) {
        if (disposedFlag.get() || !viewportActiveFlag.get()) return
        val holder = holders[event.assetId] ?: return
        val current = sourceByAssetId[event.assetId]
        val effective = current ?: event.source
        if (holderSources[event.assetId] != effective) {
            holderSources[event.assetId] = effective
            holderGenerations[event.assetId] = (holderGenerations[event.assetId] ?: 0L) + 1L
            holder.value = initialState(effective)
        }
    }

    private fun handleDisplayError(event: CoordinatorEvent.DisplayError) {
        if (!isCurrentSource(event.source)) return
        val key = cacheIdentity(event.source)
        val holder = ensureHolder(event.source)
        val retries = displayRetries[key] ?: 0
        store.invalidate(event.file)
        if (retries >= 1) {
            holder.value = MediaGridThumbnailState.Failed
            return
        }
        displayRetries[key] = 1
        completedKeys.remove(key)
        cacheMisses.remove(key)
        holder.value = MediaGridThumbnailState.Waiting
        if (canStartScheduler()) selectAndStartNext()
    }

    private fun handleGenerationFinished(event: CoordinatorEvent.GenerationFinished) {
        val active = activeGeneration
        if (disposedFlag.get() || active == null || active.token != event.token ||
            active.sourceRevision != event.sourceRevision || latestSourceRevision.get() != event.sourceRevision
        ) return

        activeGeneration = null
        generationJob = null
        if (event.wide) {
            // Wide preparation is cache-only. Record failures too to preserve the existing range pacing.
            completedKeys += cacheIdentity(event.source, allowRemote = false)
        } else {
            val current = sourceByAssetId[event.source.assetId]
            val holderGeneration = holderGenerations[event.source.assetId]
            val holder = holders[event.source.assetId]
            if (current == event.source && holderGeneration == event.holderGeneration && holder != null) {
                event.result.fold(
                    onSuccess = {
                        completedKeys += cacheIdentity(event.source, allowRemote = true)
                        cacheMisses.remove(cacheIdentity(event.source, allowRemote = true))
                        holder.value = MediaGridThumbnailState.Ready(it)
                    },
                    onFailure = { holder.value = MediaGridThumbnailState.Failed },
                )
            }
        }

        // A viewport that arrived while generation was active is applied once immediately before
        // selecting the next candidate. No per-frame selection occurs during generation.
        takeLatestViewport()?.let { snapshot -> applyViewport(snapshot, startCandidate = false) }
        if (canStartScheduler()) selectAndStartNext()
    }

    private fun handleCacheHydrationFinished(event: CoordinatorEvent.CacheHydrationFinished) {
        val active = activeCacheHydration
        if (active == null || active.token != event.token || active.sourceRevision != event.sourceRevision ||
            active.viewportToken != event.viewportToken || sourceRevision != event.sourceRevision ||
            latestSourceRevision.get() != event.sourceRevision || !foreground ||
            scrollOperationState != MediaGridScrollOperationState.Idle || disposedFlag.get()
        ) return

        activeCacheHydration = null
        cacheHydrationJob = null
        cacheMisses += event.misses
        val filesByKey = event.hits.associateBy { it.cacheKey }
        for (candidate in active.candidates) {
            val hit = filesByKey[candidate.cacheKey] ?: continue
            if (hit.source != candidate.source) continue
            val file = hit.file
            if (!isCurrentSource(candidate.source)) continue
            val holder = ensureHolder(candidate.source)
            if (holder.value !is MediaGridThumbnailState.Waiting) continue
            completedKeys += candidate.cacheKey
            cacheMisses.remove(candidate.cacheKey)
            holder.value = MediaGridThumbnailState.Ready(file)
        }
        selectAndStartNext()
    }

    private fun handleDispose() {
        cancelIdleResume()
        cancelActiveGeneration()
        cancelCacheHydration()
        cacheMisses.clear()
        currentViewport = null
        first = 0
        last = -1
    }

    private fun cancelIdleResume() {
        idleResumeToken++
        idleResumePending = false
        idleResumeJob?.cancel()
        idleResumeJob = null
    }

    private fun cancelActiveGeneration() {
        activeGeneration = null
        generationJob?.cancel()
        generationJob = null
    }

    private fun cancelCacheHydration() {
        activeCacheHydration = null
        cacheHydrationJob?.cancel()
        cacheHydrationJob = null
    }

    private fun selectAndStartNext() {
        if (!canStartScheduler()) return
        if (startCacheHydrationIfNeeded()) return
        if (!canStartGeneration()) return
        val candidate = selectNextCandidate() ?: return
        startCandidate(candidate)
    }

    private fun startCacheHydrationIfNeeded(): Boolean {
        if (activeCacheHydration != null || cacheHydrationJob != null) return true
        val revision = sourceRevision ?: return false
        val candidates = selectCacheHydrationCandidates()
        if (candidates.isEmpty()) return false
        val active = ActiveCacheHydration(
            token = ++nextCacheHydrationToken,
            sourceRevision = revision,
            viewportToken = viewportToken,
            candidates = candidates,
        )
        activeCacheHydration = active
        cacheHydrationJob = scope.launch(Dispatchers.IO) {
            val hits = mutableListOf<CacheHydrationHit>()
            val misses = LinkedHashSet<String>()
            for (candidate in active.candidates) {
                currentCoroutineContext().ensureActive()
                val file = store.findCached(candidate.source, allowRemote = true)
                if (file != null) hits += CacheHydrationHit(candidate.source, candidate.cacheKey, file)
                else misses += candidate.cacheKey
            }
            currentCoroutineContext().ensureActive()
            events.trySend(
                CoordinatorEvent.CacheHydrationFinished(
                    token = active.token,
                    sourceRevision = active.sourceRevision,
                    viewportToken = active.viewportToken,
                    hits = hits,
                    misses = misses.toList(),
                ),
            )
        }
        return true
    }

    private fun selectCacheHydrationCandidates(): List<CacheHydrationCandidate> {
        val snapshot = currentViewport ?: return emptyList()
        if (last < first) return emptyList()
        val candidates = LinkedHashMap<String, MediaGridThumbnailSource>()
        for (item in snapshot.items) {
            val source = sourceByAssetId[item.assetId]
                ?.takeIf { sourceIndex[item.assetId] == item.sourceIndex }
                ?: continue
            addCacheHydrationCandidate(candidates, source)
        }
        val adjacentStart = maxOf(0, first - columns)
        val adjacentEnd = minOf(sources.lastIndex, last + columns)
        val adjacentIndices = (adjacentStart..adjacentEnd)
            .filter { it !in first..last && !isVisibleIndex(snapshot, it) }
            .sortedWith(compareBy<Int> { abs(it - centerIndex) }.thenBy {
                if (direction > 0) -it else it
            })
        for (index in adjacentIndices) addCacheHydrationCandidate(candidates, sources[index])
        return candidates.map { (key, source) -> CacheHydrationCandidate(source, key) }
    }

    private fun addCacheHydrationCandidate(
        candidates: LinkedHashMap<String, MediaGridThumbnailSource>,
        source: MediaGridThumbnailSource,
    ) {
        if (!isWaiting(source)) return
        val key = cacheIdentityOrNull(source, allowRemote = true) ?: return
        if (key in completedKeys || key in cacheMisses) return
        candidates.putIfAbsent(key, source)
    }

    private fun canStartScheduler(): Boolean =
        !disposedFlag.get() && viewportActiveFlag.get() && foreground &&
            scrollOperationState == MediaGridScrollOperationState.Idle &&
            !idleResumePending && activeGeneration == null && currentViewport != null

    private fun canStartGeneration(): Boolean = canStartScheduler() &&
        activeCacheHydration == null && cacheHydrationJob == null

    private fun selectNextCandidate(): ThumbnailCandidate? {
        val snapshot = currentViewport ?: return null
        if (last < first) return null

        var visibleBest: MediaGridThumbnailSource? = null
        var visibleDistance = Int.MAX_VALUE
        var visibleFallbackPriority = Int.MAX_VALUE
        for (item in snapshot.items) {
            val source = sourceByAssetId[item.assetId]
                ?.takeIf { sourceIndex[item.assetId] == item.sourceIndex }
                ?: continue
            if (!isWaiting(source)) continue
            val fallbackPriority = if (source.previewUrl == null && source.remoteUrl == null) 0 else 1
            if (item.centerDistance < visibleDistance ||
                (item.centerDistance == visibleDistance && fallbackPriority < visibleFallbackPriority)
            ) {
                visibleBest = source
                visibleDistance = item.centerDistance
                visibleFallbackPriority = fallbackPriority
            }
        }
        if (visibleBest != null) return ThumbnailCandidate(visibleBest, wide = false)

        val adjacentStart = maxOf(0, first - columns)
        val adjacentEnd = minOf(sources.lastIndex, last + columns)
        var adjacentBest: MediaGridThumbnailSource? = null
        var adjacentDistance = Float.POSITIVE_INFINITY
        var adjacentTie = Int.MAX_VALUE
        for (index in adjacentStart..adjacentEnd) {
            if (index in first..last || isVisibleIndex(snapshot, index)) continue
            val source = sources[index]
            if (!isWaiting(source)) continue
            val distance = abs(index - centerIndex)
            val tie = if (direction > 0) -index else index
            if (distance < adjacentDistance || (distance == adjacentDistance && tie < adjacentTie)) {
                adjacentBest = source
                adjacentDistance = distance
                adjacentTie = tie
            }
        }
        if (adjacentBest != null) return ThumbnailCandidate(adjacentBest, wide = false)

        val wideStart = maxOf(0, first - columns * 50)
        val wideEnd = minOf(sources.lastIndex, last + columns * 50)
        var wideBest: MediaGridThumbnailSource? = null
        var wideDistance = Float.POSITIVE_INFINITY
        var wideTie = Int.MAX_VALUE
        for (index in wideStart..wideEnd) {
            val source = sources[index]
            if (source.localPath == null || completedKeys.contains(cacheIdentity(source, allowRemote = false)) || isKnownFailure(source)) continue
            val distance = abs(index - centerIndex)
            val tie = if (direction > 0) -index else index
            if (distance < wideDistance || (distance == wideDistance && tie < wideTie)) {
                wideBest = source
                wideDistance = distance
                wideTie = tie
            }
        }
        return wideBest?.let { ThumbnailCandidate(it, wide = true) }
    }

    private fun isWaiting(source: MediaGridThumbnailSource): Boolean {
        if (isKnownFailure(source) || cacheIdentity(source) in completedKeys) return false
        return holders[source.assetId]?.value?.let { it is MediaGridThumbnailState.Waiting } ?: true
    }

    private fun startCandidate(candidate: ThumbnailCandidate) {
        val holderGeneration = if (candidate.wide) null else {
            val holder = ensureHolder(candidate.source)
            holder.value = MediaGridThumbnailState.Generating
            holderGenerations[candidate.source.assetId]
        }
        val active = ActiveGeneration(
            token = ++nextGenerationToken,
            sourceRevision = sourceRevision ?: return,
            source = candidate.source,
            wide = candidate.wide,
            holderGeneration = holderGeneration,
        )
        activeGeneration = active
        generationJob = scope.launch(Dispatchers.IO) {
            val result = runCatching {
                store.getOrCreate(candidate.source, allowRemote = !candidate.wide)
            }
            if (candidate.wide) {
                yield()
                delay(50)
            }
            events.trySend(
                CoordinatorEvent.GenerationFinished(
                    token = active.token,
                    sourceRevision = active.sourceRevision,
                    source = active.source,
                    wide = active.wide,
                    holderGeneration = active.holderGeneration,
                    result = result,
                ),
            )
        }
    }

    private fun ensureHolder(source: MediaGridThumbnailSource): MutableStateFlow<MediaGridThumbnailState> {
        val holder = holders.computeIfAbsent(source.assetId) { MutableStateFlow(initialState(source)) }
        if (holderSources[source.assetId] != source) {
            holderSources[source.assetId] = source
            holderGenerations[source.assetId] = (holderGenerations[source.assetId] ?: 0L) + 1L
            holder.value = initialState(source)
        }
        return holder
    }

    private fun pruneHolders() {
        val uiFirst = maxOf(0, first - columns)
        val uiLast = minOf(sources.lastIndex, last + columns)
        val iterator = holderSources.keys.iterator()
        while (iterator.hasNext()) {
            val assetId = iterator.next()
            val index = sourceIndex[assetId]
            if (index == null || index !in uiFirst..uiLast) {
                holders.remove(assetId)
                holderGenerations.remove(assetId)
                iterator.remove()
            }
        }
    }

    private fun isVisibleIndex(snapshot: MediaGridViewportSnapshot, index: Int): Boolean {
        for (item in snapshot.items) {
            if (item.sourceIndex == index && sourceIndex[item.assetId] == index) return true
        }
        return false
    }

    private fun isCurrentSnapshot(snapshot: MediaGridViewportSnapshot): Boolean =
        !disposedFlag.get() && viewportActiveFlag.get() && sourceRevision == snapshot.sourceRevision &&
            latestSourceRevision.get() == snapshot.sourceRevision

    private fun isCurrentSource(source: MediaGridThumbnailSource): Boolean {
        if (disposedFlag.get() || !viewportActiveFlag.get() || sourceRevision == null) return false
        return latestSourceRevision.get() == sourceRevision && sourceByAssetId[source.assetId] == source
    }

    private fun takeLatestViewport(): MediaGridViewportSnapshot? {
        val snapshot = latestViewport.getAndSet(null)
        viewportSignalPending.set(false)
        if (latestViewport.get() != null && viewportSignalPending.compareAndSet(false, true)) {
            events.trySend(CoordinatorEvent.ViewportAvailable)
        }
        return snapshot
    }

    private fun signalViewport() {
        if (viewportSignalPending.compareAndSet(false, true)) {
            events.trySend(CoordinatorEvent.ViewportAvailable)
        }
    }

    private fun cacheIdentity(source: MediaGridThumbnailSource, allowRemote: Boolean = true): String =
        cacheIdentityOrNull(source, allowRemote)
            ?: listOf(source.assetId, source.mediaKey, source.localPath, source.previewUrl, source.remoteUrl, source.size, source.modified, allowRemote)
                .joinToString("|")

    private fun cacheIdentityOrNull(source: MediaGridThumbnailSource, allowRemote: Boolean): String? =
        store.cacheKey(source, allowRemote)

    private fun isKnownFailure(source: MediaGridThumbnailSource) =
        source.downloadState == "failed" || (source.localPath == null && source.previewUrl == null && source.remoteUrl == null)

    private fun initialState(source: MediaGridThumbnailSource) =
        if (isKnownFailure(source)) MediaGridThumbnailState.Failed else MediaGridThumbnailState.Waiting
}
