package com.lyco256.llm.data

import android.content.Context
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
class SemanticIndexEmbeddingIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: LikeListDatabase
    private lateinit var databaseFlow: MutableStateFlow<LikeListDatabase?>
    private lateinit var storage: DerivedSearchStorage
    private lateinit var embedder: LocalTextEmbedder
    private lateinit var synchronizer: SemanticIndexSynchronizer
    private lateinit var scope: CoroutineScope
    private val derivedDirectory: File
        get() = File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME)

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        derivedDirectory.deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, LikeListDatabase::class.java).build()
        databaseFlow = MutableStateFlow(database)
        storage = DerivedSearchStorage(context)
        embedder = LocalTextEmbedder(context)
        synchronizer = SemanticIndexSynchronizer(databaseFlow, storage, embedder)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        runBlocking {
            synchronizer.stop()
            embedder.close()
            storage.close()
        }
        scope.cancel()
        database.close()
        derivedDirectory.deleteRecursively()
    }

    @Test
    fun realEmbeddingGemmaSynchronizesChunksUpdatesAndDeletesFromRoomText() = runBlocking {
        val longOcr = buildString {
            repeat(700) { index ->
                append(if (index % 3 == 0) "😀" else "OCR長文")
            }
        }
        val clipId = database.clipDao().insertClip(
            ClipEntity(
                xPostId = "semantic-e2e-post",
                authorName = "表示名",
                authorUsername = "username",
                text = "本文のdocument embedding",
                postUrl = "https://example.test/semantic-e2e",
                xCreatedAt = "created",
                savedAt = "saved",
                syncedAt = "synced",
                summary = "概要のdocument embedding",
                ocrText = longOcr,
            ),
        )
        val primaryBeforeUpdate = requireNotNull(database.clipDao().getClip(clipId))

        synchronizer.start(scope)
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                synchronizer.state.value.targetSourceCount == 3 &&
                synchronizer.state.value.processedSourceCount == 3 &&
                synchronizer.state.value.reembeddedSourceCount == 3
        }

        val initialDocuments = storage.getSemanticDocuments(clipId)
        assertTrue(initialDocuments.any { it.sourceType == SemanticSourceType.TEXT })
        assertTrue(initialDocuments.any { it.sourceType == SemanticSourceType.SUMMARY })
        assertTrue(initialDocuments.count { it.sourceType == SemanticSourceType.OCR } > 1)
        assertTrue(initialDocuments.all { document ->
            document.embedding.size == EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION &&
                document.embedding.all { value -> value.isFinite() }
        })

        database.clipDao().updateOcrText(clipId, "更新後のOCR全文", "ocr-updated")
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                synchronizer.state.value.reembeddedSourceCount == 1 &&
                storage.getAllSemanticSourceFingerprints()[SemanticSourceKey(clipId, SemanticSourceType.OCR)] ==
                    SemanticTextChunker.fingerprint(
                        SemanticTextSource(SemanticSourceType.OCR, "更新後のOCR全文"),
                    )
        }
        assertEquals(1, storage.getSemanticDocuments(clipId).count { it.sourceType == SemanticSourceType.OCR })
        assertEquals(primaryBeforeUpdate.text, database.clipDao().getClip(clipId)?.text)

        val restoredClip = requireNotNull(database.clipDao().getClip(clipId)).copy(
            xPostId = "semantic-e2e-restored",
        )
        database.clipDao().deleteClip(clipId)
        awaitCondition { storage.getAllSemanticDocuments().isEmpty() }

        assertEquals(clipId, database.clipDao().insertClip(restoredClip))
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                storage.getSemanticDocuments(clipId).isNotEmpty()
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(180_000) {
            while (!condition()) delay(50)
        }
    }
}
