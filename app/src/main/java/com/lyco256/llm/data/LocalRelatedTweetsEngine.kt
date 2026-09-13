package com.lyco256.llm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RelatedTweetsProgressPhase {
    PREPARING,
    RETRIEVING,
    RANKING,
}

data class RelatedTweetsProgress(
    val phase: RelatedTweetsProgressPhase,
    val processed: Int,
    val total: Int,
)

data class RelatedTweetResult(
    val clipId: Long,
    val score: Float,
)

object RelatedTweetsTuning {
    const val ANN_CANDIDATES_PER_VECTOR = 256
    const val MAX_RESULTS = 50
    const val TEXT_WEIGHT = 0.40f
    const val SUMMARY_WEIGHT = 0.20f
    const val OCR_WEIGHT = 0.20f
    const val IMAGE_WEIGHT = 0.20f
    const val MIN_CHANNEL_SIMILARITY = 0.25f
    const val MIN_TOTAL_SIMILARITY = 0.20f
}

interface RelatedTweetsEngine {
    suspend fun findRelated(
        clipId: Long,
        onProgress: (RelatedTweetsProgress) -> Unit = {},
    ): List<RelatedTweetResult>
}

/** Read-only ANN boundary used by the related-search engine and its deterministic tests. */
internal interface RelatedSearchAnn : AutoCloseable {
    suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit>
}

internal fun interface RelatedSearchAnnFactory {
    suspend fun build(dimension: Int, entries: List<LocalAnnEntry>): RelatedSearchAnn
}

internal object NativeRelatedSearchAnnFactory : RelatedSearchAnnFactory {
    override suspend fun build(dimension: Int, entries: List<LocalAnnEntry>): RelatedSearchAnn {
        val snapshot = LocalAnnIndexSnapshot.build(dimension, entries)
        return object : RelatedSearchAnn {
            override suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit> =
                snapshot.search(query, candidateCount)

            override fun close() = snapshot.close()
        }
    }
}

internal fun interface RelatedSearchDataSource {
    suspend fun snapshot(
        clipId: Long,
        knownSemanticRevision: Long?,
        knownImageRevision: Long?,
    ): RelatedRetrievalSnapshot
}

private class StoredRelatedSearchDataSource(
    private val storage: DerivedSearchStorage,
) : RelatedSearchDataSource {
    override suspend fun snapshot(
        clipId: Long,
        knownSemanticRevision: Long?,
        knownImageRevision: Long?,
    ): RelatedRetrievalSnapshot = storage.getRelatedRetrievalSnapshot(
        clipId = clipId,
        knownSemanticRevision = knownSemanticRevision,
        knownImageRevision = knownImageRevision,
    )
}

/**
 * Retrieves related clips only from already persisted semantic/image vectors. It deliberately has
 * no Room, embedding, decoder, lexical, or UI dependency.
 */
class LocalRelatedTweetsEngine internal constructor(
    private val data: RelatedSearchDataSource,
    private val annFactory: RelatedSearchAnnFactory,
) : RelatedTweetsEngine {
    internal constructor(
        storage: DerivedSearchStorage,
        annFactory: RelatedSearchAnnFactory,
    ) : this(StoredRelatedSearchDataSource(storage), annFactory)

    constructor(storage: DerivedSearchStorage) : this(
        data = StoredRelatedSearchDataSource(storage),
        annFactory = NativeRelatedSearchAnnFactory,
    )

    private val mutex = Mutex()
    private var closed = false
    private var semanticCache: AnnCache<SemanticMetadata>? = null
    private var imageCache: AnnCache<Long>? = null

    override suspend fun findRelated(
        clipId: Long,
        onProgress: (RelatedTweetsProgress) -> Unit,
    ): List<RelatedTweetResult> = withContext(Dispatchers.Default) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            check(!closed) { "Related tweets engine is closed" }
            val progress = ProgressReporter(onProgress)
            progress.emit(RelatedTweetsProgressPhase.PREPARING, 0, 0)

            val retrieval = data.snapshot(
                clipId = clipId,
                knownSemanticRevision = semanticCache?.revision,
                knownImageRevision = imageCache?.revision,
            )
            updateCaches(retrieval)

            val references = retrieval.referenceSemanticDocuments.size + retrieval.referenceImageDocuments.size
            progress.emit(RelatedTweetsProgressPhase.RETRIEVING, 0, references)
            if (references == 0) {
                progress.emit(RelatedTweetsProgressPhase.RANKING, 0, 0)
                return@withLock emptyList()
            }

            val scores = linkedMapOf<Long, RelatedChannelScores>()
            var processed = 0
            retrieval.referenceSemanticDocuments.forEach { reference ->
                currentCoroutineContext().ensureActive()
                val cache = semanticCache
                if (cache != null && cache.metadata.isNotEmpty()) {
                    val candidateCount = minOf(
                        RelatedTweetsTuning.ANN_CANDIDATES_PER_VECTOR,
                        cache.metadata.size,
                    )
                    cache.snapshot?.search(reference.embedding, candidateCount)?.forEach { hit ->
                        currentCoroutineContext().ensureActive()
                        val metadata = cache.metadata[hit.key] ?: error("Unknown semantic ANN key: ${hit.key}")
                        if (metadata.clipId != clipId) {
                            val candidate = scores.getOrPut(metadata.clipId) { RelatedChannelScores() }
                            candidate.update(reference.sourceType, sanitizeSimilarity(hit.cosineSimilarity))
                        }
                    }
                }
                processed += 1
                progress.emit(RelatedTweetsProgressPhase.RETRIEVING, processed, references)
            }
            retrieval.referenceImageDocuments.forEach { reference ->
                currentCoroutineContext().ensureActive()
                val cache = imageCache
                if (cache != null && cache.metadata.isNotEmpty()) {
                    val candidateCount = minOf(
                        RelatedTweetsTuning.ANN_CANDIDATES_PER_VECTOR,
                        cache.metadata.size,
                    )
                    cache.snapshot?.search(reference.embedding, candidateCount)?.forEach { hit ->
                        currentCoroutineContext().ensureActive()
                        val candidateClipId = cache.metadata[hit.key] ?: error("Unknown image ANN key: ${hit.key}")
                        if (candidateClipId != clipId) {
                            val candidate = scores.getOrPut(candidateClipId) { RelatedChannelScores() }
                            candidate.image = maxOf(candidate.image ?: 0f, sanitizeSimilarity(hit.cosineSimilarity))
                        }
                    }
                }
                processed += 1
                progress.emit(RelatedTweetsProgressPhase.RETRIEVING, processed, references)
            }

            progress.emit(RelatedTweetsProgressPhase.RANKING, 0, scores.size)
            val availableWeight = availableWeight(retrieval)
            if (availableWeight <= 0f) {
                progress.emit(RelatedTweetsProgressPhase.RANKING, scores.size, scores.size)
                return@withLock emptyList()
            }

            val ranked = ArrayList<RelatedTweetResult>(scores.size)
            var rankedCount = 0
            scores.toSortedMap().forEach { (candidateClipId, channelScores) ->
                currentCoroutineContext().ensureActive()
                val maxChannel = channelScores.maxAvailable()
                if (maxChannel >= RelatedTweetsTuning.MIN_CHANNEL_SIMILARITY) {
                    val weightedSum =
                        (channelScores.text ?: 0f) * RelatedTweetsTuning.TEXT_WEIGHT +
                            (channelScores.summary ?: 0f) * RelatedTweetsTuning.SUMMARY_WEIGHT +
                            (channelScores.ocr ?: 0f) * RelatedTweetsTuning.OCR_WEIGHT +
                            (channelScores.image ?: 0f) * RelatedTweetsTuning.IMAGE_WEIGHT
                    val total = (weightedSum / availableWeight).coerceIn(0f, 1f)
                    check(total.isFinite()) { "Non-finite related tweets score" }
                    if (total >= RelatedTweetsTuning.MIN_TOTAL_SIMILARITY) {
                        ranked += RelatedTweetResult(candidateClipId, total)
                    }
                }
                rankedCount += 1
                progress.emit(RelatedTweetsProgressPhase.RANKING, rankedCount, scores.size)
            }
            currentCoroutineContext().ensureActive()
            ranked.sortedWith(
                compareByDescending<RelatedTweetResult> { it.score }
                    .thenByDescending { scores.getValue(it.clipId).maxAvailable() }
                    .thenBy { it.clipId },
            ).take(RelatedTweetsTuning.MAX_RESULTS)
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            val semantic = semanticCache
            val image = imageCache
            semanticCache = null
            imageCache = null
            try {
                semantic?.snapshot?.close()
            } finally {
                image?.snapshot?.close()
            }
        }
    }

    private suspend fun updateCaches(retrieval: RelatedRetrievalSnapshot) {
        val semanticChanged = semanticCache?.revision != retrieval.semanticRevision
        val imageChanged = imageCache?.revision != retrieval.imageRevision
        if (!semanticChanged && !imageChanged) return

        var replacementSemantic: AnnCache<SemanticMetadata>? = null
        var replacementImage: AnnCache<Long>? = null
        var semanticAdopted = false
        var imageAdopted = false
        try {
            if (semanticChanged) {
                val rows = retrieval.semanticDocuments ?: error("Missing semantic corpus for changed revision")
                replacementSemantic = buildSemanticCache(retrieval.semanticRevision, rows)
            }
            if (imageChanged) {
                val rows = retrieval.imageDocuments ?: error("Missing image corpus for changed revision")
                replacementImage = buildImageCache(retrieval.imageRevision, rows)
            }
            currentCoroutineContext().ensureActive()
            if (semanticChanged) {
                val previous = semanticCache
                semanticCache = replacementSemantic
                replacementSemantic = null
                semanticAdopted = true
                previous?.snapshot?.close()
            }
            if (imageChanged) {
                val previous = imageCache
                imageCache = replacementImage
                replacementImage = null
                imageAdopted = true
                previous?.snapshot?.close()
            }
        } finally {
            if (!semanticAdopted) replacementSemantic?.snapshot?.close()
            if (!imageAdopted) replacementImage?.snapshot?.close()
        }
    }

    private suspend fun buildSemanticCache(
        revision: Long,
        rows: List<SemanticDocument>,
    ): AnnCache<SemanticMetadata> {
        val sorted = rows.sortedWith(
            compareBy<SemanticDocument> { it.clipId }
                .thenBy { it.sourceType.storageValue }
                .thenBy { it.sourceOrdinal }
                .thenBy { it.documentId },
        )
        val metadata = linkedMapOf<Long, SemanticMetadata>()
        val entries = sorted.mapIndexed { index, document ->
            currentCoroutineContext().ensureActive()
            val key = index.toLong()
            check(metadata.put(key, SemanticMetadata(document.clipId, document.sourceType)) == null)
            LocalAnnEntry(key, document.embedding)
        }
        return buildCache(revision, entries, metadata)
    }

    private suspend fun buildImageCache(
        revision: Long,
        rows: List<ImageEmbeddingDocument>,
    ): AnnCache<Long> {
        val metadata = linkedMapOf<Long, Long>()
        val entries = rows.sortedBy { it.assetId }.map { document ->
            currentCoroutineContext().ensureActive()
            check(metadata.put(document.assetId, document.clipId) == null) {
                "Duplicate image asset ID: ${document.assetId}"
            }
            LocalAnnEntry(document.assetId, document.embedding)
        }
        return buildCache(revision, entries, metadata)
    }

    private suspend fun <T> buildCache(
        revision: Long,
        entries: List<LocalAnnEntry>,
        metadata: Map<Long, T>,
    ): AnnCache<T> {
        if (entries.isEmpty()) return AnnCache(revision, null, metadata)
        val snapshot = annFactory.build(entries.first().vector.size, entries)
        return try {
            currentCoroutineContext().ensureActive()
            AnnCache(revision, snapshot, metadata)
        } catch (failure: Throwable) {
            snapshot.close()
            throw failure
        }
    }

    private fun availableWeight(retrieval: RelatedRetrievalSnapshot): Float {
        var weight = 0f
        if (retrieval.referenceSemanticDocuments.any { it.sourceType == SemanticSourceType.TEXT }) {
            weight += RelatedTweetsTuning.TEXT_WEIGHT
        }
        if (retrieval.referenceSemanticDocuments.any { it.sourceType == SemanticSourceType.SUMMARY }) {
            weight += RelatedTweetsTuning.SUMMARY_WEIGHT
        }
        if (retrieval.referenceSemanticDocuments.any { it.sourceType == SemanticSourceType.OCR }) {
            weight += RelatedTweetsTuning.OCR_WEIGHT
        }
        if (retrieval.referenceImageDocuments.isNotEmpty()) weight += RelatedTweetsTuning.IMAGE_WEIGHT
        return weight
    }

    private fun sanitizeSimilarity(value: Float): Float {
        check(value.isFinite()) { "Non-finite related ANN cosine" }
        return value.coerceIn(0f, 1f)
    }

    private data class SemanticMetadata(
        val clipId: Long,
        val sourceType: SemanticSourceType,
    )

    private data class RelatedChannelScores(
        var text: Float? = null,
        var summary: Float? = null,
        var ocr: Float? = null,
        var image: Float? = null,
    ) {
        fun update(sourceType: SemanticSourceType, score: Float) {
            when (sourceType) {
                SemanticSourceType.TEXT -> text = maxOf(text ?: 0f, score)
                SemanticSourceType.SUMMARY -> summary = maxOf(summary ?: 0f, score)
                SemanticSourceType.OCR -> ocr = maxOf(ocr ?: 0f, score)
            }
        }

        fun maxAvailable(): Float = maxOf(text ?: 0f, summary ?: 0f, ocr ?: 0f, image ?: 0f)
    }

    private data class AnnCache<T>(
        val revision: Long,
        val snapshot: RelatedSearchAnn?,
        val metadata: Map<Long, T>,
    )

    private class ProgressReporter(
        private val callback: (RelatedTweetsProgress) -> Unit,
    ) {
        private var lastPhase: RelatedTweetsProgressPhase? = null
        private var lastProcessed = -1

        fun emit(phase: RelatedTweetsProgressPhase, processed: Int, total: Int) {
            val step = maxOf(1, total / 8)
            if (phase != lastPhase || processed == total || processed - lastProcessed >= step) {
                callback(RelatedTweetsProgress(phase, processed, total))
                lastPhase = phase
                lastProcessed = processed
            }
        }
    }
}
