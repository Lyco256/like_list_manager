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

enum class SemanticIndexSyncStatus {
    NOT_STARTED,
    SYNCING,
    COMPLETE,
    FAILED,
}

data class SemanticIndexSyncState(
    val status: SemanticIndexSyncStatus = SemanticIndexSyncStatus.NOT_STARTED,
    val targetSourceCount: Int = 0,
    val processedSourceCount: Int = 0,
    val reembeddedSourceCount: Int = 0,
    val failedSources: Set<SemanticSourceKey> = emptySet(),
    val lastError: String? = null,
)

/** Reconciles semantic document embeddings from the currently selected Room database. */
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticIndexSynchronizer(
    private val databaseFlow: Flow<LikeListDatabase?>,
    private val derivedSearchStorage: DerivedSearchStorage,
    private val embedder: DocumentEmbedder,
) {
    private val scopeLock = Any()
    private val _state = MutableStateFlow(SemanticIndexSyncState())
    private var job: Job? = null

    val state: StateFlow<SemanticIndexSyncState> = _state.asStateFlow()

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
                status = SemanticIndexSyncStatus.FAILED,
                lastError = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun reconcile(clips: List<ClipEntity>) {
        val sources = clips.sortedBy(ClipEntity::id).flatMap { clip ->
            SemanticTextChunker.sources(clip).map { source ->
                SemanticSourceKey(clip.id, source.sourceType) to source
            }
        }
        _state.value = SemanticIndexSyncState(
            status = SemanticIndexSyncStatus.SYNCING,
            targetSourceCount = sources.size,
        )

        val failedSources = linkedSetOf<SemanticSourceKey>()
        val failures = linkedMapOf<SemanticSourceKey, Throwable>()
        var processedSourceCount = 0
        var reembeddedSourceCount = 0
        try {
            val storedFingerprints = derivedSearchStorage.getAllSemanticSourceFingerprints()
            val currentClipIds = clips.mapTo(linkedSetOf(), ClipEntity::id)
            storedFingerprints.keys
                .map(SemanticSourceKey::clipId)
                .toSet()
                .minus(currentClipIds)
                .sorted()
                .forEach { clipId ->
                    try {
                        derivedSearchStorage.deleteSemanticClip(clipId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        storedFingerprints.keys
                            .filter { key -> key.clipId == clipId }
                            .forEach { key ->
                                failedSources += key
                                failures[key] = error
                            }
                    }
                }

            sources.forEach { (key, source) ->
                try {
                    if (source.text.isBlank()) {
                        if (key in storedFingerprints) {
                            derivedSearchStorage.deleteSemanticSource(key.clipId, key.sourceType)
                        }
                    } else {
                        val fingerprint = SemanticTextChunker.fingerprint(source)
                        if (storedFingerprints[key] != fingerprint) {
                            val documents = SemanticTextChunker.chunk(source.text).map { chunk ->
                                SemanticDocument(
                                    documentId = SemanticTextChunker.documentId(
                                        key.clipId,
                                        key.sourceType,
                                        chunk.sourceOrdinal,
                                    ),
                                    clipId = key.clipId,
                                    sourceType = key.sourceType,
                                    sourceOrdinal = chunk.sourceOrdinal,
                                    embedding = embedder.embedDocument(chunk.text).toFloatArray(),
                                )
                            }
                            derivedSearchStorage.replaceSemanticSource(
                                clipId = key.clipId,
                                sourceType = key.sourceType,
                                sourceFingerprint = fingerprint,
                                documents = documents,
                            )
                            reembeddedSourceCount += 1
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: EmbeddingRuntimeInitializationException) {
                    failedSources += key
                    failures[key] = error
                    processedSourceCount += 1
                    publishProgress(
                        sources.size,
                        processedSourceCount,
                        reembeddedSourceCount,
                        failedSources,
                        failures,
                    )
                    throw error
                } catch (error: Throwable) {
                    failedSources += key
                    failures[key] = error
                }
                processedSourceCount += 1
                publishProgress(
                    sources.size,
                    processedSourceCount,
                    reembeddedSourceCount,
                    failedSources,
                    failures,
                )
            }

            _state.value = SemanticIndexSyncState(
                status = if (failedSources.isEmpty()) SemanticIndexSyncStatus.COMPLETE else SemanticIndexSyncStatus.FAILED,
                targetSourceCount = sources.size,
                processedSourceCount = processedSourceCount,
                reembeddedSourceCount = reembeddedSourceCount,
                failedSources = failedSources,
                lastError = failures.values.lastOrNull()?.message,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = SemanticIndexSyncState(
                status = SemanticIndexSyncStatus.FAILED,
                targetSourceCount = sources.size,
                processedSourceCount = processedSourceCount,
                reembeddedSourceCount = reembeddedSourceCount,
                failedSources = failedSources,
                lastError = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun publishProgress(
        targetSourceCount: Int,
        processedSourceCount: Int,
        reembeddedSourceCount: Int,
        failedSources: Set<SemanticSourceKey>,
        failures: Map<SemanticSourceKey, Throwable>,
    ) {
        _state.value = SemanticIndexSyncState(
            status = SemanticIndexSyncStatus.SYNCING,
            targetSourceCount = targetSourceCount,
            processedSourceCount = processedSourceCount,
            reembeddedSourceCount = reembeddedSourceCount,
            failedSources = failedSources.toSet(),
            lastError = failures.values.lastOrNull()?.message,
        )
    }
}
