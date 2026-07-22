package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.lyco256.llm.LikeListManagerApp
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaGridPersistentPreviewIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<LikeListManagerApp>()
    private val context = app.applicationContext
    private val store = MediaGridPersistentPreviewStore(context.filesDir)
    private val sourceRoot = File(context.filesDir, "media_grid_persistent_preview_test")
    private val assetIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        runBlocking {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WorkManagerMediaGridPreviewEnqueuer.UNIQUE_WORK_NAME)
                .result
                .get(30, TimeUnit.SECONDS)
            sourceRoot.deleteRecursively()
            sourceRoot.mkdirs()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            for (assetId in assetIds) store.deletePreview(assetId)
            sourceRoot.deleteRecursively()
        }
    }

    @Test
    fun generatesCenteredOpaqueJpegAndSkipsAValidNewerFile() = runBlocking {
        val source = createSource("horizontal.jpg", 400, 200) { canvas ->
            canvas.drawColor(Color.GREEN)
            canvas.drawRect(0f, 0f, 100f, 200f, paint(Color.RED))
            canvas.drawRect(300f, 0f, 400f, 200f, paint(Color.BLUE))
        }
        val assetId = 71_001L
        assetIds += assetId
        source.setLastModified(1_000L)

        val generated = store.generate(assetId, source) { true }
        assertEquals(MediaGridPreviewGenerationResult.GENERATED, generated)
        val target = store.previewFile(assetId)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, options)
        assertEquals(256, options.outWidth)
        assertEquals(256, options.outHeight)
        assertEquals("image/jpeg", options.outMimeType)
        assertTrue(target.length() > 0L)
        assertTrue(target.name == "$assetId.jpg")
        assertTrue(target.parentFile?.name == "v1")
        val output = BitmapFactory.decodeFile(target.absolutePath)
        assertNotNull(output)
        val center = output!!.getPixel(128, 128)
        assertTrue(kotlin.math.abs(Color.red(center) - Color.red(Color.GREEN)) <= 2)
        assertTrue(kotlin.math.abs(Color.green(center) - Color.green(Color.GREEN)) <= 2)
        assertTrue(kotlin.math.abs(Color.blue(center) - Color.blue(Color.GREEN)) <= 2)
        output.recycle()

        val before = target.readBytes()
        val skipped = store.generate(assetId, source) { true }
        assertEquals(MediaGridPreviewGenerationResult.SKIPPED_VALID, skipped)
        assertArrayEquals(before, target.readBytes())
    }

    @Test
    fun invalidAndOlderOutputsAreReplacedWithoutLeavingTemporaryFiles() = runBlocking {
        val source = createSource("portrait.jpg", 200, 400) { canvas -> canvas.drawColor(Color.MAGENTA) }
        val assetId = 71_002L
        assetIds += assetId
        val target = store.previewFile(assetId)
        target.parentFile!!.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(MediaGridPreviewGenerationResult.GENERATED, store.generate(assetId, source) { true })
        assertTrue(BitmapFactory.decodeFile(target.absolutePath) != null)
        assertTrue(target.parentFile!!.listFiles()!!.none { it.name.startsWith(".$assetId.") })
    }

    @Test
    fun staleAssetDoesNotPublishAndPreservesExistingValidJpeg() = runBlocking {
        val source = createSource("stale.jpg", 300, 300) { canvas -> canvas.drawColor(Color.YELLOW) }
        val assetId = 71_003L
        assetIds += assetId
        source.setLastModified(1_000L)
        assertEquals(MediaGridPreviewGenerationResult.GENERATED, store.generate(assetId, source) { true })
        val target = store.previewFile(assetId)
        source.setLastModified(target.lastModified() + 10_000L)
        val before = target.readBytes()

        assertEquals(
            MediaGridPreviewGenerationResult.SKIPPED_STALE_ASSET,
            store.generate(assetId, source) { false },
        )
        assertArrayEquals(before, target.readBytes())
        assertTrue(target.parentFile!!.listFiles()!!.none { it.name.startsWith(".$assetId.") })
    }

    @Test
    fun workManagerProcessesOneAssetBatchAndUsesOnlyTheTestAppDatabase() = runBlocking {
        val source = createSource("worker.jpg", 512, 300) { canvas -> canvas.drawColor(Color.CYAN) }
        val assetId = insertLocalAsset(source)
        val workManager = WorkManager.getInstance(context)
        val existingWorkIds = workManager
            .getWorkInfosForUniqueWork(WorkManagerMediaGridPreviewEnqueuer.UNIQUE_WORK_NAME)
            .get(30, TimeUnit.SECONDS)
            .mapTo(HashSet()) { it.id }
        WorkManagerMediaGridPreviewEnqueuer(context).enqueue(listOf(assetId))

        val work = withTimeout(60_000L) {
            while (true) {
                val finished = workManager
                    .getWorkInfosForUniqueWork(WorkManagerMediaGridPreviewEnqueuer.UNIQUE_WORK_NAME)
                    .get(30, TimeUnit.SECONDS)
                    .firstOrNull { it.id !in existingWorkIds && it.state.isFinished }
                if (finished != null) return@withTimeout finished
                delay(50L)
            }
            error("unreachable")
        }
        assertEquals(WorkInfo.State.SUCCEEDED, work.state)
        assertTrue(store.previewFile(assetId).isFile)
        assertEquals(context.filesDir.canonicalFile, store.previewFile(assetId).parentFile!!.parentFile!!.parentFile!!.canonicalFile)
    }

    @Test
    fun workerPublishesOnlyGeneratedAssetCompletionNotification() = runBlocking {
        val source = createSource("worker-notification.jpg", 512, 300) { canvas -> canvas.drawColor(Color.CYAN) }
        val assetId = insertLocalAsset(source)
        val notification = async {
            withTimeout(10_000L) {
                MediaGridPreviewNotifier.previewChanged.first { it == assetId }
            }
        }
        val worker = TestListenableWorkerBuilder<MediaGridPreviewWorker>(context)
            .setInputData(
                Data.Builder()
                    .putLongArray(WorkManagerMediaGridPreviewEnqueuer.INPUT_ASSET_IDS, longArrayOf(assetId))
                    .build(),
            )
            .build()

        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertEquals(assetId, notification.await())
    }

    @Test
    fun workerSkipsAnAssetDeletedBeforeExecution() = runBlocking {
        val source = createSource("deleted-before-worker.jpg", 300, 300) { canvas -> canvas.drawColor(Color.WHITE) }
        val assetId = insertLocalAsset(source)
        app.container.postStorageManager.withDatabase { database ->
            val clipId = database.clipDao().getAsset(assetId)!!.clipId
            database.clipDao().deleteClip(clipId)
        }

        val worker = TestListenableWorkerBuilder<MediaGridPreviewWorker>(context)
            .setInputData(
                Data.Builder()
                    .putLongArray(WorkManagerMediaGridPreviewEnqueuer.INPUT_ASSET_IDS, longArrayOf(assetId))
                    .build(),
            )
            .build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertFalse(store.previewFile(assetId).exists())
    }

    private suspend fun insertLocalAsset(source: File): Long {
        val now = Instant.now().toString()
        val clipId = app.container.postStorageManager.withDatabase { database ->
            database.clipDao().insertClip(
                ClipEntity(
                    xPostId = "preview-worker-${System.nanoTime()}",
                    authorName = "worker",
                    authorUsername = "worker",
                    text = "worker test",
                    postUrl = "https://example.test/worker",
                    xCreatedAt = now,
                    savedAt = now,
                    syncedAt = now,
                ),
            )
        }
        return app.container.postStorageManager.withDatabase { database ->
            val id = database.clipDao().insertAssets(
                listOf(
                    AssetEntity(
                        clipId = clipId,
                        mediaKey = "worker-${System.nanoTime()}",
                        type = "photo",
                        remoteUrl = null,
                        previewUrl = null,
                        localPath = source.absolutePath,
                        downloadState = "downloaded",
                        sizeBytes = source.length(),
                        createdAt = now,
                    ),
                ),
            ).single()
            assetIds += id
            id
        }
    }

    private fun createSource(name: String, width: Int, height: Int, draw: (Canvas) -> Unit): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap))
            return File(sourceRoot, name).also { file ->
                file.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun paint(color: Int) = android.graphics.Paint().apply { this.color = color }
}
