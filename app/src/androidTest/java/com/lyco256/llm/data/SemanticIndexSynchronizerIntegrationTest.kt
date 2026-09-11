package com.lyco256.llm.data

import android.content.Context
import androidx.room.Room
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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticIndexSynchronizerIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: LikeListDatabase
    private lateinit var databaseFlow: MutableStateFlow<LikeListDatabase?>
    private lateinit var derivedSearchStorage: DerivedSearchStorage
    private lateinit var embedder: RecordingEmbedder
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
        derivedSearchStorage = DerivedSearchStorage(context)
        embedder = RecordingEmbedder()
        synchronizer = newSynchronizer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        runBlocking {
            synchronizer.stop()
            derivedSearchStorage.close()
        }
        scope.cancel()
        database.close()
        derivedDirectory.deleteRecursively()
    }

    @Test
    fun initialBuildUpdatesAndBlankDeletionOnlyReembedChangedSources() = runBlocking {
        val longOcr = buildString { repeat(800) { append(if (it % 2 == 0) "😀" else "OCR") } }
        val clipId = insertClip("first", summary = "first summary", ocrText = longOcr)
        synchronizer.start(scope)
        awaitComplete(sourceCount = 3, reembeddedCount = 3)

        val initialDocuments = derivedSearchStorage.getSemanticDocuments(clipId)
        assertEquals(
            setOf(SemanticSourceType.TEXT, SemanticSourceType.SUMMARY, SemanticSourceType.OCR),
            initialDocuments.map { it.sourceType }.toSet(),
        )
        assertEquals(
            SemanticTextChunker.chunk(longOcr).size,
            initialDocuments.count { it.sourceType == SemanticSourceType.OCR },
        )
        assertTrue(initialDocuments.all { it.embedding.size == EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION })
        embedder.clear()

        database.clipDao().updateLikeCount(clipId, 99L, "like-count-only")
        awaitComplete(sourceCount = 3, reembeddedCount = 0)
        assertTrue(embedder.inputs.isEmpty())

        database.clipDao().updateOcrText(clipId, "updated OCR", "updated")
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                synchronizer.state.value.reembeddedSourceCount == 1 &&
                SemanticSourceKey(clipId, SemanticSourceType.OCR) in derivedSearchStorage.getAllSemanticSourceFingerprints()
        }
        assertEquals(listOf("updated OCR"), embedder.inputs.toList())
        embedder.clear()

        database.clipDao().updateSummary(clipId, " ")
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                SemanticSourceKey(clipId, SemanticSourceType.SUMMARY) !in derivedSearchStorage.getAllSemanticSourceFingerprints()
        }
        assertTrue(embedder.inputs.isEmpty())
        assertTrue(derivedSearchStorage.getSemanticDocuments(clipId).none { it.sourceType == SemanticSourceType.SUMMARY })
    }

    @Test
    fun clipDeletionAndRestorationFollowThePrimarySnapshot() = runBlocking {
        val clipId = insertClip("restore", summary = "restore summary", ocrText = "restore OCR")
        synchronizer.start(scope)
        awaitComplete(sourceCount = 3, reembeddedCount = 3)

        database.clipDao().deleteClip(clipId)
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                derivedSearchStorage.getAllSemanticDocuments().isEmpty() &&
                derivedSearchStorage.getAllSemanticSourceFingerprints().isEmpty()
        }

        database.clipDao().insertClip(
            ClipEntity(
                id = clipId,
                xPostId = "post-restore-again",
                authorName = "restore display",
                authorUsername = "restore user",
                text = "restored text",
                postUrl = "https://example.test/restore-again",
                xCreatedAt = "created-restore-again",
                savedAt = "saved-restore-again",
                syncedAt = "synced-restore-again",
                summary = "restored summary",
                ocrText = "restored OCR",
            ),
        )
        awaitComplete(sourceCount = 3, reembeddedCount = 3)
        assertEquals(3, derivedSearchStorage.getSemanticDocuments(clipId).size)
    }

    @Test
    fun oneChunkFailureKeepsOldSourceAndContinuesOtherSourcesAndClips() = runBlocking {
        val failedId = insertClip("failed", summary = "stable summary")
        val goodId = insertClip("good", summary = "good summary")
        synchronizer.start(scope)
        awaitComplete(sourceCount = 6, reembeddedCount = 4)
        val oldTextEmbedding = derivedSearchStorage.getSemanticDocuments(failedId)
            .first { it.sourceType == SemanticSourceType.TEXT }
            .embedding
        embedder.clear()
        embedder.failOnInput = "new failed text"

        database.clipDao().updateClip(
            requireNotNull(database.clipDao().getClip(failedId)).copy(text = "new failed text"),
        )
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.FAILED &&
                SemanticSourceKey(failedId, SemanticSourceType.TEXT) in synchronizer.state.value.failedSources
        }

        assertTrue(database.clipDao().getClip(failedId) != null)
        assertTrue(database.clipDao().getClip(goodId) != null)
        assertEquals(
            oldTextEmbedding.toList(),
            derivedSearchStorage.getSemanticDocuments(failedId)
                .first { it.sourceType == SemanticSourceType.TEXT }
                .embedding
                .toList(),
        )
        assertTrue(derivedSearchStorage.getAllSemanticSourceFingerprints().keys.any { it.clipId == goodId })

        embedder.failOnInput = null
        database.clipDao().updateSummary(failedId, "retry summary")
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                SemanticSourceKey(failedId, SemanticSourceType.TEXT) in derivedSearchStorage.getAllSemanticSourceFingerprints()
        }
    }

    @Test
    fun stoppingAndRestartingDoesNotReembedAlreadyCommittedSources() = runBlocking {
        val committedId = insertClip("committed")
        val pendingId = insertClip("pending")
        embedder.blockOnInput = "pending text"
        synchronizer.start(scope)

        awaitFingerprint(committedId)
        embedder.blockedStarted.await()
        synchronizer.stop()

        assertTrue(SemanticSourceKey(committedId, SemanticSourceType.TEXT) in derivedSearchStorage.getAllSemanticSourceFingerprints())
        assertFalse(SemanticSourceKey(pendingId, SemanticSourceType.TEXT) in derivedSearchStorage.getAllSemanticSourceFingerprints())

        embedder.blockOnInput = null
        embedder.clear()
        synchronizer = newSynchronizer()
        synchronizer.start(scope)
        awaitComplete(sourceCount = 6, reembeddedCount = 1)
        assertTrue(embedder.inputs.none { it == "committed text" })
        assertTrue(embedder.inputs.contains("pending text"))
    }

    @Test
    fun unavailableDatabaseDoesNotDeleteSemanticDataAndLatestSnapshotWins() = runBlocking {
        val clipId = insertClip("stable", text = "stable original", summary = "stable summary")
        synchronizer.start(scope)
        awaitComplete(sourceCount = 3, reembeddedCount = 2)
        val before = derivedSearchStorage.getAllSemanticSourceFingerprints()
        embedder.clear()

        databaseFlow.value = null
        delay(100)
        assertEquals(before, derivedSearchStorage.getAllSemanticSourceFingerprints())

        databaseFlow.value = database
        awaitComplete(sourceCount = 3, reembeddedCount = 0)
        assertTrue(embedder.inputs.isEmpty())

        embedder.blockOnInput = "stable text"
        database.clipDao().updateClip(requireNotNull(database.clipDao().getClip(clipId)).copy(text = "stable text"))
        embedder.blockedStarted.await()
        database.clipDao().updateSummary(clipId, "intermediate summary")
        database.clipDao().updateSummary(clipId, "latest summary")
        embedder.blockOnInput = null
        embedder.releaseBlocked.complete(Unit)

        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                SemanticTextChunker.fingerprint(
                    SemanticTextSource(SemanticSourceType.SUMMARY, "latest summary"),
                ) == derivedSearchStorage.getAllSemanticSourceFingerprints()[SemanticSourceKey(clipId, SemanticSourceType.SUMMARY)]
        }
        assertTrue(embedder.inputs.contains("latest summary"))
        assertFalse(embedder.inputs.contains("intermediate summary"))
    }

    @Test
    fun modelInitializationFailureStopsOneReconcileWithoutRepeatedInitializationAttempts() = runBlocking {
        insertClip("init-failure", summary = "init summary", ocrText = "init OCR")
        embedder.failInitialization = true
        synchronizer.start(scope)

        awaitCondition { synchronizer.state.value.status == SemanticIndexSyncStatus.FAILED }
        assertEquals(1, embedder.inputs.size)
        assertEquals(1, synchronizer.state.value.processedSourceCount)

        embedder.failInitialization = false
        synchronizer.stop()
        synchronizer = newSynchronizer()
        synchronizer.start(scope)
        awaitComplete(sourceCount = 3, reembeddedCount = 3)
    }

    private fun newSynchronizer() = SemanticIndexSynchronizer(
        databaseFlow = databaseFlow,
        derivedSearchStorage = derivedSearchStorage,
        embedder = embedder,
    )

    private suspend fun awaitComplete(sourceCount: Int, reembeddedCount: Int) {
        awaitCondition {
            synchronizer.state.value.status == SemanticIndexSyncStatus.COMPLETE &&
                synchronizer.state.value.targetSourceCount == sourceCount &&
                synchronizer.state.value.processedSourceCount == sourceCount &&
                synchronizer.state.value.reembeddedSourceCount == reembeddedCount
        }
    }

    private suspend fun awaitFingerprint(clipId: Long) {
        awaitCondition {
            derivedSearchStorage.getAllSemanticSourceFingerprints().keys.any { it.clipId == clipId }
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(30_000) {
            while (!condition()) delay(20)
        }
    }

    private suspend fun insertClip(
        name: String,
        text: String = "$name text",
        summary: String = "",
        ocrText: String = "",
    ): Long = database.clipDao().insertClip(
        ClipEntity(
            xPostId = "post-$name-${System.nanoTime()}",
            authorName = "$name display",
            authorUsername = "$name user",
            text = text,
            postUrl = "https://example.test/$name",
            xCreatedAt = "created-$name",
            savedAt = "saved-$name",
            syncedAt = "synced-$name",
            summary = summary,
            ocrText = ocrText,
        ),
    )

    private class RecordingEmbedder : DocumentEmbedder {
        val inputs = CopyOnWriteArrayList<String>()
        var failOnInput: String? = null
        var failInitialization: Boolean = false
        var blockOnInput: String? = null
        val blockedStarted = CompletableDeferred<Unit>()
        val releaseBlocked = CompletableDeferred<Unit>()

        override suspend fun embedDocument(text: String): TextEmbedding {
            inputs += text
            if (failInitialization) {
                throw EmbeddingRuntimeInitializationException(IllegalStateException("synthetic init failure"))
            }
            if (blockOnInput == text) {
                blockedStarted.complete(Unit)
                releaseBlocked.await()
            }
            if (failOnInput == text) error("synthetic embedding failure")
            return TextEmbedding.fromModelOutput(FloatArray(EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION) { index ->
                1f + ((text.hashCode() and 0x7fff) / 10_000f) + index / 1_000_000f
            })
        }

        fun clear() {
            inputs.clear()
        }
    }
}
