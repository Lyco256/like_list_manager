package com.lyco256.llm.data

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.lyco256.llm.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SemanticSearchStorageIntegrationTest {
    private lateinit var context: Context
    private lateinit var storage: DerivedSearchStorage
    private val derivedFile: File
        get() = File(
            context.noBackupFilesDir,
            "${DerivedSearchStorage.DIRECTORY_NAME}/${DerivedSearchStorage.DATABASE_NAME}",
        )

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME).deleteRecursively()
        storage = DerivedSearchStorage(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            storage.close()
            File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME).deleteRecursively()
        }
    }

    @Test
    fun replacesChunksReadsThemAfterReopenAndDeletesSourceOrClip() = runBlocking {
        val clipId = 7L
        storage.replaceSemanticSource(
            clipId = clipId,
            sourceType = SemanticSourceType.OCR,
            sourceFingerprint = "ocr-v1",
            documents = semanticDocuments(clipId, SemanticSourceType.OCR, 2, 7f),
        )
        assertEquals(
            mapOf(SemanticSourceKey(clipId, SemanticSourceType.OCR) to "ocr-v1"),
            storage.getAllSemanticSourceFingerprints(),
        )
        assertEquals(listOf(0, 1), storage.getSemanticDocuments(clipId).map { it.sourceOrdinal })
        assertEquals(768, storage.getSemanticDocuments(clipId).first().embedding.size)

        storage.replaceSemanticSource(
            clipId = clipId,
            sourceType = SemanticSourceType.OCR,
            sourceFingerprint = "ocr-v2",
            documents = semanticDocuments(clipId, SemanticSourceType.OCR, 1, 9f),
        )
        assertEquals("ocr-v2", storage.getAllSemanticSourceFingerprints().getValue(SemanticSourceKey(clipId, SemanticSourceType.OCR)))
        assertEquals(listOf(0), storage.getSemanticDocuments(clipId).map { it.sourceOrdinal })

        storage.close()
        storage = DerivedSearchStorage(context)
        assertEquals(1, storage.getAllSemanticDocuments().size)
        storage.deleteSemanticSource(clipId, SemanticSourceType.OCR)
        assertTrue(storage.getAllSemanticDocuments().isEmpty())

        storage.replaceSemanticSource(
            clipId = clipId,
            sourceType = SemanticSourceType.TEXT,
            sourceFingerprint = "text-v1",
            documents = semanticDocuments(clipId, SemanticSourceType.TEXT, 1, 3f),
        )
        storage.replaceSemanticSource(
            clipId = clipId,
            sourceType = SemanticSourceType.SUMMARY,
            sourceFingerprint = "summary-v1",
            documents = semanticDocuments(clipId, SemanticSourceType.SUMMARY, 1, 4f),
        )
        storage.deleteSemanticClip(clipId)
        assertTrue(storage.getAllSemanticDocuments().isEmpty())
        assertTrue(storage.getAllSemanticSourceFingerprints().isEmpty())
    }

    @Test
    fun clearIncludesLexicalAndSemanticData() = runBlocking {
        storage.replaceClipDocuments(
            clipId = 8L,
            documents = listOf(
                LexicalDocument(
                    documentId = "lexical-8",
                    clipId = 8L,
                    sourceType = "text",
                    sourceOrdinal = 0,
                    rawText = "clear needle",
                    normalizedText = "clear needle",
                    readingText = "clear needle",
                    romanizedText = "clear needle",
                    compactText = "clearneedle",
                ),
            ),
        )
        storage.replaceSemanticSource(
            clipId = 8L,
            sourceType = SemanticSourceType.TEXT,
            sourceFingerprint = "text-8",
            documents = semanticDocuments(8L, SemanticSourceType.TEXT, 1, 1f),
        )

        storage.clear()

        assertTrue(storage.searchNormal("clear").isEmpty())
        assertTrue(storage.getAllSemanticDocuments().isEmpty())
        assertTrue(storage.getAllSemanticSourceFingerprints().isEmpty())
    }

    @Test
    fun transactionFailureKeepsThePreviousDocumentsAndFingerprint() = runBlocking {
        val clipId = 9L
        storage.replaceSemanticSource(
            clipId = clipId,
            sourceType = SemanticSourceType.TEXT,
            sourceFingerprint = "old",
            documents = semanticDocuments(clipId, SemanticSourceType.TEXT, 2, 2f),
        )
        storage.close()
        withRawConnection { connection ->
            connection.executeSql(
                """
                CREATE TRIGGER semantic_fail_insert
                BEFORE INSERT ON semantic_documents
                BEGIN
                    SELECT RAISE(ABORT, 'synthetic semantic insert failure');
                END
                """.trimIndent(),
            )
        }
        storage = DerivedSearchStorage(context)

        assertThrows(Exception::class.java) {
            runBlocking {
                storage.replaceSemanticSource(
                    clipId = clipId,
                    sourceType = SemanticSourceType.TEXT,
                    sourceFingerprint = "new",
                    documents = semanticDocuments(clipId, SemanticSourceType.TEXT, 1, 5f),
                )
            }
        }

        assertEquals("old", storage.getAllSemanticSourceFingerprints().getValue(SemanticSourceKey(clipId, SemanticSourceType.TEXT)))
        assertEquals(listOf(0, 1), storage.getSemanticDocuments(clipId).map { it.sourceOrdinal })
        storage.close()
        withRawConnection { connection -> connection.executeSql("DROP TRIGGER semantic_fail_insert") }
        storage = DerivedSearchStorage(context)
    }

    @Test
    fun invalidPublicVectorsAndCorruptStoredBlobsAreRejected() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                storage.replaceSemanticSource(
                    clipId = 1L,
                    sourceType = SemanticSourceType.TEXT,
                    sourceFingerprint = "bad-dimension",
                    documents = listOf(
                        SemanticDocument(
                            documentId = SemanticTextChunker.documentId(1L, SemanticSourceType.TEXT, 0),
                            clipId = 1L,
                            sourceType = SemanticSourceType.TEXT,
                            sourceOrdinal = 0,
                            embedding = FloatArray(767),
                        ),
                    ),
                )
            }
        }

        storage.replaceSemanticSource(
            clipId = 1L,
            sourceType = SemanticSourceType.TEXT,
            sourceFingerprint = "valid",
            documents = semanticDocuments(1L, SemanticSourceType.TEXT, 1, 1f),
        )
        storage.close()
        withRawConnection { connection ->
            val statement = connection.prepare("UPDATE semantic_documents SET embedding = ?")
            try {
                statement.bindBlob(1, ByteArray(1))
                statement.step()
            } finally {
                statement.close()
            }
        }
        storage = DerivedSearchStorage(context)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { storage.getSemanticDocuments(1L) }
        }
        Unit
    }

    @Test
    fun schemaMismatchRecreatesDerivedSemanticTablesOnly() = runBlocking {
        storage.replaceSemanticSource(
            clipId = 20L,
            sourceType = SemanticSourceType.TEXT,
            sourceFingerprint = "before-recreate",
            documents = semanticDocuments(20L, SemanticSourceType.TEXT, 1, 1f),
        )
        storage.close()
        withRawConnection { connection -> connection.executeSql("PRAGMA user_version = 999") }
        storage = DerivedSearchStorage(context)

        assertTrue(storage.getAllSemanticDocuments().isEmpty())
        assertTrue(storage.getAllSemanticSourceFingerprints().isEmpty())
        assertTrue(derivedFile.isFile)
    }

    private fun semanticDocuments(
        clipId: Long,
        sourceType: SemanticSourceType,
        count: Int,
        seed: Float,
    ): List<SemanticDocument> = (0 until count).map { ordinal ->
        SemanticDocument(
            documentId = SemanticTextChunker.documentId(clipId, sourceType, ordinal),
            clipId = clipId,
            sourceType = sourceType,
            sourceOrdinal = ordinal,
            embedding = FloatArray(768) { index -> seed + ordinal + index / 10_000f },
        )
    }

    private fun withRawConnection(block: (SQLiteConnection) -> Unit) {
        val connection = BundledSQLiteDriver().open(
            derivedFile.absolutePath,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX,
        )
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun SQLiteConnection.executeSql(sql: String) {
        val statement = prepare(sql)
        try {
            statement.step()
        } finally {
            statement.close()
        }
    }
}
