package com.lyco256.llm.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRelatedTweetsEngineTest {
    @Test
    fun emptyReferenceReturnsEmptyWithoutBuildingOrQueryingAnn() = runBlocking {
        val snapshot = MutableRelatedSnapshot(
            semanticRevision = 1L,
            imageRevision = 1L,
            referenceSemanticDocuments = emptyList(),
            referenceImageDocuments = emptyList(),
            semanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)),
            imageDocuments = listOf(image(10L, 1L, 5f)),
        )
        val factory = FakeAnnFactory(emptyMap())
        val engine = LocalRelatedTweetsEngine(snapshot, factory)

        assertTrue(engine.findRelated(1L).isEmpty())
        assertEquals(2, factory.buildCount)
        assertTrue(factory.requestedCandidateCounts.isEmpty())
    }

    @Test
    fun aggregatesMaximumPerChannelExcludesReferenceAndNormalizesAvailableWeights() = runBlocking {
        val snapshot = MutableRelatedSnapshot(
            semanticRevision = 1L,
            imageRevision = 1L,
            referenceSemanticDocuments = listOf(
                semantic(1L, SemanticSourceType.TEXT, 0, 1f),
                semantic(1L, SemanticSourceType.TEXT, 1, 2f),
            ),
            referenceImageDocuments = listOf(image(10L, 1L, 5f)),
            semanticDocuments = listOf(
                semantic(1L, SemanticSourceType.TEXT, 0, 1f),
                semantic(1L, SemanticSourceType.TEXT, 1, 2f),
                semantic(2L, SemanticSourceType.SUMMARY, 0, 10f),
                semantic(3L, SemanticSourceType.TEXT, 0, 11f),
            ),
            imageDocuments = listOf(
                image(10L, 1L, 5f),
                image(20L, 2L, 20f),
                image(30L, 3L, 30f),
            ),
        )
        val factory = FakeAnnFactory(
            plans = mapOf(
                1f to listOf(0L to .99f, 2L to .80f, 3L to .50f),
                2f to listOf(0L to .99f, 2L to .70f),
                5f to listOf(10L to .99f, 20L to .60f, 30L to .90f),
            ),
        )
        val engine = LocalRelatedTweetsEngine(snapshot, factory)

        val result = engine.findRelated(1L)

        assertEquals(listOf(2L, 3L), result.map { it.clipId })
        assertEquals((.80f * .40f + .60f * .20f) / .60f, result[0].score, .0001f)
        assertEquals((.50f * .40f + .90f * .20f) / .60f, result[1].score, .0001f)
        assertTrue(factory.requestedCandidateCounts.all { it <= RelatedTweetsTuning.ANN_CANDIDATES_PER_VECTOR })
        assertTrue(result.map { it.clipId }.distinct().size == result.size)
        engine.close()
        engine.close()
    }

    @Test
    fun aggregatesSummaryOcrAndMultipleImagesByMaximumWithoutCountingRows() = runBlocking {
        val referenceSemantic = listOf(
            semantic(1L, SemanticSourceType.TEXT, 0, 1f),
            semantic(1L, SemanticSourceType.TEXT, 1, 2f),
            semantic(1L, SemanticSourceType.SUMMARY, 0, 3f),
            semantic(1L, SemanticSourceType.SUMMARY, 1, 4f),
            semantic(1L, SemanticSourceType.OCR, 0, 5f),
            semantic(1L, SemanticSourceType.OCR, 1, 6f),
        )
        val candidateSemantic = listOf(
            semantic(1L, SemanticSourceType.TEXT, 0, 1f),
            semantic(1L, SemanticSourceType.TEXT, 1, 2f),
            semantic(1L, SemanticSourceType.SUMMARY, 0, 3f),
            semantic(1L, SemanticSourceType.SUMMARY, 1, 4f),
            semantic(1L, SemanticSourceType.OCR, 0, 5f),
            semantic(1L, SemanticSourceType.OCR, 1, 6f),
            semantic(2L, SemanticSourceType.TEXT, 0, 20f),
        )
        val snapshot = MutableRelatedSnapshot(
            semanticRevision = 1L,
            imageRevision = 1L,
            referenceSemanticDocuments = referenceSemantic,
            referenceImageDocuments = listOf(image(101L, 1L, 10f), image(102L, 1L, 11f)),
            semanticDocuments = candidateSemantic,
            imageDocuments = listOf(
                image(101L, 1L, 10f),
                image(102L, 1L, 11f),
                image(201L, 2L, 20f),
            ),
        )
        val factory = FakeAnnFactory(
            plans = mapOf(
                1f to listOf(6L to .80f),
                2f to listOf(6L to .70f),
                3f to listOf(6L to .60f),
                4f to listOf(6L to .40f),
                5f to listOf(6L to .30f),
                6f to listOf(6L to .70f),
                10f to listOf(201L to .50f),
                11f to listOf(201L to .90f),
            ),
        )
        val engine = LocalRelatedTweetsEngine(snapshot, factory)

        val result = engine.findRelated(1L)

        assertEquals(listOf(2L), result.map { it.clipId })
        assertEquals(.76f, result.single().score, .0001f)
    }

    @Test
    fun thresholdsTiesAndResultLimitAreDeterministic() = runBlocking {
        val candidateRows = (2L..80L).map { clipId ->
            semantic(clipId, SemanticSourceType.TEXT, 0, clipId.toFloat() + 10f)
        }
        val plans = buildMap {
            put(1f, candidateRows.mapIndexed { index, row -> (index + 1L) to if (index < 55) .5f else .1f })
        }
        val snapshot = MutableRelatedSnapshot(
            semanticRevision = 1L,
            imageRevision = 1L,
            referenceSemanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)),
            referenceImageDocuments = emptyList(),
            semanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)) + candidateRows,
            imageDocuments = emptyList(),
        )
        val engine = LocalRelatedTweetsEngine(snapshot, FakeAnnFactory(plans))

        val result = engine.findRelated(1L)

        assertEquals(50, result.size)
        assertEquals((2L..51L).toList(), result.map { it.clipId })
        assertTrue(result.zipWithNext().all { (left, right) -> left.score >= right.score })
    }

    @Test
    fun revisionCacheIsReusedThenReplacedAndClosedOnlyAfterSuccessfulBuild() = runBlocking {
        val first = MutableRelatedSnapshot(
            semanticRevision = 7L,
            imageRevision = 9L,
            referenceSemanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)),
            referenceImageDocuments = emptyList(),
            semanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f), semantic(2L, SemanticSourceType.TEXT, 0, 2f)),
            imageDocuments = emptyList(),
        )
        val source = MutableRelatedSnapshotSource(first)
        val factory = FakeAnnFactory(mapOf(1f to listOf(0L to 1f, 1L to .8f)))
        val engine = LocalRelatedTweetsEngine(source, factory)

        engine.findRelated(1L)
        engine.findRelated(1L)
        assertEquals(1, factory.buildCount)
        assertEquals(0, factory.closeCount)

        source.current = first.copy(
            semanticRevision = 8L,
            semanticDocuments = first.semanticDocuments!!.map { it.copy(embedding = vector(it.embedding[0] + 10f, 768)) },
        )
        engine.findRelated(1L)
        assertEquals(2, factory.buildCount)
        assertEquals(1, factory.closeCount)

        engine.close()
        assertEquals(2, factory.closeCount)
        engine.close()
        assertEquals(2, factory.closeCount)
    }

    @Test
    fun failedRevisionBuildKeepsOldSnapshotAndDoesNotAdoptPartialCache() = runBlocking {
        val first = MutableRelatedSnapshot(
            semanticRevision = 7L,
            imageRevision = 9L,
            referenceSemanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)),
            referenceImageDocuments = emptyList(),
            semanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f), semantic(2L, SemanticSourceType.TEXT, 0, 2f)),
            imageDocuments = emptyList(),
        )
        val source = MutableRelatedSnapshotSource(first)
        val factory = FakeAnnFactory(mapOf(1f to listOf(0L to 1f, 1L to .8f)))
        val engine = LocalRelatedTweetsEngine(source, factory)
        engine.findRelated(1L)

        source.current = first.copy(semanticRevision = 8L)
        factory.failBuild = true
        assertTrue(runCatching { engine.findRelated(1L) }.isFailure)
        assertEquals(0, factory.closeCount)

        factory.failBuild = false
        assertEquals(listOf(2L), engine.findRelated(1L).map { it.clipId })
        assertEquals(3, factory.buildCount)
        assertEquals(1, factory.closeCount)
    }

    @Test
    fun changingOnlyImageRevisionRebuildsImageAnnAndReusesSemanticAnn() = runBlocking {
        val first = MutableRelatedSnapshot(
            semanticRevision = 7L,
            imageRevision = 9L,
            referenceSemanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)),
            referenceImageDocuments = listOf(image(10L, 1L, 5f)),
            semanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f), semantic(2L, SemanticSourceType.TEXT, 0, 2f)),
            imageDocuments = listOf(image(10L, 1L, 5f), image(20L, 2L, 6f)),
        )
        val source = MutableRelatedSnapshotSource(first)
        val factory = FakeAnnFactory(
            plans = mapOf(
                1f to listOf(0L to 1f, 1L to .8f),
                5f to listOf(10L to 1f, 20L to .7f),
            ),
        )
        val engine = LocalRelatedTweetsEngine(source, factory)
        engine.findRelated(1L)
        assertEquals(listOf(768, 256), factory.builtDimensions)

        source.current = first.copy(
            imageRevision = 10L,
            imageDocuments = first.imageDocuments!!.map { it.copy(embedding = vector(it.embedding[0] + 1f, 256)) },
        )
        engine.findRelated(1L)

        assertEquals(listOf(768, 256, 256), factory.builtDimensions)
        assertEquals(1, factory.closeCount)
    }

    @Test
    fun equalTotalUsesHigherMaximumChannelBeforeClipId() = runBlocking {
        val snapshot = MutableRelatedSnapshot(
            semanticRevision = 1L,
            imageRevision = 1L,
            referenceSemanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)),
            referenceImageDocuments = listOf(image(10L, 1L, 5f)),
            semanticDocuments = listOf(
                semantic(1L, SemanticSourceType.TEXT, 0, 1f),
                semantic(2L, SemanticSourceType.TEXT, 0, 2f),
                semantic(3L, SemanticSourceType.TEXT, 0, 3f),
            ),
            imageDocuments = listOf(image(10L, 1L, 5f), image(20L, 2L, 6f), image(30L, 3L, 7f)),
        )
        val engine = LocalRelatedTweetsEngine(
            snapshot,
            FakeAnnFactory(
                plans = mapOf(
                    1f to listOf(0L to 1f, 1L to .80f, 2L to .70f),
                    5f to listOf(10L to 1f, 20L to .40f, 30L to .60f),
                ),
            ),
        )

        val result = engine.findRelated(1L)

        assertEquals(listOf(2L, 3L), result.map { it.clipId })
        assertEquals(result[0].score, result[1].score, .0001f)
    }

    @Test
    fun candidateCountIsBoundedPerReferenceVectorAndNoAllPairsScanIsNeeded() = runBlocking {
        val corpus = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)) +
            (2L..401L).map { semantic(it, SemanticSourceType.TEXT, 0, it.toFloat()) }
        val snapshot = MutableRelatedSnapshot(
            semanticRevision = 1L,
            imageRevision = 1L,
            referenceSemanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)),
            referenceImageDocuments = emptyList(),
            semanticDocuments = corpus,
            imageDocuments = emptyList(),
        )
        val factory = FakeAnnFactory(mapOf(1f to (0L until 400L).map { it to .8f }))
        val engine = LocalRelatedTweetsEngine(snapshot, factory)

        engine.findRelated(1L)

        assertEquals(listOf(RelatedTweetsTuning.ANN_CANDIDATES_PER_VECTOR), factory.requestedCandidateCounts)
    }

    @Test
    fun cancellationDoesNotReturnPartialResults() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val snapshot = MutableRelatedSnapshot(
            semanticRevision = 1L,
            imageRevision = 1L,
            referenceSemanticDocuments = listOf(
                semantic(1L, SemanticSourceType.TEXT, 0, 1f),
                semantic(1L, SemanticSourceType.TEXT, 1, 2f),
            ),
            referenceImageDocuments = emptyList(),
            semanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f), semantic(2L, SemanticSourceType.TEXT, 0, 2f)),
            imageDocuments = emptyList(),
        )
        val engine = LocalRelatedTweetsEngine(
            snapshot,
            FakeAnnFactory(
                plans = mapOf(1f to listOf(0L to 1f, 1L to .8f), 2f to listOf(0L to 1f, 1L to .8f)),
                onSearch = { started.complete(Unit); delay(10_000L) },
            ),
        )
        val job: Job = launch { engine.findRelated(1L) }
        withTimeout(5_000L) { started.await() }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun nonFiniteAnnScoreFailsInsteadOfReturningNormalResult() = runBlocking {
        val snapshot = MutableRelatedSnapshot(
            semanticRevision = 1L,
            imageRevision = 1L,
            referenceSemanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f)),
            referenceImageDocuments = emptyList(),
            semanticDocuments = listOf(semantic(1L, SemanticSourceType.TEXT, 0, 1f), semantic(2L, SemanticSourceType.TEXT, 0, 2f)),
            imageDocuments = emptyList(),
        )
        val engine = LocalRelatedTweetsEngine(snapshot, FakeAnnFactory(mapOf(1f to listOf(0L to 1f, 1L to Float.NaN))))

        val failure = runCatching { engine.findRelated(1L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun semantic(clipId: Long, sourceType: SemanticSourceType, ordinal: Int, marker: Float) =
        SemanticDocument(
            documentId = SemanticTextChunker.documentId(clipId, sourceType, ordinal),
            clipId = clipId,
            sourceType = sourceType,
            sourceOrdinal = ordinal,
            embedding = vector(marker, 768),
        )

    private fun image(assetId: Long, clipId: Long, marker: Float) = ImageEmbeddingDocument(
        assetId = assetId,
        clipId = clipId,
        sourceFingerprint = "fingerprint-$assetId",
        embedding = vector(marker, 256),
    )

    private fun vector(marker: Float, dimension: Int): FloatArray = FloatArray(dimension) { index ->
        if (index == 0) marker else 0f
    }

    private data class MutableRelatedSnapshot(
        val semanticRevision: Long,
        val imageRevision: Long,
        val referenceSemanticDocuments: List<SemanticDocument>,
        val referenceImageDocuments: List<ImageEmbeddingDocument>,
        val semanticDocuments: List<SemanticDocument>?,
        val imageDocuments: List<ImageEmbeddingDocument>?,
    ) : RelatedSearchDataSource {
        override suspend fun snapshot(
            clipId: Long,
            knownSemanticRevision: Long?,
            knownImageRevision: Long?,
        ): RelatedRetrievalSnapshot = RelatedRetrievalSnapshot(
            semanticRevision = semanticRevision,
            imageRevision = imageRevision,
            referenceSemanticDocuments = referenceSemanticDocuments,
            referenceImageDocuments = referenceImageDocuments,
            semanticDocuments = if (knownSemanticRevision == semanticRevision) null else semanticDocuments,
            imageDocuments = if (knownImageRevision == imageRevision) null else imageDocuments,
        )
    }

    private class MutableRelatedSnapshotSource(var current: MutableRelatedSnapshot) : RelatedSearchDataSource {
        override suspend fun snapshot(clipId: Long, knownSemanticRevision: Long?, knownImageRevision: Long?): RelatedRetrievalSnapshot =
            current.snapshot(clipId, knownSemanticRevision, knownImageRevision)
    }

    private class FakeAnnFactory(
        private val plans: Map<Float, List<Pair<Long, Float>>>,
        private val onSearch: suspend () -> Unit = {},
    ) : RelatedSearchAnnFactory {
        var buildCount = 0
        var closeCount = 0
        var failBuild = false
        val builtDimensions = mutableListOf<Int>()
        val requestedCandidateCounts = mutableListOf<Int>()

        override suspend fun build(dimension: Int, entries: List<LocalAnnEntry>): RelatedSearchAnn {
            buildCount++
            builtDimensions += dimension
            if (failBuild) error("fake ANN build failure")
            return object : RelatedSearchAnn {
                override suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit> {
                    requestedCandidateCounts += candidateCount
                    onSearch()
                    return plans[query[0]].orEmpty().take(candidateCount).map { (key, score) -> AnnHit(key, score) }
                }

                override fun close() {
                    closeCount++
                }
            }
        }
    }
}
