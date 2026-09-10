package com.lyco256.llm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class LexicalIndexSyncStatus {
    NOT_STARTED,
    SYNCING,
    COMPLETE,
    FAILED,
}

data class LexicalIndexSyncState(
    val status: LexicalIndexSyncStatus = LexicalIndexSyncStatus.NOT_STARTED,
    val processedClipCount: Int = 0,
    val indexedClipCount: Int = 0,
    val failedClipIds: Set<Long> = emptySet(),
    val lastError: String? = null,
)

/** Reconciles the rebuildable lexical index from the currently selected Room database. */
@OptIn(ExperimentalCoroutinesApi::class)
class LexicalIndexSynchronizer(
    private val databaseFlow: Flow<LikeListDatabase?>,
    private val derivedSearchStorage: DerivedSearchStorage,
    private val analyzer: LexicalTextAnalyzer,
) {
    private val scopeLock = Any()
    private val _state = MutableStateFlow(LexicalIndexSyncState())
    private var job: Job? = null

    val state: StateFlow<LexicalIndexSyncState> = _state.asStateFlow()

    fun start(scope: CoroutineScope): Job = synchronized(scopeLock) {
        job?.takeIf { it.isActive } ?: scope.launch(Dispatchers.Default) {
            observeDatabase()
        }.also { job = it }
    }

    suspend fun stop() {
        val current = synchronized(scopeLock) {
            job.also { job = null }
        }
        current?.cancel()
        current?.join()
    }

    private suspend fun observeDatabase() {
        try {
            databaseFlow
                .flatMapLatest { database ->
                    if (database == null) {
                        flowOf(null)
                    } else {
                        database.clipDao().observeAllClips().map { clips -> clips as List<ClipEntity>? }
                    }
                }
                .conflate()
                .collect { clips ->
                    if (clips != null) reconcile(clips)
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = _state.value.copy(
                status = LexicalIndexSyncStatus.FAILED,
                lastError = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun reconcile(clips: List<ClipEntity>) {
        _state.value = LexicalIndexSyncState(
            status = LexicalIndexSyncStatus.SYNCING,
            processedClipCount = 0,
            indexedClipCount = 0,
        )
        val failedClipIds = linkedSetOf<Long>()
        val failures = linkedMapOf<Long, Throwable>()
        var indexedClipCount = 0
        try {
            val storedFingerprints = derivedSearchStorage.getAllClipFingerprints()
            val currentClips = clips.associateBy(ClipEntity::id)

            (storedFingerprints.keys - currentClips.keys).sorted().forEach { clipId ->
                try {
                    derivedSearchStorage.deleteClipDocuments(clipId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failedClipIds += clipId
                    failures[clipId] = error
                }
            }

            currentClips.values.sortedBy(ClipEntity::id).forEachIndexed { index, clip ->
                val fingerprint = LexicalDocumentBuilder.fingerprint(clip)
                if (storedFingerprints[clip.id] == fingerprint) {
                    _state.value = _state.value.copy(
                        processedClipCount = index + 1,
                        indexedClipCount = indexedClipCount,
                        failedClipIds = failedClipIds.toSet(),
                        lastError = failures.values.lastOrNull()?.message,
                    )
                    return@forEachIndexed
                }
                try {
                    val documents = LexicalDocumentBuilder.build(clip, analyzer)
                    derivedSearchStorage.replaceClipDocuments(clip.id, fingerprint, documents)
                    indexedClipCount += 1
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failedClipIds += clip.id
                    failures[clip.id] = error
                }
                _state.value = _state.value.copy(
                    processedClipCount = index + 1,
                    indexedClipCount = indexedClipCount,
                    failedClipIds = failedClipIds.toSet(),
                    lastError = failures.values.lastOrNull()?.message,
                )
            }

            _state.value = LexicalIndexSyncState(
                status = if (failedClipIds.isEmpty()) {
                    LexicalIndexSyncStatus.COMPLETE
                } else {
                    LexicalIndexSyncStatus.FAILED
                },
                processedClipCount = currentClips.size,
                indexedClipCount = indexedClipCount,
                failedClipIds = failedClipIds,
                lastError = failures.values.lastOrNull()?.message,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = LexicalIndexSyncState(
                status = LexicalIndexSyncStatus.FAILED,
                processedClipCount = 0,
                indexedClipCount = indexedClipCount,
                failedClipIds = failedClipIds,
                lastError = error.message ?: error::class.java.simpleName,
            )
        }
    }
}
