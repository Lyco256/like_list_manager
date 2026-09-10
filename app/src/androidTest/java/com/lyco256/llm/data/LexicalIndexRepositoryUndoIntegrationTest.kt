package com.lyco256.llm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LexicalIndexRepositoryUndoIntegrationTest {
    private lateinit var context: Context
    private lateinit var storage: PostStorageManager
    private lateinit var repository: ClipRepository
    private lateinit var derivedSearchStorage: DerivedSearchStorage
    private lateinit var synchronizer: LexicalIndexSynchronizer
    private lateinit var scope: CoroutineScope

    private val storageConfig = PostStorageConfig(
        databaseName = "lexical_sync_undo_test.db",
        imagesDirectory = "lexical_sync_undo_images",
        dataDirectory = "lexical_sync_undo_data",
        preferencesName = "lexical_sync_undo_preferences",
    )

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        cleanup()
        storage = PostStorageManager(context, storageConfig)
        repository = ClipRepository(
            context = context,
            postStorageManager = storage,
            apiSettingsStore = InMemorySettingsStore(),
            xOAuthManager = DisabledOAuthGateway(),
            xApiClient = DisabledXApiGateway(),
            ocrTextGateway = FakeOcrTextGateway(),
            includeSeedMedia = false,
        )
        derivedSearchStorage = DerivedSearchStorage(context)
        synchronizer = LexicalIndexSynchronizer(
            databaseFlow = storage.database,
            derivedSearchStorage = derivedSearchStorage,
            analyzer = LexicalTextAnalyzer { text ->
                SudachiLexicalTextAnalysis(text, text, text, text)
            },
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        runBlocking {
            synchronizer.stop()
            derivedSearchStorage.close()
        }
        scope.cancel()
        storage.database.value?.close()
        cleanup()
    }

    @Test
    fun summaryOcrAndClipDeleteUndoAreReindexedThroughTheRoomObserver() = runBlocking {
        val clipId = storage.withDatabase { database ->
            database.clipDao().insertClip(
                ClipEntity(
                    xPostId = "undo-post",
                    authorName = "Undo display",
                    authorUsername = "undo-user",
                    text = "undo body",
                    postUrl = "https://example.test/undo",
                    xCreatedAt = "created",
                    savedAt = "saved",
                    syncedAt = "synced",
                    summary = "before summary",
                    ocrText = "before OCR",
                ),
            )
        }
        synchronizer.start(scope)
        awaitFingerprint(clipId)

        var clip = requireNotNull(storage.withDatabase { it.clipDao().getClip(clipId) })
        repository.updateSummary(clip, "after summary")
        awaitFingerprint(clipId)
        assertTrue(derivedSearchStorage.searchNormal("after summary").any { it.clipId == clipId })

        val summaryUndo = requireNotNull(repository.pendingUndo.first { it?.actionType == UndoActionType.SUMMARY_EDITED.storageValue })
        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit(summaryUndo))
        awaitFingerprint(clipId)
        assertTrue(derivedSearchStorage.searchNormal("before summary").any { it.clipId == clipId })

        clip = requireNotNull(storage.withDatabase { it.clipDao().getClip(clipId) })
        repository.updateOcrText(clip, "after OCR")
        awaitFingerprint(clipId)
        assertTrue(derivedSearchStorage.searchNormal("after OCR").any { it.clipId == clipId })

        val ocrUndo = requireNotNull(repository.pendingUndo.first { it?.actionType == UndoActionType.OCR_EDITED.storageValue })
        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit(ocrUndo))
        awaitFingerprint(clipId)
        assertTrue(derivedSearchStorage.searchNormal("before OCR").any { it.clipId == clipId })

        clip = requireNotNull(storage.withDatabase { it.clipDao().getClip(clipId) })
        repository.moveClipToTrash(clip)
        awaitCondition { clipId !in derivedSearchStorage.getAllClipFingerprints() }
        val deleteUndo = requireNotNull(repository.pendingUndo.first { it?.actionType == UndoActionType.CLIP_DELETED.storageValue })
        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit(deleteUndo))
        awaitFingerprint(clipId)
        assertTrue(derivedSearchStorage.searchNormal("undo body").any { it.clipId == clipId })
    }

    private suspend fun awaitFingerprint(clipId: Long) {
        val clip = requireNotNull(storage.withDatabase { it.clipDao().getClip(clipId) })
        val expected = LexicalDocumentBuilder.fingerprint(clip)
        awaitCondition { derivedSearchStorage.getAllClipFingerprints()[clipId] == expected }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(20)
        }
    }

    private fun cleanup() {
        context.deleteDatabase(storageConfig.databaseName)
        File(context.filesDir, storageConfig.imagesDirectory).deleteRecursively()
        File(context.filesDir, storageConfig.dataDirectory).deleteRecursively()
        context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, DurableClipDeleteUndoStore.DIRECTORY_NAME).deleteRecursively()
        File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME).deleteRecursively()
    }
}

