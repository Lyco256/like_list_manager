package com.lyco256.llm.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LexicalIndexSynchronizerIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: LikeListDatabase
    private lateinit var databaseFlow: MutableStateFlow<LikeListDatabase?>
    private lateinit var derivedSearchStorage: DerivedSearchStorage
    private lateinit var analyzer: RecordingAnalyzer
    private lateinit var synchronizer: LexicalIndexSynchronizer
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
        analyzer = RecordingAnalyzer()
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
    fun initialNewEditDeleteAndUndoRestorationFollowTheClipFlow() = runBlocking {
        val first = insertClip("first", summary = "first summary", ocrText = "first OCR")
        synchronizer.start(scope)
        awaitComplete(1)
        assertEquals(setOf(first), derivedSearchStorage.getAllClipFingerprints().keys)
        analyzer.clear()

        val second = insertClip("second")
        awaitFingerprint(second)
        assertTrue(analyzer.texts.isNotEmpty())
        assertTrue(analyzer.texts.all { it.contains("second") })
        analyzer.clear()

        database.clipDao().updateSummary(first, "edited summary")
        awaitFingerprint(first)
        assertTrue(analyzer.texts.contains("edited summary"))
        assertTrue(analyzer.texts.none { it.contains("second") })
        analyzer.clear()

        database.clipDao().updateOcrText(first, "edited OCR", "updated")
        awaitFingerprint(first)
        assertTrue(analyzer.texts.contains("edited OCR"))
        assertTrue(analyzer.texts.none { it.contains("second") })
        analyzer.clear()

        database.clipDao().deleteClip(second)
        awaitCondition { second !in derivedSearchStorage.getAllClipFingerprints() }
        assertTrue(derivedSearchStorage.searchNormal("second").none { it.clipId == second })

        val restored = requireNotNull(database.clipDao().getClip(first)).copy(summary = "first summary", ocrText = "first OCR")
        database.clipDao().updateClip(restored)
        awaitFingerprint(first)
        assertTrue(analyzer.texts.contains("first summary"))
        assertTrue(analyzer.texts.contains("first OCR"))
    }

    @Test
    fun unchangedSearchFieldsDoNotReanalyzeAfterRestartOrLikeCountUpdate() = runBlocking {
        val clipId = insertClip("stable")
        val firstJob = synchronizer.start(scope)
        awaitComplete(1)
        assertTrue(firstJob.isActive)
        synchronizer.stop()

        analyzer.clear()
        synchronizer = newSynchronizer()
        synchronizer.start(scope)
        awaitComplete(1)
        assertTrue(analyzer.texts.isEmpty())

        database.clipDao().updateLikeCount(clipId, 99, "like-count-only")
        awaitCondition { synchronizer.state.value.status == LexicalIndexSyncStatus.COMPLETE }
        delay(100)
        assertTrue(analyzer.texts.isEmpty())
    }

    @Test
    fun unavailablePrimaryDatabaseDoesNotLookLikeAnEmptyDatabase() = runBlocking {
        val clipId = insertClip("kept")
        synchronizer.start(scope)
        awaitComplete(1)
        val before = derivedSearchStorage.getAllClipFingerprints()

        databaseFlow.value = null
        delay(100)
        assertEquals(before, derivedSearchStorage.getAllClipFingerprints())
        assertTrue(derivedSearchStorage.searchNormal("kept").any { it.clipId == clipId })

        databaseFlow.value = database
        awaitComplete(1)
        assertEquals(before, derivedSearchStorage.getAllClipFingerprints())
    }

    @Test
    fun oneAnalyzerFailureDoesNotFailPrimaryOrBlockOtherClips() = runBlocking {
        analyzer.failText = "bad text"
        val failedId = insertClip("failed", text = "bad text")
        val goodId = insertClip("good", text = "good text")
        synchronizer.start(scope)

        awaitCondition {
            synchronizer.state.value.status == LexicalIndexSyncStatus.FAILED &&
                failedId in synchronizer.state.value.failedClipIds
        }
        assertTrue(database.clipDao().getClip(failedId) != null)
        assertTrue(database.clipDao().getClip(goodId) != null)
        assertFalse(failedId in derivedSearchStorage.getAllClipFingerprints())
        assertEquals(goodId, derivedSearchStorage.getAllClipFingerprints().keys.single())

        analyzer.failText = null
        database.clipDao().updateClip(requireNotNull(database.clipDao().getClip(failedId)).copy(summary = "retry"))
        awaitFingerprint(failedId)
        assertEquals(setOf(failedId, goodId), derivedSearchStorage.getAllClipFingerprints().keys)
    }

    @Test
    fun clearedDerivedDatabaseIsRebuiltWithoutChangingPrimaryDatabase() = runBlocking {
        val clipId = insertClip("rebuild")
        synchronizer.start(scope)
        awaitComplete(1)
        val primaryBefore = requireNotNull(database.clipDao().getClip(clipId))
        derivedSearchStorage.clear()
        analyzer.clear()

        databaseFlow.value = null
        delay(100)
        databaseFlow.value = database
        awaitFingerprint(clipId)

        assertEquals(primaryBefore, database.clipDao().getClip(clipId))
        assertTrue(analyzer.texts.isNotEmpty())
    }

    private fun newSynchronizer() = LexicalIndexSynchronizer(
        databaseFlow = databaseFlow,
        derivedSearchStorage = derivedSearchStorage,
        analyzer = analyzer,
    )

    private suspend fun awaitComplete(expectedClipCount: Int) {
        awaitCondition {
            synchronizer.state.value.status == LexicalIndexSyncStatus.COMPLETE &&
                synchronizer.state.value.processedClipCount == expectedClipCount
        }
    }

    private suspend fun awaitFingerprint(clipId: Long) {
        val expected = LexicalDocumentBuilder.fingerprint(requireNotNull(database.clipDao().getClip(clipId)))
        awaitCondition { derivedSearchStorage.getAllClipFingerprints()[clipId] == expected }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(10_000) {
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
            xPostId = "post-$name",
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

    private class RecordingAnalyzer : LexicalTextAnalyzer {
        val texts = mutableListOf<String>()
        var failText: String? = null

        override suspend fun analyze(text: String): SudachiLexicalTextAnalysis {
            failText?.takeIf { it == text }?.let { error("synthetic analyzer failure") }
            texts += text
            return SudachiLexicalTextAnalysis(
                normalizedText = "clip=${text.hashCode()}:$text",
                readingText = "reading:$text",
                romanizedText = "romanized:$text",
                compactText = "compact:$text",
            )
        }

        fun clear() = texts.clear()
    }
}
