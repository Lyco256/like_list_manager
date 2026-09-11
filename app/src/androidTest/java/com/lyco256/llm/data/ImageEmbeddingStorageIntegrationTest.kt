package com.lyco256.llm.data

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageEmbeddingStorageIntegrationTest {
    private lateinit var context: Context
    private lateinit var storage: DerivedSearchStorage
    private val derivedDirectory: File
        get() = File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME)
    private val derivedFile: File
        get() = File(derivedDirectory, DerivedSearchStorage.DATABASE_NAME)

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        derivedDirectory.deleteRecursively()
        storage = DerivedSearchStorage(context)
    }

    @After
    fun tearDown() {
        runBlocking { storage.close() }
        derivedDirectory.deleteRecursively()
    }

    @Test
    fun replacesReadsAfterReopenAndDeletesByAssetOrClip() = runBlocking {
        storage.replaceImageEmbedding(
            assetId = 11L,
            clipId = 7L,
            sourceFingerprint = "photo-v1",
            embeddingBlob = ImageEmbeddingBlobCodec.encode(vector(1f)),
        )
        storage.replaceImageEmbedding(
            assetId = 12L,
            clipId = 7L,
            sourceFingerprint = "thumb-v1",
            embeddingBlob = ImageEmbeddingBlobCodec.encode(vector(2f)),
        )

        assertEquals(
            mapOf(11L to "photo-v1", 12L to "thumb-v1"),
            storage.getAllImageEmbeddingFingerprints(),
        )
        assertEquals(11L, requireNotNull(storage.getImageEmbedding(11L)).assetId)
        assertEquals(7L, storage.getImageEmbeddingsForClip(7L).first().clipId)
        assertEquals(2, storage.getAllImageEmbeddings().size)

        storage.replaceImageEmbedding(
            assetId = 11L,
            clipId = 8L,
            sourceFingerprint = "photo-v2",
            embeddingBlob = ImageEmbeddingBlobCodec.encode(vector(3f)),
        )
        assertEquals("photo-v2", requireNotNull(storage.getImageEmbedding(11L)).sourceFingerprint)
        assertEquals(8L, requireNotNull(storage.getImageEmbedding(11L)).clipId)

        storage.close()
        storage = DerivedSearchStorage(context)
        assertEquals(2, storage.getAllImageEmbeddings().size)
        storage.deleteImageEmbedding(11L)
        assertTrue(storage.getImageEmbedding(11L) == null)
        storage.deleteImageEmbeddingsForClip(7L)
        assertTrue(storage.getAllImageEmbeddings().isEmpty())
    }

    @Test
    fun clearIncludesImageEmbeddings() = runBlocking {
        storage.replaceClipDocuments(1L, fixtureDocuments(1L))
        storage.replaceImageEmbedding(
            assetId = 21L,
            clipId = 1L,
            sourceFingerprint = "image-21",
            embeddingBlob = ImageEmbeddingBlobCodec.encode(vector(1f)),
        )

        storage.clear()

        assertTrue(storage.getAllImageEmbeddings().isEmpty())
        assertTrue(storage.searchNormal("fixture").isEmpty())
    }

    @Test
    fun replaceFailureKeepsPreviousRowAndFingerprint() = runBlocking {
        storage.replaceImageEmbedding(
            assetId = 31L,
            clipId = 3L,
            sourceFingerprint = "old",
            embeddingBlob = ImageEmbeddingBlobCodec.encode(vector(1f)),
        )
        storage.close()
        withRawConnection { connection ->
            connection.executeSql(
                """
                CREATE TRIGGER image_embedding_fail_insert
                BEFORE INSERT ON image_embeddings
                BEGIN
                    SELECT RAISE(ABORT, 'synthetic image insert failure');
                END
                """.trimIndent(),
            )
        }
        storage = DerivedSearchStorage(context)

        assertThrows(Exception::class.java) {
            runBlocking {
                storage.replaceImageEmbedding(
                    assetId = 31L,
                    clipId = 3L,
                    sourceFingerprint = "new",
                    embeddingBlob = ImageEmbeddingBlobCodec.encode(vector(2f)),
                )
            }
        }

        assertEquals("old", storage.getAllImageEmbeddingFingerprints().getValue(31L))
        assertEquals(1f, requireNotNull(storage.getImageEmbedding(31L)).embedding.first(), 0f)
        storage.close()
        withRawConnection { connection -> connection.executeSql("DROP TRIGGER image_embedding_fail_insert") }
        storage = DerivedSearchStorage(context)
    }

    @Test
    fun schemaMismatchRecreatesOnlyDerivedImageTables() = runBlocking {
        storage.replaceImageEmbedding(
            assetId = 41L,
            clipId = 4L,
            sourceFingerprint = "before-recreate",
            embeddingBlob = ImageEmbeddingBlobCodec.encode(vector(1f)),
        )
        storage.close()
        withRawConnection { connection -> connection.executeSql("PRAGMA user_version = 999") }

        storage = DerivedSearchStorage(context)

        assertTrue(storage.getAllImageEmbeddings().isEmpty())
        assertTrue(derivedFile.isFile)
    }

    private fun vector(seed: Float): FloatArray = FloatArray(ImageEmbeddingBlobCodec.DIMENSION) { index ->
        seed + index / 10_000f
    }

    private fun fixtureDocuments(clipId: Long): List<LexicalDocument> = listOf(
        LexicalDocument(
            documentId = "fixture-$clipId",
            clipId = clipId,
            sourceType = "text",
            sourceOrdinal = 0,
            rawText = "fixture text",
            normalizedText = "fixture text",
            readingText = "fixture text",
            romanizedText = "fixture text",
            compactText = "fixturetext",
        ),
    )

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
