package com.lyco256.llm.data

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DerivedSearchStorageIntegrationTest {
    private lateinit var context: Context
    private lateinit var derivedDirectory: File
    private lateinit var storage: DerivedSearchStorage

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        derivedDirectory = File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME)
        derivedDirectory.deleteRecursively()
        storage = DerivedSearchStorage(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            storage.close()
            derivedDirectory.deleteRecursively()
        }
    }

    @Test
    fun bundledSqliteCreatesBothFtsIndexesAndSearches() = runBlocking {
        storage.replaceClipDocuments(42L, fixtureDocuments(42L))

        assertEquals(listOf("clip-42-body"), storage.searchNormal("Bundled Needle").map { it.documentId })
        assertEquals(listOf(42L), storage.searchTrigram("Needle").map { it.clipId })
        assertEquals("body", storage.searchNormal("Bundled Needle").single().sourceType)
        assertEquals(0, storage.searchNormal("").size)
        assertTrue(File(derivedDirectory, DerivedSearchStorage.DATABASE_NAME).isFile)
    }

    @Test
    fun clipReplacementAndDeletionAreAtomicAcrossBaseAndBothIndexes() = runBlocking {
        storage.replaceClipDocuments(7L, fixtureDocuments(7L))
        storage.replaceClipDocuments(
            7L,
            listOf(
                fixtureDocuments(7L).first().copy(
                    documentId = "clip-7-summary",
                    sourceType = "summary",
                    sourceOrdinal = 0,
                    rawText = "Replacement Only",
                    normalizedText = "replacement only",
                    readingText = "replacement only",
                    romanizedText = "replacement only",
                    compactText = "replacementonly",
                ),
            ),
        )

        assertTrue(storage.searchNormal("Replacement").any { it.clipId == 7L })
        assertTrue(storage.searchTrigram("Replacement").any { it.clipId == 7L })
        assertFalse(storage.searchNormal("Bundled Needle").any { it.clipId == 7L })
        assertFalse(storage.searchTrigram("Needle").any { it.clipId == 7L })

        storage.deleteClipDocuments(7L)
        assertTrue(storage.searchNormal("Replacement").none { it.clipId == 7L })
        assertTrue(storage.searchTrigram("Replacement").none { it.clipId == 7L })

        storage.close()
        inspectDerivedDatabase { connection ->
            assertEquals(0L, connection.queryCount("lexical_documents", 7L))
            assertEquals(0L, connection.queryCount("lexical_documents_fts", 7L))
            assertEquals(0L, connection.queryCount("lexical_documents_trigram_fts", 7L))
        }
    }

    @Test
    fun closeAndReopenPreservesDerivedDataWithoutTouchingPrimaryPaths() = runBlocking {
        val primaryDatabase = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
        val primaryImages = File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY)
        val primaryPreferences = context.getSharedPreferences(BuildConfig.STORAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val apiPreferences = context.getSharedPreferences(BuildConfig.API_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val databaseHashBefore = primaryDatabase.takeIf(File::isFile)?.sha256()
        val imageNamesBefore = primaryImages.walkTopDown().filter(File::isFile).map(File::getName).toList()
        val primaryPreferencesBefore = primaryPreferences.all
        val apiPreferencesBefore = apiPreferences.all

        storage.replaceClipDocuments(11L, fixtureDocuments(11L))
        storage.clear()
        storage.replaceClipDocuments(11L, fixtureDocuments(11L))
        storage.close()
        storage = DerivedSearchStorage(context)

        assertTrue(storage.searchNormal("Bundled Needle").any { it.clipId == 11L })
        assertEquals(databaseHashBefore, primaryDatabase.takeIf(File::isFile)?.sha256())
        assertEquals(imageNamesBefore, primaryImages.walkTopDown().filter(File::isFile).map(File::getName).toList())
        assertEquals(primaryPreferencesBefore, primaryPreferences.all)
        assertEquals(apiPreferencesBefore, apiPreferences.all)
    }

    @Test
    fun concurrentOperationsRemainConsistent() = runBlocking {
        withContext(Dispatchers.Default) {
            (1L..40L).map { clipId ->
                async {
                    storage.replaceClipDocuments(clipId, fixtureDocuments(clipId))
                    storage.searchNormal("Bundled")
                    storage.searchTrigram("Needle")
                }
            }.awaitAll()
        }

        assertEquals((1L..40L).toSet(), storage.searchTrigram("Needle").map { it.clipId }.toSet())
    }

    @Test
    fun schemaMismatchIsRecreatedOnceAndOnlyDerivedFilesAreReplaced() = runBlocking {
        val primaryBefore = snapshotPrimaryData()
        storage.replaceClipDocuments(20L, fixtureDocuments(20L))
        storage.close()
        setUserVersion(999)

        storage = DerivedSearchStorage(context)
        assertTrue(storage.searchNormal("Bundled Needle").isEmpty())
        storage.replaceClipDocuments(21L, fixtureDocuments(21L))
        assertTrue(storage.searchNormal("Bundled Needle").any { it.clipId == 21L })
        assertPrimaryDataUnchanged(primaryBefore)
    }

    @Test
    fun corruptDerivedDatabaseIsRecreatedWithoutChangingPrimaryData() = runBlocking {
        val primaryBefore = snapshotPrimaryData()
        storage.replaceClipDocuments(30L, fixtureDocuments(30L))
        storage.close()
        val databaseFile = File(derivedDirectory, DerivedSearchStorage.DATABASE_NAME)
        databaseFile.writeText("not a SQLite database")

        storage = DerivedSearchStorage(context)
        assertTrue(storage.searchNormal("Bundled Needle").isEmpty())
        storage.replaceClipDocuments(31L, fixtureDocuments(31L))
        assertTrue(storage.searchTrigram("Needle").any { it.clipId == 31L })
        assertPrimaryDataUnchanged(primaryBefore)
    }

    private data class PrimaryDataSnapshot(
        val databaseHash: String?,
        val imageNames: List<String>,
        val storagePreferences: Map<String, *>,
        val apiPreferences: Map<String, *>,
    )

    private fun snapshotPrimaryData(): PrimaryDataSnapshot {
        val primaryDatabase = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
        val primaryImages = File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY)
        return PrimaryDataSnapshot(
            databaseHash = primaryDatabase.takeIf(File::isFile)?.sha256(),
            imageNames = primaryImages.walkTopDown().filter(File::isFile).map(File::getName).toList(),
            storagePreferences = context.getSharedPreferences(
                BuildConfig.STORAGE_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).all,
            apiPreferences = context.getSharedPreferences(
                BuildConfig.API_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).all,
        )
    }

    private fun assertPrimaryDataUnchanged(before: PrimaryDataSnapshot) {
        val primaryDatabase = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
        val primaryImages = File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY)
        assertEquals(before.databaseHash, primaryDatabase.takeIf(File::isFile)?.sha256())
        assertEquals(before.imageNames, primaryImages.walkTopDown().filter(File::isFile).map(File::getName).toList())
        assertEquals(
            before.storagePreferences,
            context.getSharedPreferences(BuildConfig.STORAGE_PREFERENCES_NAME, Context.MODE_PRIVATE).all,
        )
        assertEquals(
            before.apiPreferences,
            context.getSharedPreferences(BuildConfig.API_PREFERENCES_NAME, Context.MODE_PRIVATE).all,
        )
    }

    private fun inspectDerivedDatabase(block: (androidx.sqlite.SQLiteConnection) -> Unit) {
        val connection = BundledSQLiteDriver().open(
            File(derivedDirectory, DerivedSearchStorage.DATABASE_NAME).absolutePath,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX,
        )
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun androidx.sqlite.SQLiteConnection.queryCount(tableName: String, clipId: Long): Long {
        val statement = prepare("SELECT COUNT(*) FROM $tableName WHERE clip_id = ?")
        return try {
            statement.bindLong(1, clipId)
            check(statement.step())
            statement.getLong(0)
        } finally {
            statement.close()
        }
    }

    private fun fixtureDocuments(clipId: Long): List<LexicalDocument> = listOf(
        LexicalDocument(
            documentId = "clip-$clipId-body",
            clipId = clipId,
            sourceType = "body",
            sourceOrdinal = 0,
            rawText = "Bundled Needle for clip $clipId",
            normalizedText = "bundled needle for clip $clipId",
            readingText = "bundled needle for clip $clipId",
            romanizedText = "bundled needle for clip $clipId",
            compactText = "bundledneedleforclip$clipId",
        ),
        LexicalDocument(
            documentId = "clip-$clipId-ocr-0",
            clipId = clipId,
            sourceType = "ocr",
            sourceOrdinal = 0,
            rawText = "OCR chunk $clipId",
            normalizedText = "ocr chunk $clipId",
            readingText = "ocr chunk $clipId",
            romanizedText = "ocr chunk $clipId",
            compactText = "ocrchunk$clipId",
        ),
    )

    private fun setUserVersion(version: Int) {
        val databaseFile = File(derivedDirectory, DerivedSearchStorage.DATABASE_NAME)
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.execSQL("PRAGMA user_version = $version")
        } finally {
            database.close()
        }
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }
}
