package com.lyco256.llm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class LocalSearchResult(val clipId: Long, val score: Float)

object LocalSearchTuning {
    const val LEXICAL_WEIGHT = 0.64f
    const val TEXT_WEIGHT = 0.24f
    const val IMAGE_WEIGHT = 0.12f
    const val LEXICAL_THRESHOLD = 0.30f
    const val TEXT_THRESHOLD = 0.20f
    const val IMAGE_THRESHOLD = 0.18f
    const val FUZZY_CAP = 0.62f
    const val ANAGRAM_CAP = 0.42f
    const val ANN_CANDIDATES = 2048
}

/** Read-only boundary; the engine has no Room, mutation, network, or UI dependency. */
internal interface LocalSearchDataSource {
    suspend fun lexical(knownRevision: Long?, queries: Fts5LiteralQueries): LexicalRetrievalSnapshot
    suspend fun semantic(knownRevision: Long?): SearchDataSnapshot<SemanticDocument>?
    suspend fun images(knownRevision: Long?): SearchDataSnapshot<ImageEmbeddingDocument>?
}

internal class StoredLocalSearchDataSource(private val storage: DerivedSearchStorage) : LocalSearchDataSource {
    override suspend fun lexical(knownRevision: Long?, queries: Fts5LiteralQueries) = storage.retrieveLexical(knownRevision, queries)
    override suspend fun semantic(knownRevision: Long?) = storage.getSemanticSnapshot(knownRevision)
    override suspend fun images(knownRevision: Long?) = storage.getImageSnapshot(knownRevision)
}

internal interface SearchAnn : AutoCloseable {
    suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit>
}

internal fun interface SearchAnnFactory {
    suspend fun build(dimension: Int, entries: List<LocalAnnEntry>): SearchAnn
}

internal object NativeSearchAnnFactory : SearchAnnFactory {
    override suspend fun build(dimension: Int, entries: List<LocalAnnEntry>): SearchAnn {
        val snapshot = LocalAnnIndexSnapshot.build(dimension, entries)
        return object : SearchAnn {
            override suspend fun search(query: FloatArray, candidateCount: Int) = snapshot.search(query, candidateCount)
            override fun close() = snapshot.close()
        }
    }
}

/** Shared runtimes are borrowed. Only the engine's ANN snapshots and caches are owned. */
class LocalSearchEngine internal constructor(
    private val data: LocalSearchDataSource,
    private val analyzer: LexicalTextAnalyzer,
    private val textEmbedder: QueryEmbedder,
    private val imageEmbedder: MultimodalTextEmbedder,
    private val annFactory: SearchAnnFactory,
) {
    constructor(
        storage: DerivedSearchStorage,
        analyzer: LexicalTextAnalyzer,
        textEmbedder: QueryEmbedder,
        imageEmbedder: MultimodalTextEmbedder,
    ) : this(StoredLocalSearchDataSource(storage), analyzer, textEmbedder, imageEmbedder, NativeSearchAnnFactory)

    private val mutex = Mutex()
    private var closed = false
    private var lexical: LexicalCache? = null
    private var semantic: AnnCache<SemanticMetadata>? = null
    private var images: AnnCache<Long>? = null

    suspend fun search(query: String): List<LocalSearchResult> = withContext(Dispatchers.Default) {
        mutex.withLock {
            coroutineContext.ensureActive()
            check(!closed) { "Local search engine is closed" }
            if (query.isBlank()) return@withLock emptyList()
            val representation = SearchRepresentation.query(query, analyzer.analyze(query))
            coroutineContext.ensureActive()
            updateLexical(data.lexical(lexical?.revision, Fts5LiteralQueries(emptyList(), emptyList())))
            updateSemantic()
            updateImages()
            val retrieval = data.lexical(lexical?.revision, Fts5LiteralQueryBuilder.build(representation))
            // A writer may have changed lexical data while ANN was building. Refresh atomically
            // with FTS candidates so the cache never associates those IDs with another revision.
            updateLexical(retrieval)
            val corpus = checkNotNull(lexical)
            check(corpus.revision == retrieval.revision) { "Lexical revision mismatch" }

            val scores = linkedMapOf<Long, ClipScores>()
            // Evaluate FTS hits first and never score a document twice.
            val ordered = linkedSetOf<String>().apply {
                addAll(retrieval.candidateDocumentIds)
                addAll(corpus.byId.keys)
            }
            for (id in ordered) {
                coroutineContext.ensureActive()
                val document = checkNotNull(corpus.byId[id]) { "FTS document missing from lexical snapshot" }
                val ordinary = LocalLexicalScorer.ordinary(representation, document.representation)
                // The fallback scan also checks cheap literal evidence, before bounded edit distance.
                val lexicalScore = when {
                    ordinary > 0f -> ordinary
                    LocalLexicalScorer.shortMatch(representation, document.representation) -> 0.74f
                    else -> maxOf(LexicalFallback.fuzzy(representation, document.representation),
                        LexicalFallback.anagram(representation, document.representation))
                } * document.weight
                if (lexicalScore > 0f) {
                    val clip = scores.getOrPut(document.document.clipId) { ClipScores() }
                    clip.lexical = maxOf(clip.lexical, lexicalScore)
                }
            }
            val semanticCache = checkNotNull(semantic)
            if (semanticCache.metadata.isNotEmpty()) {
                coroutineContext.ensureActive()
                val vector = textEmbedder.embedQuery(query).toFloatArray()
                coroutineContext.ensureActive()
                semanticCache.snapshot!!.search(vector, minOf(LocalSearchTuning.ANN_CANDIDATES, semanticCache.metadata.size))
                    .forEach { hit ->
                        coroutineContext.ensureActive()
                        val metadata = semanticCache.metadata.getValue(hit.key)
                        val clip = scores.getOrPut(metadata.clipId) { ClipScores() }
                        clip.text = maxOf(clip.text, cosine(hit.cosineSimilarity) * semanticSourceWeight(metadata.source))
                    }
            }
            val imageCache = checkNotNull(images)
            if (imageCache.metadata.isNotEmpty()) {
                coroutineContext.ensureActive()
                val vector = imageEmbedder.embedText(query).toFloatArray()
                coroutineContext.ensureActive()
                imageCache.snapshot!!.search(vector, minOf(LocalSearchTuning.ANN_CANDIDATES, imageCache.metadata.size))
                    .forEach { hit ->
                        coroutineContext.ensureActive()
                        val clip = scores.getOrPut(imageCache.metadata.getValue(hit.key)) { ClipScores() }
                        clip.image = maxOf(clip.image, cosine(hit.cosineSimilarity))
                    }
            }
            val ranked = scores.mapNotNull { (id, score) ->
                coroutineContext.ensureActive()
                if (score.lexical < LocalSearchTuning.LEXICAL_THRESHOLD && score.text < LocalSearchTuning.TEXT_THRESHOLD &&
                    score.image < LocalSearchTuning.IMAGE_THRESHOLD) null else {
                    val total = score.lexical * LocalSearchTuning.LEXICAL_WEIGHT + score.text * LocalSearchTuning.TEXT_WEIGHT +
                        score.image * LocalSearchTuning.IMAGE_WEIGHT
                    check(total.isFinite()) { "Non-finite local search score" }
                    LocalSearchResult(id, total.coerceIn(0f, 1f)) to score.lexical
                }
            }.sortedWith(compareByDescending<Pair<LocalSearchResult, Float>> { it.first.score }
                .thenByDescending { it.second }.thenBy { it.first.clipId }).map { it.first }
            coroutineContext.ensureActive()
            ranked
        }
    }

    /** Serialized with active searches; cleanup completes even if the caller is cancelled. */
    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                val oldSemantic = semantic
                val oldImages = images
                semantic = null
                images = null
                lexical = null
                try { oldSemantic?.snapshot?.close() } finally { oldImages?.snapshot?.close() }
            }
        }
    }

    private suspend fun updateLexical(retrieval: LexicalRetrievalSnapshot) {
        val documents = retrieval.documents ?: return
        val prepared = documents.map { document ->
            coroutineContext.ensureActive()
            PreparedDocument(document, SearchRepresentation.document(document), lexicalSourceWeight(document.sourceType))
        }
        lexical = LexicalCache(retrieval.revision, prepared.associateBy { it.document.documentId },
            prepared.groupBy { it.document.clipId })
    }

    private suspend fun updateSemantic() {
        val rows = data.semantic(semantic?.revision) ?: return
        val sorted = rows.documents.sortedWith(compareBy<SemanticDocument> { it.clipId }
            .thenBy { it.sourceType.storageValue }.thenBy { it.sourceOrdinal }.thenBy { it.documentId })
        val metadata = linkedMapOf<Long, SemanticMetadata>()
        val entries = sorted.mapIndexed { index, document ->
            coroutineContext.ensureActive()
            val key = index.toLong()
            metadata[key] = SemanticMetadata(document.clipId, document.sourceType)
            LocalAnnEntry(key, document.embedding)
        }
        val replacement = buildCache(rows.revision, 768, entries, metadata)
        var adopted = false
        try {
            coroutineContext.ensureActive()
            val previous = semantic
            semantic = replacement
            adopted = true
            previous?.snapshot?.close()
        } finally { if (!adopted) replacement.snapshot?.close() }
    }

    private suspend fun updateImages() {
        val rows = data.images(images?.revision) ?: return
        val metadata = linkedMapOf<Long, Long>()
        val entries = rows.documents.sortedBy { it.assetId }.map { document ->
            coroutineContext.ensureActive()
            check(metadata.put(document.assetId, document.clipId) == null) { "Duplicate image asset ID" }
            LocalAnnEntry(document.assetId, document.embedding)
        }
        val replacement = buildCache(rows.revision, 256, entries, metadata)
        var adopted = false
        try {
            coroutineContext.ensureActive()
            val previous = images
            images = replacement
            adopted = true
            previous?.snapshot?.close()
        } finally { if (!adopted) replacement.snapshot?.close() }
    }

    private suspend fun <T> buildCache(
        revision: Long, dimension: Int, entries: List<LocalAnnEntry>, metadata: Map<Long, T>,
    ): AnnCache<T> {
        if (entries.isEmpty()) return AnnCache(revision, null, metadata)
        val snapshot = annFactory.build(dimension, entries)
        return try {
            coroutineContext.ensureActive()
            AnnCache(revision, snapshot, metadata)
        } catch (failure: Throwable) { snapshot.close(); throw failure }
    }

    private fun cosine(value: Float): Float {
        check(value.isFinite()) { "Non-finite ANN cosine" }
        return value.coerceIn(0f, 1f)
    }

    private data class PreparedDocument(val document: LexicalDocument, val representation: SearchRepresentation, val weight: Float)
    private data class LexicalCache(
        val revision: Long, val byId: Map<String, PreparedDocument>, val byClip: Map<Long, List<PreparedDocument>>,
    )
    private data class SemanticMetadata(val clipId: Long, val source: SemanticSourceType)
    private data class AnnCache<T>(val revision: Long, val snapshot: SearchAnn?, val metadata: Map<Long, T>)
    private data class ClipScores(var lexical: Float = 0f, var text: Float = 0f, var image: Float = 0f)
}
