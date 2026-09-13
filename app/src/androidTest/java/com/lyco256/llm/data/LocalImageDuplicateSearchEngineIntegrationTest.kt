package com.lyco256.llm.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalImageDuplicateSearchEngineIntegrationTest {
    private lateinit var root: File
    private lateinit var storage: DerivedSearchStorage

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "local-image-duplicate-search-fixture")
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
        check(root.mkdirs())
        storage = DerivedSearchStorage(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        })
    }

    @After
    fun tearDown() = runBlocking {
        storage.close()
        root.deleteRecursively()
        Unit
    }

    @Test
    fun nativeAnnReadsArtificial256DimensionalEmbeddingsAndReturnsValidAssetIds() = runBlocking {
        storage.replaceImageEmbedding(10L, 1L, "fixture-10", ImageEmbeddingBlobCodec.encode(vector(0)))
        storage.replaceImageEmbedding(20L, 2L, "fixture-20", ImageEmbeddingBlobCodec.encode(vector(1)))
        storage.replaceImageEmbedding(30L, 3L, "fixture-30", ImageEmbeddingBlobCodec.encode(vector(2)))
        val before = checkNotNull(storage.getImageSnapshot())
        val progress = mutableListOf<ImageDuplicateSearchProgress>()

        val result = LocalImageDuplicateSearchEngine(storage).search(progress::add)

        assertEquals(before.revision, result.sourceRevision)
        assertTrue(result.orderedAssetIds.all { it in setOf(10L, 20L, 30L) })
        assertEquals(result.orderedAssetIds.size, result.orderedAssetIds.distinct().size)
        assertEquals(ImageDuplicateSearchProgress(3, 3), progress.last())
        val after = checkNotNull(storage.getImageSnapshot())
        assertEquals(before.revision, after.revision)
        assertEquals(before.documents.map { it.assetId }, after.documents.map { it.assetId })
        assertEquals(before.documents.map { it.sourceFingerprint }, after.documents.map { it.sourceFingerprint })
    }

    private fun vector(seed: Int): FloatArray = FloatArray(ImageEmbeddingBlobCodec.DIMENSION) { index ->
        when (index) {
            seed -> 1f
            (seed + 1) % ImageEmbeddingBlobCodec.DIMENSION -> .25f
            else -> 0f
        }
    }
}
