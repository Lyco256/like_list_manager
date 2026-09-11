package com.lyco256.llm.data

import android.graphics.Bitmap
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

interface ImageEmbedder {
    suspend fun embedImage(bitmap: Bitmap): MultimodalEmbedding
}

enum class ImageEmbeddingSyncStatus {
    NOT_STARTED,
    SYNCING,
    COMPLETE,
    FAILED,
}

data class ImageEmbeddingSyncState(
    val status: ImageEmbeddingSyncStatus = ImageEmbeddingSyncStatus.NOT_STARTED,
    val targetAssetCount: Int = 0,
    val processedAssetCount: Int = 0,
    val reembeddedAssetCount: Int = 0,
    val failedAssetIds: Set<Long> = emptySet(),
    val lastError: String? = null,
)

/** Reconciles local image embeddings from the currently selected Room database. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageEmbeddingSynchronizer(
    private val databaseFlow: Flow<LikeListDatabase?>,
    private val derivedSearchStorage: DerivedSearchStorage,
    private val embedder: ImageEmbedder,
    private val decoder: ImageEmbeddingBitmapDecoder = LocalImageEmbeddingBitmapDecoder(),
) {
    private val scopeLock = Any()
    private val _state = MutableStateFlow(ImageEmbeddingSyncState())
    private var job: Job? = null

    val state: StateFlow<ImageEmbeddingSyncState> = _state.asStateFlow()

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
                        database.clipDao().observeAssets().map { assets -> assets as List<AssetEntity>? }
                    }
                }
                .conflate()
                .mapLatest { assets -> if (assets != null) reconcile(assets) }
                .collect { }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = _state.value.copy(
                status = ImageEmbeddingSyncStatus.FAILED,
                lastError = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun reconcile(assets: List<AssetEntity>) {
        val sortedAssets = assets.sortedBy(AssetEntity::id)
        val targetAssets = sortedAssets.filter { it.type in TARGET_TYPES }
        _state.value = ImageEmbeddingSyncState(
            status = ImageEmbeddingSyncStatus.SYNCING,
            targetAssetCount = targetAssets.size,
        )

        val failedAssetIds = linkedSetOf<Long>()
        val failures = linkedMapOf<Long, Throwable>()
        var processedAssetCount = 0
        var reembeddedAssetCount = 0
        try {
            val storedFingerprints = derivedSearchStorage.getAllImageEmbeddingFingerprints()
            val currentAssetIds = sortedAssets.mapTo(linkedSetOf(), AssetEntity::id)
            storedFingerprints.keys
                .minus(currentAssetIds)
                .sorted()
                .forEach { assetId ->
                    try {
                        derivedSearchStorage.deleteImageEmbedding(assetId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failedAssetIds += assetId
                        failures[assetId] = error
                    }
                }

            for (asset in sortedAssets) {
                if (asset.type !in TARGET_TYPES) {
                    if (asset.id in storedFingerprints) {
                        try {
                            derivedSearchStorage.deleteImageEmbedding(asset.id)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            failedAssetIds += asset.id
                            failures[asset.id] = error
                        }
                    }
                    continue
                }

                val localPath = asset.localPath?.trim().orEmpty()
                if (localPath.isEmpty()) {
                    if (asset.id in storedFingerprints) {
                        try {
                            derivedSearchStorage.deleteImageEmbedding(asset.id)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            recordFailure(asset.id, error, failedAssetIds, failures)
                        }
                    }
                    processedAssetCount += 1
                    publishProgress(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    continue
                }

                val file = File(localPath)
                val signature = ImageEmbeddingFingerprint.sourceSignature(file)
                if (signature == null) {
                    recordFailure(
                        assetId = asset.id,
                        error = IllegalStateException("Image source is unavailable: $localPath"),
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    processedAssetCount += 1
                    publishProgress(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    continue
                }

                val fingerprint = ImageEmbeddingFingerprint.calculate(asset, signature)
                if (storedFingerprints[asset.id] == fingerprint) {
                    processedAssetCount += 1
                    publishProgress(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    continue
                }

                val bitmap = try {
                    decoder.decode(file)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordFailure(asset.id, error, failedAssetIds, failures)
                    processedAssetCount += 1
                    publishProgress(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    continue
                }

                val embedding = try {
                    embedder.embedImage(bitmap)
                } catch (error: CancellationException) {
                    bitmap.recycle()
                    throw error
                } catch (error: Throwable) {
                    bitmap.recycle()
                    recordFailure(asset.id, error, failedAssetIds, failures)
                    processedAssetCount += 1
                    publishProgress(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    _state.value = failedState(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    return
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }

                val blob = try {
                    ImageEmbeddingBlobCodec.encode(embedding)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordFailure(asset.id, error, failedAssetIds, failures)
                    processedAssetCount += 1
                    publishProgress(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    _state.value = failedState(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    return
                }

                val afterSignature = ImageEmbeddingFingerprint.sourceSignature(file)
                if (afterSignature != signature) {
                    recordFailure(
                        asset.id,
                        IllegalStateException("Image source changed during embedding: $localPath"),
                        failedAssetIds,
                        failures,
                    )
                    processedAssetCount += 1
                    publishProgress(
                        targetAssetCount = targetAssets.size,
                        processedAssetCount = processedAssetCount,
                        reembeddedAssetCount = reembeddedAssetCount,
                        failedAssetIds = failedAssetIds,
                        failures = failures,
                    )
                    continue
                }

                currentCoroutineContext().ensureActive()
                try {
                    derivedSearchStorage.replaceImageEmbedding(
                        assetId = asset.id,
                        clipId = asset.clipId,
                        sourceFingerprint = fingerprint,
                        embeddingBlob = blob,
                    )
                    reembeddedAssetCount += 1
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordFailure(asset.id, error, failedAssetIds, failures)
                }
                processedAssetCount += 1
                publishProgress(
                    targetAssetCount = targetAssets.size,
                    processedAssetCount = processedAssetCount,
                    reembeddedAssetCount = reembeddedAssetCount,
                    failedAssetIds = failedAssetIds,
                    failures = failures,
                )
            }

            _state.value = if (failedAssetIds.isEmpty()) {
                ImageEmbeddingSyncState(
                    status = ImageEmbeddingSyncStatus.COMPLETE,
                    targetAssetCount = targetAssets.size,
                    processedAssetCount = processedAssetCount,
                    reembeddedAssetCount = reembeddedAssetCount,
                )
            } else {
                failedState(
                    targetAssetCount = targetAssets.size,
                    processedAssetCount = processedAssetCount,
                    reembeddedAssetCount = reembeddedAssetCount,
                    failedAssetIds = failedAssetIds,
                    failures = failures,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = failedState(
                targetAssetCount = targetAssets.size,
                processedAssetCount = processedAssetCount,
                reembeddedAssetCount = reembeddedAssetCount,
                failedAssetIds = failedAssetIds,
                failures = failures,
                fallbackError = error,
            )
        }
    }

    private fun publishProgress(
        targetAssetCount: Int,
        processedAssetCount: Int,
        reembeddedAssetCount: Int,
        failedAssetIds: Set<Long>,
        failures: Map<Long, Throwable>,
    ) {
        _state.value = ImageEmbeddingSyncState(
            status = ImageEmbeddingSyncStatus.SYNCING,
            targetAssetCount = targetAssetCount,
            processedAssetCount = processedAssetCount,
            reembeddedAssetCount = reembeddedAssetCount,
            failedAssetIds = failedAssetIds.toSet(),
            lastError = failures.values.lastOrNull()?.message,
        )
    }

    private fun failedState(
        targetAssetCount: Int,
        processedAssetCount: Int,
        reembeddedAssetCount: Int,
        failedAssetIds: Set<Long>,
        failures: Map<Long, Throwable>,
        fallbackError: Throwable? = null,
    ) = ImageEmbeddingSyncState(
        status = ImageEmbeddingSyncStatus.FAILED,
        targetAssetCount = targetAssetCount,
        processedAssetCount = processedAssetCount,
        reembeddedAssetCount = reembeddedAssetCount,
        failedAssetIds = failedAssetIds.toSet(),
        lastError = failures.values.lastOrNull()?.message
            ?: fallbackError?.message
            ?: fallbackError?.javaClass?.simpleName,
    )

    private fun recordFailure(
        assetId: Long,
        error: Throwable,
        failedAssetIds: MutableSet<Long>,
        failures: MutableMap<Long, Throwable>,
    ) {
        failedAssetIds += assetId
        failures[assetId] = error
    }

    companion object {
        private val TARGET_TYPES = setOf("photo", "video_thumbnail")
    }
}
