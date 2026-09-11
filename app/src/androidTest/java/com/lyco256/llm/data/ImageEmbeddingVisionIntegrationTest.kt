package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageEmbeddingVisionIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: LikeListDatabase
    private lateinit var databaseFlow: MutableStateFlow<LikeListDatabase?>
    private lateinit var storage: DerivedSearchStorage
    private lateinit var imageFile: File
    private lateinit var embedder: LocalMultimodalEmbedder
    private lateinit var synchronizer: ImageEmbeddingSynchronizer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        imageFile = File(context.cacheDir, "image-embedding-vision-test.png")
        imageFile.delete()
        writeImage(imageFile)
        File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME).deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, LikeListDatabase::class.java).build()
        databaseFlow = MutableStateFlow(database)
        storage = DerivedSearchStorage(context)
        embedder = LocalMultimodalEmbedder(context)
        synchronizer = ImageEmbeddingSynchronizer(
            databaseFlow = databaseFlow,
            derivedSearchStorage = storage,
            embedder = embedder,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        runBlocking {
            synchronizer.stop()
            storage.close()
        }
        runCatching { embedder.close() }
        scope.cancel()
        database.close()
        imageFile.delete()
        File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME).deleteRecursively()
    }

    @Test
    fun realVisionRuntimeStoresReusesAndDeletesAnImageEmbedding() = runBlocking {
        val clipId = database.clipDao().insertClip(
            ClipEntity(
                xPostId = "vision-post",
                authorName = "vision display",
                authorUsername = "vision user",
                text = "vision text",
                postUrl = "https://example.invalid/vision",
                xCreatedAt = "created",
                savedAt = "saved",
                syncedAt = "synced",
            ),
        )
        val assetId = database.clipDao().insertAssets(
            listOf(
                AssetEntity(
                    clipId = clipId,
                    mediaKey = "vision-photo",
                    type = "photo",
                    remoteUrl = "https://remote.invalid/vision",
                    previewUrl = "https://preview.invalid/vision",
                    localPath = imageFile.absolutePath,
                    sizeBytes = imageFile.length(),
                    downloadState = "local",
                    createdAt = "asset-created",
                ),
            ),
        ).single()

        synchronizer.start(scope)
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.COMPLETE &&
                synchronizer.state.value.reembeddedAssetCount == 1
        }

        val stored = requireNotNull(storage.getImageEmbedding(assetId))
        assertEquals(assetId, stored.assetId)
        assertEquals(clipId, stored.clipId)
        assertEquals(ImageEmbeddingBlobCodec.DIMENSION, stored.embedding.size)
        assertTrue(stored.embedding.all(Float::isFinite))
        assertEquals(1f, kotlin.math.sqrt(stored.embedding.sumOf { it.toDouble() * it.toDouble() }).toFloat(), 0.001f)
        assertEquals(1, embedder.visionSessionInitializationCountForTest)

        synchronizer.stop()
        synchronizer = ImageEmbeddingSynchronizer(
            databaseFlow = databaseFlow,
            derivedSearchStorage = storage,
            embedder = embedder,
        )
        synchronizer.start(scope)
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.COMPLETE &&
                synchronizer.state.value.reembeddedAssetCount == 0
        }
        assertEquals(1, embedder.visionSessionInitializationCountForTest)

        database.clipDao().deleteClip(clipId)
        awaitCondition { storage.getAllImageEmbeddings().isEmpty() }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(120_000) {
            while (!condition()) delay(50)
        }
    }

    private fun writeImage(file: File) {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff336699.toInt())
            file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } finally {
            bitmap.recycle()
        }
    }
}
