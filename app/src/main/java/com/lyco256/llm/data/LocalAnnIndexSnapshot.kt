package com.lyco256.llm.data

import android.os.Build
import cloud.unum.usearch.Index
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

data class LocalAnnEntry(
    val key: Long,
    val vector: FloatArray,
)

data class AnnHit(
    val key: Long,
    val cosineSimilarity: Float,
)

class LocalAnnIndexSnapshot private constructor(
    val dimension: Int,
    private var backend: LocalAnnIndexBackend?,
    private var normalizedVectors: Map<Long, FloatArray>,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit> = synchronized(lock) {
        check(!closed) { "ANN snapshot is closed" }
        require(candidateCount > 0) { "candidateCount must be positive" }
        val normalizedQuery = normalizeVector(query, dimension, "query")
        val backend = backend ?: return@synchronized emptyList()
        val maxCandidates = minOf(candidateCount, normalizedVectors.size)
        val nativeKeys = backend.search(normalizedQuery, maxCandidates)
        check(nativeKeys.size <= normalizedVectors.size) {
            "ANN backend returned more keys than the snapshot contains"
        }

        val seenKeys = HashSet<Long>(nativeKeys.size)
        val hits = ArrayList<AnnHit>(nativeKeys.size)
        nativeKeys.forEach { key ->
            check(normalizedVectors.containsKey(key)) {
                "ANN backend returned an unknown key: $key"
            }
            if (!seenKeys.add(key)) return@forEach
            val vector = normalizedVectors.getValue(key)
            var score = 0f
            for (index in 0 until dimension) {
                score += normalizedQuery[index] * vector[index]
            }
            check(score.isFinite()) { "ANN exact cosine score is not finite" }
            hits.add(AnnHit(key, score))
        }
        hits.sortedWith(compareByDescending<AnnHit> { it.cosineSimilarity }.thenBy { it.key })
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val currentBackend = backend
            backend = null
            normalizedVectors = emptyMap()
            runCatching { currentBackend?.close() }
        }
    }

    companion object {
        private val SUPPORTED_DIMENSIONS = setOf(256, 768)

        suspend fun build(
            dimension: Int,
            entries: List<LocalAnnEntry>,
        ): LocalAnnIndexSnapshot = withContext(Dispatchers.Default) {
            buildInternal(dimension, entries, UsearchAnnBackendFactory)
        }

        internal suspend fun buildForTesting(
            dimension: Int,
            entries: List<LocalAnnEntry>,
            backendFactory: LocalAnnBackendFactory,
        ): LocalAnnIndexSnapshot = withContext(Dispatchers.Default) {
            buildInternal(dimension, entries, backendFactory)
        }

        private suspend fun buildInternal(
            dimension: Int,
            entries: List<LocalAnnEntry>,
            backendFactory: LocalAnnBackendFactory,
        ): LocalAnnIndexSnapshot {
            require(dimension in SUPPORTED_DIMENSIONS) {
                "ANN dimension must be 256 or 768: $dimension"
            }

            val normalizedVectors = LinkedHashMap<Long, FloatArray>(entries.size)
            entries.forEachIndexed { index, entry ->
                coroutineContext.ensureActive()
                check(normalizedVectors.put(entry.key, normalizeVector(entry.vector, dimension, "entry[$index]")) == null) {
                    "Duplicate ANN key: ${entry.key}"
                }
            }
            coroutineContext.ensureActive()

            if (normalizedVectors.isEmpty()) {
                return LocalAnnIndexSnapshot(dimension, null, emptyMap())
            }

            var backend: LocalAnnIndexBackend? = null
            try {
                val createdBackend = backendFactory.create(dimension, normalizedVectors.size)
                backend = createdBackend
                normalizedVectors.forEach { (key, vector) ->
                    coroutineContext.ensureActive()
                    createdBackend.add(key, vector)
                }
                coroutineContext.ensureActive()
                return LocalAnnIndexSnapshot(dimension, createdBackend, normalizedVectors)
            } catch (throwable: Throwable) {
                runCatching { backend?.close() }
                throw throwable
            }
        }

        private fun normalizeVector(vector: FloatArray, dimension: Int, label: String): FloatArray {
            require(vector.size == dimension) {
                "$label dimension mismatch: expected $dimension, got ${vector.size}"
            }
            var squaredNorm = 0.0
            vector.forEachIndexed { index, value ->
                require(value.isFinite()) { "$label[$index] must be finite" }
                squaredNorm += value.toDouble() * value.toDouble()
            }
            val norm = sqrt(squaredNorm)
            require(norm.isFinite() && norm > 0.0) { "$label must have a positive finite L2 norm" }

            return FloatArray(dimension) { index ->
                val normalized = (vector[index].toDouble() / norm).toFloat()
                check(normalized.isFinite()) { "$label[$index] normalization is not finite" }
                normalized
            }
        }
    }
}

internal interface LocalAnnIndexBackend : AutoCloseable {
    fun add(key: Long, vector: FloatArray)
    fun search(query: FloatArray, candidateCount: Int): LongArray
}

internal fun interface LocalAnnBackendFactory {
    fun create(dimension: Int, capacity: Int): LocalAnnIndexBackend
}

internal fun requireSupportedUsearchAbi(supportedAbis: List<String>) {
    check(supportedAbis.any { it == "arm64-v8a" || it == "armeabi-v7a" }) {
        "USearch ANN runtime does not support device ABIs: ${supportedAbis.joinToString()}"
    }
}

private object UsearchAnnBackendFactory : LocalAnnBackendFactory {
    override fun create(dimension: Int, capacity: Int): LocalAnnIndexBackend {
        requireSupportedUsearchAbi(Build.SUPPORTED_ABIS.asList())
        val index = Index.Config()
            .metric(Index.Metric.COSINE)
            .quantization(Index.Quantization.FLOAT32)
            .dimensions(dimension.toLong())
            .capacity(capacity.toLong())
            .connectivity(32)
            .expansion_add(256)
            .expansion_search(256)
            .build()
        return try {
            index.reserve(capacity.toLong(), 1, 1)
            UsearchAnnBackend(index)
        } catch (throwable: Throwable) {
            runCatching { index.close() }
            throw throwable
        }
    }
}

private class UsearchAnnBackend(
    private val index: Index,
) : LocalAnnIndexBackend {
    private var closed = false

    override fun add(key: Long, vector: FloatArray) {
        check(!closed) { "USearch backend is closed" }
        index.add(key, vector)
    }

    override fun search(query: FloatArray, candidateCount: Int): LongArray {
        check(!closed) { "USearch backend is closed" }
        return index.search(query, candidateCount.toLong())
    }

    override fun close() {
        if (closed) return
        closed = true
        index.close()
    }
}
