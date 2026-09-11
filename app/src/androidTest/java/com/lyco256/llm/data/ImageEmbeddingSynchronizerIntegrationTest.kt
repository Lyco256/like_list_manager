package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageEmbeddingSynchronizerIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: LikeListDatabase
    private lateinit var databaseFlow: MutableStateFlow<LikeListDatabase?>
    private lateinit var storage: DerivedSearchStorage
    private lateinit var decoder: RecordingDecoder
    private lateinit var embedder: RecordingImageEmbedder
    private lateinit var synchronizer: ImageEmbeddingSynchronizer
    private lateinit var scope: CoroutineScope
    private lateinit var imageDirectory: File
    private var replacementDatabase: LikeListDatabase? = null

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        imageDirectory = File(context.cacheDir, "image-embedding-sync-test")
        imageDirectory.deleteRecursively()
        imageDirectory.mkdirs()
        File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME).deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, LikeListDatabase::class.java).build()
        databaseFlow = MutableStateFlow(database)
        storage = DerivedSearchStorage(context)
        decoder = RecordingDecoder()
        embedder = RecordingImageEmbedder()
        synchronizer = newSynchronizer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        runBlocking {
            synchronizer.stop()
            storage.close()
        }
        scope.cancel()
        replacementDatabase?.close()
        database.close()
        imageDirectory.deleteRecursively()
        File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME).deleteRecursively()
    }

    @Test
    fun initialBuildTargetsOnlyLocalPhotoAndVideoThumbnailAssets() = runBlocking {
        val clipId = insertClip("initial")
        val photoPath = writeFile("photo.bin", "photo")
        val thumbnailPath = writeFile("thumbnail.bin", "thumbnail")
        val ignoredPath = writeFile("ignored.bin", "ignored")
        val photoId = insertAsset(clipId, "photo", photoPath)
        val thumbnailId = insertAsset(clipId, "video_thumbnail", thumbnailPath)
        insertAsset(clipId, "video", ignoredPath)
        insertAsset(clipId, "photo", null, remoteUrl = "https://remote.invalid/only.jpg")

        synchronizer.start(scope)
        awaitComplete(targetCount = 3, processedCount = 3, reembeddedCount = 2)

        assertEquals(setOf(photoId, thumbnailId), storage.getAllImageEmbeddingFingerprints().keys)
        assertEquals(setOf(photoPath.absolutePath, thumbnailPath.absolutePath), decoder.paths.toSet())
        assertTrue(decoder.bitmaps.all(Bitmap::isRecycled))
        assertTrue(embedder.calls == 2)
    }

    @Test
    fun unchangedAndPathOnlyChangesReuseTheStoredEmbeddingButFileChangesReembed() = runBlocking {
        val clipId = insertClip("reuse")
        val originalPath = writeFile("reuse.bin", "stable-content")
        val assetId = insertAsset(clipId, "photo", originalPath)
        val originalSignature = requireNotNull(ImageEmbeddingFingerprint.sourceSignature(originalPath))
        synchronizer.start(scope)
        awaitComplete(targetCount = 1, processedCount = 1, reembeddedCount = 1)
        val initialFingerprint = storage.getAllImageEmbeddingFingerprints().getValue(assetId)
        synchronizer.stop()

        decoder.clear()
        embedder.clear()
        synchronizer = newSynchronizer()
        synchronizer.start(scope)
        awaitComplete(targetCount = 1, processedCount = 1, reembeddedCount = 0)
        assertEquals(0, embedder.calls)

        val movedPath = File(imageDirectory, "moved.bin")
        check(originalPath.renameTo(movedPath))
        val movedSignature = requireNotNull(ImageEmbeddingFingerprint.sourceSignature(movedPath))
        assertEquals(originalSignature, movedSignature)
        val movedAsset = requireNotNull(database.clipDao().getAsset(assetId)).copy(localPath = movedPath.absolutePath)
        assertEquals(initialFingerprint, ImageEmbeddingFingerprint.calculate(movedAsset, movedSignature))
        database.clipDao().updateAsset(
            movedAsset,
        )
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.COMPLETE &&
                embedder.calls == 0 &&
                storage.getAllImageEmbeddingFingerprints()[assetId] == initialFingerprint
        }

        movedPath.appendText("changed")
        check(movedPath.setLastModified(originalPath.lastModified() + 1_000L))
        database.clipDao().updateAsset(
            requireNotNull(database.clipDao().getAsset(assetId)).copy(downloadState = "changed"),
        )
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.COMPLETE &&
                embedder.calls == 1 &&
                storage.getAllImageEmbeddingFingerprints()[assetId] != initialFingerprint
        }
        assertTrue(storage.getAllImageEmbeddingFingerprints().getValue(assetId) != initialFingerprint)
    }

    @Test
    fun deletionUndoRestorationAndUnavailableDatabaseFollowThePrimarySnapshot() = runBlocking {
        val clipId = insertClip("restore")
        val path = writeFile("restore.bin", "restore")
        val assetId = insertAsset(clipId, "photo", path)
        synchronizer.start(scope)
        awaitComplete(targetCount = 1, processedCount = 1, reembeddedCount = 1)
        val before = storage.getAllImageEmbeddings()

        databaseFlow.value = null
        delay(100)
        val whileUnavailable = storage.getAllImageEmbeddings()
        assertEquals(before.map(ImageEmbeddingDocument::assetId), whileUnavailable.map(ImageEmbeddingDocument::assetId))
        assertEquals(
            before.map { it.sourceFingerprint to it.embedding.toList() },
            whileUnavailable.map { it.sourceFingerprint to it.embedding.toList() },
        )

        databaseFlow.value = database
        awaitComplete(targetCount = 1, processedCount = 1, reembeddedCount = 0)
        assertEquals(1, embedder.calls)

        database.clipDao().deleteClip(clipId)
        awaitCondition { storage.getAllImageEmbeddings().isEmpty() }

        database.clipDao().insertClip(clip(clipId, "restored"))
        database.clipDao().insertAssets(
            listOf(asset(assetId, clipId, "photo", path)),
        )
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.COMPLETE &&
                storage.getImageEmbedding(assetId) != null
        }
        assertEquals(2, embedder.calls)
    }

    @Test
    fun databaseSwitchReconcilesAfterNullWithoutDroppingThePreviousDerivedRowsEarly() = runBlocking {
        val originalClipId = insertClip("switch-original")
        val originalPath = writeFile("switch-original.bin", "original")
        val originalAssetId = insertAsset(originalClipId, "photo", originalPath)
        synchronizer.start(scope)
        awaitComplete(targetCount = 1, processedCount = 1, reembeddedCount = 1)
        val original = requireNotNull(storage.getImageEmbedding(originalAssetId))

        val replacement = Room.inMemoryDatabaseBuilder(context, LikeListDatabase::class.java).build()
        replacementDatabase = replacement
        val replacementClipId = 77L
        val replacementAssetId = 91L
        val replacementPath = writeFile("switch-replacement.bin", "replacement")
        replacement.clipDao().insertClip(clip(replacementClipId, "switch-replacement"))
        replacement.clipDao().insertAssets(
            listOf(asset(replacementAssetId, replacementClipId, "photo", replacementPath)),
        )

        databaseFlow.value = null
        delay(100)
        assertEquals(original.sourceFingerprint, storage.getAllImageEmbeddingFingerprints().getValue(originalAssetId))

        databaseFlow.value = replacement
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.COMPLETE &&
                synchronizer.state.value.targetAssetCount == 1 &&
                storage.getImageEmbedding(originalAssetId) == null &&
                storage.getImageEmbedding(replacementAssetId) != null
        }
        assertEquals(2, embedder.calls)
    }

    @Test
    fun stoppingDuringInitialInferenceKeepsCommittedRowsAndReusesThemAfterRestart() = runBlocking {
        val clipId = insertClip("interrupted")
        val firstPath = writeFile("interrupted-first.bin", "first")
        val secondPath = writeFile("interrupted-second.bin", "second")
        val firstId = insertAsset(clipId, "photo", firstPath)
        val secondId = insertAsset(clipId, "photo", secondPath)
        embedder.blockOnCall = 2
        embedder.blockGate = CompletableDeferred()

        synchronizer.start(scope)
        awaitCondition {
            embedder.calls == 2 &&
                storage.getImageEmbedding(firstId) != null &&
                storage.getImageEmbedding(secondId) == null
        }
        synchronizer.stop()
        assertEquals(2, embedder.calls)
        assertTrue(storage.getImageEmbedding(firstId) != null)
        assertTrue(storage.getImageEmbedding(secondId) == null)

        embedder.blockOnCall = null
        embedder.blockGate = null
        synchronizer = newSynchronizer()
        synchronizer.start(scope)
        awaitComplete(targetCount = 2, processedCount = 2, reembeddedCount = 1)
        assertEquals(3, embedder.calls)
        assertTrue(storage.getImageEmbedding(firstId) != null)
        assertTrue(storage.getImageEmbedding(secondId) != null)
    }

    @Test
    fun decodeFailureKeepsTheOldRowAndCanBeRetriedLater() = runBlocking {
        val clipId = insertClip("decode")
        val path = writeFile("decode.bin", "decode")
        val assetId = insertAsset(clipId, "photo", path)
        synchronizer.start(scope)
        awaitComplete(targetCount = 1, processedCount = 1, reembeddedCount = 1)
        val old = requireNotNull(storage.getImageEmbedding(assetId))
        path.appendText("changed")
        decoder.failPath = path.absolutePath

        database.clipDao().updateAsset(requireNotNull(database.clipDao().getAsset(assetId)).copy(downloadState = "retry"))
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.FAILED &&
                assetId in synchronizer.state.value.failedAssetIds
        }
        assertEquals(old.sourceFingerprint, storage.getAllImageEmbeddingFingerprints().getValue(assetId))
        assertEquals(old.embedding.toList(), requireNotNull(storage.getImageEmbedding(assetId)).embedding.toList())

        decoder.failPath = null
        database.clipDao().updateAsset(requireNotNull(database.clipDao().getAsset(assetId)).copy(downloadState = "retry-again"))
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.COMPLETE &&
                synchronizer.state.value.reembeddedAssetCount == 1
        }
        assertTrue(storage.getAllImageEmbeddingFingerprints().getValue(assetId) != old.sourceFingerprint)
    }

    @Test
    fun inferenceFailureStopsTheCurrentReconcileWithoutTouchingUnprocessedRows() = runBlocking {
        val clipId = insertClip("inference")
        val firstPath = writeFile("first.bin", "first")
        val secondPath = writeFile("second.bin", "second")
        val firstId = insertAsset(clipId, "photo", firstPath)
        val secondId = insertAsset(clipId, "photo", secondPath)
        synchronizer.start(scope)
        awaitComplete(targetCount = 2, processedCount = 2, reembeddedCount = 2)
        val oldFirst = requireNotNull(storage.getImageEmbedding(firstId)).sourceFingerprint
        val oldSecond = requireNotNull(storage.getImageEmbedding(secondId)).sourceFingerprint
        decoder.paths.clear()
        decoder.bitmaps.clear()

        firstPath.appendText("changed")
        secondPath.appendText("changed")
        embedder.failOnCall = embedder.calls + 1
        database.withTransaction {
            database.clipDao().updateAsset(requireNotNull(database.clipDao().getAsset(firstId)).copy(downloadState = "changed"))
            database.clipDao().updateAsset(requireNotNull(database.clipDao().getAsset(secondId)).copy(downloadState = "changed"))
        }

        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.FAILED &&
                firstId in synchronizer.state.value.failedAssetIds
        }
        assertEquals(3, embedder.calls)
        assertEquals(oldFirst, storage.getAllImageEmbeddingFingerprints().getValue(firstId))
        assertEquals(oldSecond, storage.getAllImageEmbeddingFingerprints().getValue(secondId))
        assertEquals(listOf(firstPath.absolutePath), decoder.paths.toList())
    }

    private fun newSynchronizer() = ImageEmbeddingSynchronizer(
        databaseFlow = databaseFlow,
        derivedSearchStorage = storage,
        embedder = embedder,
        decoder = decoder,
    )

    private suspend fun awaitComplete(targetCount: Int, processedCount: Int, reembeddedCount: Int) {
        awaitCondition {
            synchronizer.state.value.status == ImageEmbeddingSyncStatus.COMPLETE &&
                synchronizer.state.value.targetAssetCount == targetCount &&
                synchronizer.state.value.processedAssetCount == processedCount &&
                synchronizer.state.value.reembeddedAssetCount == reembeddedCount
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeoutOrNull(30_000) {
            while (!condition()) delay(20)
        } ?: throw AssertionError(
            "Timed out: state=${synchronizer.state.value}, calls=${embedder.calls}, " +
                "paths=${decoder.paths.toList()}, fingerprints=${storage.getAllImageEmbeddingFingerprints()}",
        )
    }

    private suspend fun insertClip(name: String): Long = database.clipDao().insertClip(clip(0L, name))

    private suspend fun insertAsset(
        clipId: Long,
        type: String,
        path: File?,
        remoteUrl: String? = "https://remote.invalid/$type",
    ): Long = database.clipDao().insertAssets(
        listOf(asset(0L, clipId, type, path, remoteUrl)),
    ).single()

    private fun clip(id: Long, name: String) = ClipEntity(
        id = id,
        xPostId = "post-$name-${System.nanoTime()}",
        authorName = "$name display",
        authorUsername = "$name user",
        text = "$name text",
        postUrl = "https://example.invalid/$name",
        xCreatedAt = "created-$name",
        savedAt = "saved-$name",
        syncedAt = "synced-$name",
    )

    private fun asset(
        id: Long,
        clipId: Long,
        type: String,
        path: File?,
        remoteUrl: String? = "https://remote.invalid/$type",
    ) = AssetEntity(
        id = id,
        clipId = clipId,
        mediaKey = "$type-$clipId-$id",
        type = type,
        remoteUrl = remoteUrl,
        previewUrl = "https://preview.invalid/$type",
        localPath = path?.absolutePath,
        sizeBytes = path?.length(),
        downloadState = "local",
        createdAt = "created-asset-$clipId-$id",
    )

    private fun writeFile(name: String, text: String): File = File(imageDirectory, name).apply {
        writeText(text)
        check(setLastModified(1_700_000_000_000L))
    }

    private class RecordingDecoder : ImageEmbeddingBitmapDecoder {
        val paths = CopyOnWriteArrayList<String>()
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        var failPath: String? = null

        override fun decode(file: File): Bitmap {
            paths += file.absolutePath
            check(failPath != file.absolutePath) { "synthetic decode failure" }
            return Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).also(bitmaps::add)
        }

        fun clear() {
            paths.clear()
            bitmaps.clear()
        }
    }

    private class RecordingImageEmbedder : ImageEmbedder {
        @Volatile
        var calls: Int = 0
        @Volatile
        var failOnCall: Int? = null
        @Volatile
        var blockOnCall: Int? = null
        @Volatile
        var blockGate: CompletableDeferred<Unit>? = null
        override suspend fun embedImage(bitmap: Bitmap): MultimodalEmbedding {
            calls += 1
            if (blockOnCall == calls) blockGate?.await()
            check(failOnCall != calls) { "synthetic inference failure" }
            return MultimodalEmbedding.fromModelOutput(FloatArray(ImageEmbeddingBlobCodec.DIMENSION) { index ->
                1f + index / 10_000f
            })
        }

        fun clear() {
            calls = 0
            failOnCall = null
        }
    }
}
