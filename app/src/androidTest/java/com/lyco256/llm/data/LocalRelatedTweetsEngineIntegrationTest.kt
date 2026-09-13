package com.lyco256.llm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalRelatedTweetsEngineIntegrationTest {
    private lateinit var context: Context
    private lateinit var derivedDirectory: File
    private lateinit var storage: DerivedSearchStorage

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        derivedDirectory = File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME)
        derivedDirectory.deleteRecursively()
        storage = DerivedSearchStorage(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            storage.close()
            derivedDirectory.deleteRecursively()
        }
    }

    @Test
    fun realUsearchRetrievesBothDimensionsAggregatesAndReusesRevisionCache() = runBlocking {
        addClip(1L, 101L, 1f)
        addClip(2L, 202L, 1f)
        addClip(3L, 303L, 1f)
        val factory = CountingRealAnnFactory()
        val engine = LocalRelatedTweetsEngine(storage, factory)

        val first = engine.findRelated(1L)
        val second = engine.findRelated(1L)

        assertTrue(first.map { it.clipId }.containsAll(listOf(2L, 3L)))
        assertEquals(first.map { it.clipId }, second.map { it.clipId })
        assertTrue(first.none { it.clipId == 1L })
        assertEquals(first.size, first.map { it.clipId }.distinct().size)
        assertEquals(2, factory.buildCount)
        assertEquals(0, factory.closeCount)

        storage.replaceSemanticSource(
            clipId = 2L,
            sourceType = SemanticSourceType.TEXT,
            sourceFingerprint = "clip-2-text-changed",
            documents = listOf(semantic(2L, SemanticSourceType.TEXT, 0, 2f)),
        )
        engine.findRelated(1L)
        assertEquals(3, factory.buildCount)
        assertEquals(1, factory.closeCount)

        engine.close()
        engine.close()
        assertEquals(3, factory.closeCount)
    }

    private suspend fun addClip(clipId: Long, assetId: Long, marker: Float) {
        listOf(SemanticSourceType.TEXT, SemanticSourceType.SUMMARY, SemanticSourceType.OCR).forEach { sourceType ->
            storage.replaceSemanticSource(
                clipId = clipId,
                sourceType = sourceType,
                sourceFingerprint = "clip-$clipId-${sourceType.storageValue}",
                documents = listOf(semantic(clipId, sourceType, 0, marker)),
            )
        }
        storage.replaceImageEmbedding(
            assetId = assetId,
            clipId = clipId,
            sourceFingerprint = "asset-$assetId",
            embeddingBlob = ImageEmbeddingBlobCodec.encode(vector(marker, 256)),
        )
    }

    private fun semantic(clipId: Long, sourceType: SemanticSourceType, ordinal: Int, marker: Float) =
        SemanticDocument(
            documentId = SemanticTextChunker.documentId(clipId, sourceType, ordinal),
            clipId = clipId,
            sourceType = sourceType,
            sourceOrdinal = ordinal,
            embedding = vector(marker, 768),
        )

    private fun vector(marker: Float, dimension: Int): FloatArray = FloatArray(dimension) { index ->
        if (index == 0) marker else 0f
    }

    private class CountingRealAnnFactory : RelatedSearchAnnFactory {
        var buildCount = 0
        var closeCount = 0

        override suspend fun build(dimension: Int, entries: List<LocalAnnEntry>): RelatedSearchAnn {
            buildCount++
            val snapshot = LocalAnnIndexSnapshot.build(dimension, entries)
            return object : RelatedSearchAnn {
                override suspend fun search(query: FloatArray, candidateCount: Int): List<AnnHit> =
                    snapshot.search(query, candidateCount)

                override fun close() {
                    closeCount++
                    snapshot.close()
                }
            }
        }
    }
}
