package com.lyco256.llm.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.coroutineContext

class LocalSearchEngineTest {
    @Test fun allCachesRefreshBeforeFtsAndInferenceRemainsSequential() = runBlocking {
        val f = Fixture()
        f.data.semanticRows = listOf(semantic(1)); f.data.imageRows = listOf(image(1, 1))
        val events = mutableListOf<String>()
        val data = object : LocalSearchDataSource {
            override suspend fun lexical(knownRevision: Long?, queries: Fts5LiteralQueries): LexicalRetrievalSnapshot {
                val isFts = queries.normal.isNotEmpty() || queries.trigram.isNotEmpty()
                events.add(if (isFts) "fts" else "lexical-cache")
                // Exercise a lexical mutation between initial cache update and FTS acquisition.
                if (isFts) { f.data.lexicalRevision++; f.data.lexicalRows = listOf(lexical(2)) }
                return f.data.lexical(knownRevision, queries)
            }
            override suspend fun semantic(knownRevision: Long?): SearchDataSnapshot<SemanticDocument>? {
                events.add("semantic-cache"); return f.data.semantic(knownRevision)
            }
            override suspend fun images(knownRevision: Long?): SearchDataSnapshot<ImageEmbeddingDocument>? {
                events.add("image-cache"); return f.data.images(knownRevision)
            }
        }
        val engine = LocalSearchEngine(data,
            LexicalTextAnalyzer { events.add("analyze"); analysis(it) },
            QueryEmbedder { events.add("text"); TextEmbedding.fromModelOutput(FloatArray(768) { 1f }) },
            MultimodalTextEmbedder { events.add("image"); MultimodalEmbedding.fromModelOutput(FloatArray(256) { 1f }) },
            SearchAnnFactory { dimension, entries -> events.add("build-$dimension"); f.factory.build(dimension, entries) })
        try {
            assertTrue(engine.search("abcd").any { it.clipId == 2L })
            assertEquals(listOf("analyze", "lexical-cache", "semantic-cache", "build-768", "image-cache", "build-256",
                "fts", "text", "image"), events)
        } finally { engine.close() }
    }

    @Test fun candidatesAggregateByMaxAdmitIndependentlyAndSortDeterministically() = runBlocking {
        val fixture = Fixture()
        fixture.data.lexicalRows = listOf(lexical(10))
        fixture.data.semanticRows = listOf(semantic(10), semantic(20), semantic(20, 1), semantic(40), semantic(60), semantic(70))
        fixture.data.imageRows = listOf(image(1, 10), image(2, 30), image(3, 30), image(4, 40), image(5, 50), image(6, 70))
        fixture.factory.hits = { dimension, entries -> entries.map { entry ->
            AnnHit(entry.key, if (dimension == 768) when (entry.key) {
                0L -> 0.5f; 1L, 2L -> 0.5f; 3L -> 0.1f; 4L -> 0.5f; else -> -0.5f
            } else when (entry.key) { 1L -> 0.5f; 2L, 3L, 5L -> 1f; 4L -> 0.1f; else -> -0.5f })
        } }
        val engine = fixture.engine()
        try {
            val result = engine.search("abcd")
            assertEquals(listOf(10L, 20L, 30L, 50L, 60L), result.map { it.clipId })
            assertEquals(0.82f, result.first().score, 0.00001f)
            assertTrue(result.drop(1).all { kotlin.math.abs(it.score - 0.12f) < 0.00001f })
            assertEquals(result.size, result.map { it.clipId }.toSet().size)
            assertTrue(result.all { it.score.isFinite() })
            assertEquals(listOf("analyze", "text", "image"), fixture.calls)
            assertEquals(result, engine.search("abcd"))
        } finally { engine.close() }
    }

    @Test fun blankEmptyDataAndClosedEngineNeverInitializeUnneededResources() = runBlocking {
        val fixture = Fixture()
        val engine = fixture.engine()
        assertTrue(engine.search(" \n\t").isEmpty())
        assertTrue(fixture.calls.isEmpty())
        assertEquals(0, fixture.data.reads)
        assertTrue(engine.search("x").isEmpty())
        assertEquals(listOf("analyze"), fixture.calls)
        assertTrue(fixture.factory.snapshots.isEmpty())
        fixture.data.lexicalRows = listOf(lexical(1)); fixture.data.lexicalRevision++
        assertEquals(listOf(1L), engine.search("abcd").map { it.clipId })
        assertFalse(fixture.calls.contains("text")); assertFalse(fixture.calls.contains("image"))
        engine.close(); engine.close()
        assertTrue(runCatching { engine.search("") }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun revisionsIndependentlyRefreshCachesAndCloseOnlyReplacedSnapshots() = runBlocking {
        val f = Fixture()
        f.data.semanticRows = listOf(semantic(1)); f.data.imageRows = listOf(image(2, 2))
        val engine = f.engine()
        engine.search("abcd"); engine.search("abcd")
        assertEquals(listOf(768, 256), f.factory.snapshots.map { it.dimension })
        assertEquals(1, f.data.lexicalLoads)
        f.data.semanticRevision++
        engine.search("abcd")
        assertEquals(listOf(768, 256, 768), f.factory.snapshots.map { it.dimension })
        assertEquals(1, f.factory.snapshots[0].closes)
        assertEquals(0, f.factory.snapshots[1].closes)
        f.data.imageRevision++
        f.data.lexicalRevision++; f.data.lexicalRows = listOf(lexical(3))
        assertTrue(engine.search("abcd").any { it.clipId == 3L })
        assertEquals(2, f.data.lexicalLoads)
        assertEquals(1, f.factory.snapshots[1].closes)
        f.data.semanticRows = emptyList(); f.data.semanticRevision++
        f.data.imageRows = emptyList(); f.data.imageRevision++
        f.calls.clear()
        engine.search("abcd")
        assertEquals(listOf("analyze"), f.calls)
        assertEquals(4, f.factory.snapshots.size)
        engine.close(); engine.close()
        assertTrue(f.factory.snapshots.all { it.closes == 1 })
    }

    @Test fun failedRebuildNeverSearchesOldOrPartialSnapshotAndRetriesNextCall() = runBlocking {
        for (dimension in listOf(768, 256)) {
            val f = Fixture()
            f.data.semanticRows = listOf(semantic(1)); f.data.imageRows = listOf(image(2, 2))
            val engine = f.engine()
            engine.search("abcd")
            val old = f.factory.snapshots.single { it.dimension == dimension }
            if (dimension == 768) f.data.semanticRevision++ else f.data.imageRevision++
            f.factory.failDimension = dimension
            assertTrue(runCatching { engine.search("abcd") }.isFailure)
            assertEquals(1, old.searches)
            assertEquals(0, old.closes)
            assertEquals(1, f.factory.snapshots.last().closes)
            f.factory.failDimension = null
            engine.search("abcd")
            assertEquals(1, old.closes)
            engine.close()
            assertTrue(f.factory.snapshots.all { it.closes == 1 })
        }
    }

    @Test fun cancelledBuildClosesNewSnapshotKeepsOldCacheAndAllowsRetry() = runBlocking {
        val f = Fixture()
        f.data.semanticRows = listOf(semantic(1))
        val engine = f.engine()
        engine.search("abcd")
        val old = f.factory.snapshots.single()
        f.data.semanticRevision++
        f.factory.cancelBuild = true
        val request = async { engine.search("abcd") }
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, old.closes)
        assertEquals(1, old.searches)
        assertEquals(1, f.factory.snapshots.last().closes)
        f.factory.cancelBuild = false
        engine.search("abcd")
        assertEquals(1, old.closes)
        engine.close()
    }

    @Test fun searchesSerializeAndCancelledWaiterDoesNotAnalyzeOrInfer() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var analyses = 0
        val engine = f.engine(LexicalTextAnalyzer {
            analyses++
            entered.complete(Unit)
            release.await()
            analysis(it)
        })
        val first = async { engine.search("abcd") }
        entered.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { engine.search("abcd") }
        waiter.cancelAndJoin()
        release.complete(Unit)
        first.await()
        assertEquals(1, analyses)
        engine.close()
    }

    @Test fun errorsArePropagatedAndNonFiniteAnnScoresAreRejected() = runBlocking {
        val f = Fixture()
        f.data.semanticRows = listOf(semantic(1))
        f.factory.hits = { _, entries -> entries.map { AnnHit(it.key, Float.NaN) } }
        val engine = f.engine()
        assertTrue(runCatching { engine.search("abcd") }.exceptionOrNull() is IllegalStateException)
        f.factory.hits = { _, entries -> entries.map { AnnHit(it.key, Float.POSITIVE_INFINITY) } }
        // A separate engine adopts the changed fake behavior.
        engine.close()
        val other = f.engine()
        assertTrue(runCatching { other.search("abcd") }.isFailure)
        other.close()
        val analyzerFailure = f.engine(LexicalTextAnalyzer { error("analysis failure") })
        assertTrue(runCatching { analyzerFailure.search("abcd") }.isFailure)
        analyzerFailure.close()
        f.data.lexicalRows = listOf(lexical(2).copy(sourceType = "unknown"))
        val corrupt = f.engine()
        assertTrue(runCatching { corrupt.search("abcd") }.isFailure)
        corrupt.close()
    }

    @Test fun storageAndModelFailuresPropagateWithoutPartialResults() = runBlocking {
        val f = Fixture()
        f.data.semanticRows = listOf(semantic(1)); f.data.imageRows = listOf(image(1, 1))
        for (stage in listOf("storage", "text", "image", "ann")) {
            val data = object : LocalSearchDataSource by f.data {
                override suspend fun semantic(knownRevision: Long?): SearchDataSnapshot<SemanticDocument>? {
                    if (stage == "storage") error("fixture storage read")
                    return f.data.semantic(knownRevision)
                }
            }
            val factory = SearchAnnFactory { dimension, entries ->
                val delegate = f.factory.build(dimension, entries)
                object : SearchAnn by delegate {
                    override suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit> {
                        if (stage == "ann") error("fixture ANN search")
                        return delegate.search(query, candidateCount)
                    }
                }
            }
            val engine = LocalSearchEngine(data, LexicalTextAnalyzer { analysis(it) },
                QueryEmbedder { if (stage == "text") error("fixture text model") else TextEmbedding.fromModelOutput(FloatArray(768) { 1f }) },
                MultimodalTextEmbedder { if (stage == "image") error("fixture image model") else MultimodalEmbedding.fromModelOutput(FloatArray(256) { 1f }) },
                factory)
            assertTrue(runCatching { engine.search("abcd") }.isFailure)
            engine.close()
        }
        assertTrue(f.factory.snapshots.all { it.closes == 1 })
    }

    @Test fun sourceWeightsAndCandidateCountAreStructuralNotQualityAssertions() = runBlocking {
        val f = Fixture()
        f.data.semanticRows = listOf(semantic(1).copy(sourceType = SemanticSourceType.OCR),
            semantic(1, 1).copy(sourceType = SemanticSourceType.SUMMARY))
        f.factory.hits = { _, entries -> entries.map { AnnHit(it.key, 1.000001f) } }
        val engine = f.engine()
        assertEquals(0.24f * 0.95f, engine.search("abcd").single().score, 0.00001f)
        engine.close()
        f.data.semanticRows = (1L..2050).map { semantic(it) }
        f.data.imageRows = (1L..2050).map { image(it, it) }
        val large = f.engine()
        assertTrue(large.search("abcd").all { it.score.isFinite() })
        large.close()
    }

    private class Fixture {
        val data = FakeData()
        val factory = FakeFactory()
        val calls = mutableListOf<String>()
        fun engine(analyzer: LexicalTextAnalyzer = LexicalTextAnalyzer { calls.add("analyze"); analysis(it) }) =
            LocalSearchEngine(data, analyzer,
                QueryEmbedder { calls.add("text"); TextEmbedding.fromModelOutput(FloatArray(768) { 1f }) },
                MultimodalTextEmbedder { calls.add("image"); MultimodalEmbedding.fromModelOutput(FloatArray(256) { 1f }) }, factory)
    }

    private class FakeData : LocalSearchDataSource {
        var lexicalRevision = 0L; var semanticRevision = 0L; var imageRevision = 0L
        var lexicalRows = emptyList<LexicalDocument>()
        var semanticRows = emptyList<SemanticDocument>()
        var imageRows = emptyList<ImageEmbeddingDocument>()
        var reads = 0; var lexicalLoads = 0
        override suspend fun lexical(knownRevision: Long?, queries: Fts5LiteralQueries): LexicalRetrievalSnapshot {
            reads++
            val changed = knownRevision != lexicalRevision
            if (changed) lexicalLoads++
            return LexicalRetrievalSnapshot(lexicalRevision, if (changed) lexicalRows else null,
                lexicalRows.map { it.documentId }.toSet())
        }
        override suspend fun semantic(knownRevision: Long?) =
            if (knownRevision == semanticRevision) null else SearchDataSnapshot(semanticRevision, semanticRows)
        override suspend fun images(knownRevision: Long?) =
            if (knownRevision == imageRevision) null else SearchDataSnapshot(imageRevision, imageRows)
    }

    private class FakeFactory : SearchAnnFactory {
        val snapshots = mutableListOf<FakeAnn>()
        var failDimension: Int? = null
        var cancelBuild = false
        var hits: (Int, List<LocalAnnEntry>) -> List<AnnHit> = { _, entries -> entries.map { AnnHit(it.key, 0.5f) } }
        override suspend fun build(dimension: Int, entries: List<LocalAnnEntry>): SearchAnn {
            assertTrue(entries.all { it.vector.size == dimension })
            val ann = FakeAnn(dimension, hits(dimension, entries)).also(snapshots::add)
            if (failDimension == dimension) { ann.close(); error("build failure") }
            if (cancelBuild) coroutineContext.job.cancel()
            return ann
        }
    }

    private class FakeAnn(val dimension: Int, val hits: List<AnnHit>) : SearchAnn {
        var closes = 0; var searches = 0
        override suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit> {
            check(closes == 0)
            assertEquals(dimension, query.size)
            assertEquals(minOf(2048, hits.size), candidateCount)
            searches++
            return hits
        }
        override fun close() { closes++ }
    }

    companion object {
        private fun analysis(text: String) = SudachiLexicalTextAnalysis(text, text, text, searchCompact(text))
        private fun lexical(id: Long) = LexicalDocument("$id", id, "text", 0, "abcd", "abcd", "abcd", "abcd", "abcd")
        private fun semantic(id: Long, ordinal: Int = 0) = SemanticDocument("$id:$ordinal", id, SemanticSourceType.TEXT,
            ordinal, FloatArray(768) { 1f })
        private fun image(asset: Long, clip: Long) = ImageEmbeddingDocument(asset, clip, "fixture", FloatArray(256) { 1f })
    }
}
