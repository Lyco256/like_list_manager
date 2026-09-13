package com.lyco256.llm.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageDuplicateSearchEngineTest {
    @Test
    fun emptyAndSingleSnapshotsSkipAnnAndReportCompletion() = runBlocking {
        listOf(emptyList(), listOf(document(7L))).forEach { documents ->
            val fixture = Fixture(documents)
            val progress = mutableListOf<ImageDuplicateSearchProgress>()
            val result = fixture.engine.search(progress::add)

            assertTrue(result.orderedAssetIds.isEmpty())
            assertEquals(0, result.groupCount)
            assertEquals(41L, result.sourceRevision)
            assertEquals(1, fixture.reads)
            assertEquals(0, fixture.factory.buildCount)
            assertEquals(listOf(ImageDuplicateSearchProgress(0, documents.size), ImageDuplicateSearchProgress(documents.size, documents.size)), progress)
        }
    }

    @Test
    fun usesOne256AnnAndCapsCandidatesWithoutRecomputingPairCosines() = runBlocking {
        val documents = (1L..40L).map(::document)
        val fixture = Fixture(documents)
        fixture.factory.hitProvider = { sourceAssetId, candidateCount ->
            assertEquals(33, candidateCount)
            documents.map { candidate -> AnnHit(candidate.assetId, if (candidate.assetId == sourceAssetId) 1f else .8f) }
        }

        val result = fixture.engine.search()

        assertEquals(1, fixture.factory.buildCount)
        assertEquals(256, fixture.factory.dimensions.single())
        assertEquals(40, fixture.factory.ann.single().searches)
        assertEquals(documents.map(ImageEmbeddingDocument::assetId), result.orderedAssetIds)
        assertEquals(1, result.groupCount)
        assertEquals(33, fixture.factory.ann.single().maxObservedCandidateCount)
        assertTrue(fixture.factory.ann.single().receivedCandidateCounts.all { it == 33 })
    }

    @Test
    fun appliesStrongOrMutualThresholdsAndBuildsConnectedGroupsInDeterministicOrder() = runBlocking {
        val fixture = Fixture((1L..6L).map(::document))
        fixture.factory.hitProvider = { source, _ ->
            when (source) {
                1L -> listOf(AnnHit(1L, 1f), AnnHit(2L, .80f))
                2L -> listOf(AnnHit(2L, 1f), AnnHit(3L, .60f), AnnHit(1L, .80f))
                3L -> listOf(AnnHit(3L, 1f), AnnHit(2L, .61f), AnnHit(4L, .60f), AnnHit(5L, .50f))
                4L -> listOf(AnnHit(4L, 1f))
                else -> listOf(AnnHit(source, 1f))
            }
        }

        val result = fixture.engine.search()

        assertEquals(listOf(1L, 2L, 3L), result.orderedAssetIds)
        assertEquals(1, result.groupCount)
        assertTrue(result.orderedAssetIds.none { it == 4L || it == 5L || it == 6L })
    }

    @Test
    fun strongEdgeIsAcceptedEvenWhenOnlyOneDirectionContainsTheCandidate() = runBlocking {
        val fixture = Fixture((1L..3L).map(::document))
        fixture.factory.hitProvider = { source, _ ->
            when (source) {
                1L -> listOf(AnnHit(1L, 1f), AnnHit(2L, .72f))
                else -> listOf(AnnHit(source, 1f))
            }
        }

        val result = fixture.engine.search()

        assertEquals(listOf(1L, 2L), result.orderedAssetIds)
        assertEquals(1, result.groupCount)
    }

    @Test
    fun keepsAtMost32NeighborsAfterRemovingSelf() = runBlocking {
        val documents = (1L..34L).map(::document)
        val fixture = Fixture(documents)
        fixture.factory.hitProvider = { source, _ ->
            buildList {
                add(AnnHit(source, 1f))
                if (source != 34L) {
                    addAll(documents.filter { it.assetId != source && it.assetId != 34L }.map { AnnHit(it.assetId, .8f) })
                    add(AnnHit(34L, .8f))
                }
            }
        }

        val result = fixture.engine.search()

        assertEquals((1L..33L).toList(), result.orderedAssetIds)
        assertTrue(34L !in result.orderedAssetIds)
    }

    @Test
    fun strongerGroupSortsBeforeEqualScoreGroupAndUnorderedPairsAreDeduplicated() = runBlocking {
        val fixture = Fixture((1L..4L).map(::document))
        fixture.factory.hitProvider = { source, _ ->
            when (source) {
                1L -> listOf(AnnHit(1L, 1f), AnnHit(2L, .80f))
                2L -> listOf(AnnHit(2L, 1f), AnnHit(1L, .80f))
                3L -> listOf(AnnHit(3L, 1f), AnnHit(4L, .80f))
                4L -> listOf(AnnHit(4L, 1f), AnnHit(3L, .80f))
                else -> emptyList()
            }
        }

        val result = fixture.engine.search()

        assertEquals(listOf(1L, 2L, 3L, 4L), result.orderedAssetIds)
        assertEquals(2, result.groupCount)
        assertEquals(4, result.orderedAssetIds.distinct().size)
    }

    @Test
    fun nonFiniteScoreAndSearchFailureCloseTheOwnedAnnWithoutPartialResult() = runBlocking {
        val nonFinite = Fixture((1L..2L).map(::document))
        nonFinite.factory.hitProvider = { source, _ -> listOf(AnnHit(source, Float.NaN)) }
        assertTrue(runCatching { nonFinite.engine.search() }.isFailure)
        assertTrue(nonFinite.factory.ann.single().closed)

        val failedSearch = Fixture((1L..2L).map(::document))
        failedSearch.factory.hitProvider = { _, _ -> error("fake ANN failure") }
        assertTrue(runCatching { failedSearch.engine.search() }.isFailure)
        assertTrue(failedSearch.factory.ann.single().closed)
    }

    @Test
    fun cancellationClosesAnnAndDoesNotPublishAResult() = runBlocking {
        val fixture = Fixture((1L..2L).map(::document))
        val entered = CompletableDeferred<Unit>()
        fixture.factory.blockSearch = entered
        val job = launch { fixture.engine.search() }
        entered.await()

        job.cancelAndJoin()

        assertTrue(fixture.factory.ann.single().closed)
        assertTrue(job.isCancelled)
    }

    private class Fixture(documents: List<ImageEmbeddingDocument>) {
        var reads = 0
        val factory = RecordingFactory()
        val engine = LocalImageDuplicateSearchEngine(
            data = ImageDuplicateSearchDataSource {
                reads++
                SearchDataSnapshot(41L, documents)
            },
            annFactory = factory,
        )
    }

    private class RecordingFactory : SearchAnnFactory {
        var buildCount = 0
        var hitProvider: (Long, Int) -> List<AnnHit> = { source, _ -> listOf(AnnHit(source, 1f)) }
        var blockSearch: CompletableDeferred<Unit>? = null
        val dimensions = mutableListOf<Int>()
        val ann = mutableListOf<RecordingAnn>()

        override suspend fun build(dimension: Int, entries: List<LocalAnnEntry>): SearchAnn {
            buildCount++
            dimensions += dimension
            return RecordingAnn(entries, this).also(ann::add)
        }
    }

    private class RecordingAnn(
        entries: List<LocalAnnEntry>,
        private val owner: RecordingFactory,
    ) : SearchAnn {
        private val assetIdsByFirstValue = entries.associate { it.vector.first().toLong() to it.key }
        val receivedCandidateCounts = mutableListOf<Int>()
        var searches = 0
        var closed = false
        var maxObservedCandidateCount = 0

        override suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit> {
            check(!closed)
            searches++
            receivedCandidateCounts += candidateCount
            maxObservedCandidateCount = maxOf(maxObservedCandidateCount, candidateCount)
            owner.blockSearch?.complete(Unit)
            owner.blockSearch?.let { awaitCancellation() }
            return owner.hitProvider(assetIdsByFirstValue.getValue(query.first().toLong()), candidateCount)
        }

        override fun close() {
            if (!closed) closed = true
        }
    }

    private fun document(assetId: Long) = ImageEmbeddingDocument(
        assetId = assetId,
        clipId = assetId,
        sourceFingerprint = "fixture-$assetId",
        embedding = FloatArray(ImageEmbeddingBlobCodec.DIMENSION).also { it[0] = assetId.toFloat() },
    )
}
