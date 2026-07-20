package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lyco256.llm.BuildConfig
import com.lyco256.llm.LikeListManagerApp
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExistingMediaGridPreviewBackfillIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<LikeListManagerApp>()
    private val context = app.applicationContext
    private val storage = app.container.postStorageManager
    private val store = MediaGridPersistentPreviewStore(context.filesDir)
    private val sourceRoot = File(context.filesDir, "existing_preview_backfill_test")
    private val insertedClipIds = mutableListOf<Long>()

    @Before
    fun setUp() = runBlocking {
        assertTrue(BuildConfig.TEST_HARNESS)
        assertEquals("com.lyco256.llm.test", BuildConfig.APPLICATION_ID)
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WorkManagerMediaGridPreviewEnqueuer.UNIQUE_WORK_NAME).result.get(30, TimeUnit.SECONDS)
        workManager.cancelUniqueWork(WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_UNIQUE_WORK_NAME).result.get(30, TimeUnit.SECONDS)
        sourceRoot.deleteRecursively()
        sourceRoot.mkdirs()
    }

    @After
    fun tearDown() = runBlocking {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_UNIQUE_WORK_NAME)
            .result
            .get(30, TimeUnit.SECONDS)
        insertedClipIds.forEach { clipId ->
            storage.withDatabase { database -> database.clipDao().deleteClip(clipId) }
        }
        sourceRoot.deleteRecursively()
    }

    @Test
    fun scansExistingLocalAssetsAndExcludesValidPreview() = runBlocking {
        val validSource = createSource("valid.jpg", Color.GREEN)
        val staleSource = createSource("stale.jpg", Color.RED)
        val invalidSource = createSource("invalid.jpg", Color.BLUE)
        val missingSource = File(sourceRoot, "missing.jpg")
        val validAsset = insertLocalAsset(validSource)
        val staleAsset = insertLocalAsset(staleSource)
        val invalidAsset = insertLocalAsset(invalidSource)
        insertLocalAsset(missingSource)
        insertRemoteAsset()
        store.generate(validAsset, validSource) { true }
        store.generate(staleAsset, staleSource) { true }
        staleSource.setLastModified(store.previewFile(staleAsset).lastModified() + 10_000L)
        store.previewFile(invalidAsset).parentFile!!.mkdirs()
        store.previewFile(invalidAsset).writeBytes(byteArrayOf(1, 2, 3))

        val scan = app.container.existingMediaGridPreviewBackfill.scan()

        assertEquals(4, scan.localAssetCount)
        assertEquals(1, scan.validPreviewCount)
        assertEquals(listOf(staleAsset, invalidAsset), scan.targetAssetIds)
    }

    @Test
    fun startResumeAndNewControllerRestoreTaggedWorkState() = runBlocking {
        val firstSource = createSource("first.jpg", Color.CYAN)
        val secondSource = createSource("second.jpg", Color.MAGENTA)
        val first = insertLocalAsset(firstSource)
        val second = insertLocalAsset(secondSource)
        val backfill = app.container.existingMediaGridPreviewBackfill

        backfill.enqueue(listOf(second, first, second))
        val recreated = ExistingMediaGridPreviewBackfill(
            postStorageManager = storage,
            previewStore = store,
            previewEnqueuer = WorkManagerMediaGridPreviewEnqueuer(context),
        )
        val restoredStatus = withTimeout(10_000L) {
            recreated.workStatus.first { it.totalCount > 0 }
        }
        assertTrue(restoredStatus.totalCount > 0)
        awaitTargetWorkFinished(listOf(first, second))
        assertTrue(store.previewFile(first).isFile)
        assertTrue(store.previewFile(second).isFile)
        assertEquals(0, recreated.scan().targetCount)
    }

    @Test
    fun stopCancelsOnlyTaggedBackfillAndResumeEnqueuesRemainingAssets() = runBlocking {
        val normalSource = createSource("normal.jpg", Color.YELLOW)
        val backfillSource = createSource("backfill.jpg", Color.WHITE)
        val normalAsset = insertLocalAsset(normalSource)
        val backfillAsset = insertLocalAsset(backfillSource)
        val workManager = WorkManager.getInstance(context)
        val backfill = app.container.existingMediaGridPreviewBackfill

        WorkManagerMediaGridPreviewEnqueuer(context).enqueue(listOf(normalAsset))
        backfill.enqueue(listOf(backfillAsset))
        backfill.cancel()

        val normalInfos = workManager.getWorkInfosForUniqueWork(WorkManagerMediaGridPreviewEnqueuer.UNIQUE_WORK_NAME)
            .get(30, TimeUnit.SECONDS)
        assertTrue(normalInfos.isNotEmpty())
        assertTrue(normalInfos.none { it.state == WorkInfo.State.CANCELLED })
        assertFalse(store.previewFile(backfillAsset).isFile)

        backfill.enqueue(backfill.scan().targetAssetIds)
        awaitTargetWorkFinished(listOf(backfillAsset))
        assertTrue(store.previewFile(backfillAsset).isFile)
    }

    private suspend fun awaitTargetWorkFinished(assetIds: List<Long>) {
        withTimeout(60_000L) {
            while (true) {
                val infos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_UNIQUE_WORK_NAME)
                    .get(30, TimeUnit.SECONDS)
                if (infos.isNotEmpty() && infos.all { it.state.isFinished }) return@withTimeout
                delay(100L)
            }
        }
    }

    private suspend fun insertLocalAsset(source: File): Long {
        val clipId = insertClip("local-${System.nanoTime()}")
        return storage.withDatabase { database ->
            database.clipDao().insertAssets(
                listOf(
                    AssetEntity(
                        clipId = clipId,
                        mediaKey = "asset-${System.nanoTime()}",
                        type = "photo",
                        remoteUrl = null,
                        previewUrl = null,
                        localPath = source.absolutePath,
                        downloadState = "downloaded",
                        sizeBytes = source.length(),
                        createdAt = "2026-07-20T00:00:00Z",
                    ),
                ),
            ).single()
        }
    }

    private suspend fun insertRemoteAsset() {
        val clipId = insertClip("remote-${System.nanoTime()}")
        storage.withDatabase { database ->
            database.clipDao().insertAssets(
                listOf(
                    AssetEntity(
                        clipId = clipId,
                        mediaKey = "remote-${System.nanoTime()}",
                        type = "photo",
                        remoteUrl = "https://example.test/remote.jpg",
                        previewUrl = null,
                        localPath = null,
                        createdAt = "2026-07-20T00:00:00Z",
                    ),
                ),
            )
        }
    }

    private suspend fun insertClip(postId: String): Long {
        val clipId = storage.withDatabase { database ->
            database.clipDao().insertClip(
                ClipEntity(
                    xPostId = postId,
                    authorName = "test",
                    authorUsername = "test",
                    text = "test",
                    postUrl = "https://example.test/$postId",
                    xCreatedAt = "2026-07-20T00:00:00Z",
                    savedAt = "2026-07-20T00:00:00Z",
                    syncedAt = "2026-07-20T00:00:00Z",
                ),
            )
        }
        insertedClipIds += clipId
        return clipId
    }

    private fun createSource(name: String, color: Int): File {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).drawColor(color)
            File(sourceRoot, name).also { output ->
                output.outputStream().use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
