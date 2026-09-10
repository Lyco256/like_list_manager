package com.lyco256.llm.data

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_EXRESCODE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A row stored in the rebuildable lexical index. Text generation belongs to later layers. */
data class LexicalDocument(
    val documentId: String,
    val clipId: Long,
    val sourceType: String,
    val sourceOrdinal: Int,
    val rawText: String,
    val normalizedText: String,
    val readingText: String,
    val romanizedText: String,
    val compactText: String,
)

data class LexicalSearchResult(
    val documentId: String,
    val clipId: Long,
    val sourceType: String,
    val sourceOrdinal: Int,
)

/**
 * Owns the independent, rebuildable SQLite database used by future lexical retrieval layers.
 *
 * The connection is deliberately private. All operations use one FULLMUTEX connection and an
 * application-level Mutex, so callers cannot accidentally use a non-thread-safe raw connection.
 */
class DerivedSearchStorage(
    context: Context,
    private val driver: BundledSQLiteDriver = BundledSQLiteDriver(),
) {
    private val databaseDirectory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)
    private val databaseFile = File(databaseDirectory, DATABASE_NAME)
    private val mutex = Mutex()
    private var connection: SQLiteConnection? = null

    val databasePath: File
        get() = databaseFile

    suspend fun replaceClipDocuments(clipId: Long, documents: List<LexicalDocument>) = withStorage {
        require(documents.all { it.clipId == clipId }) { "All lexical documents must belong to clip $clipId" }
        require(documents.map { it.documentId }.distinct().size == documents.size) {
            "Lexical document IDs must be unique"
        }
        documents.forEach(::validateDocument)

        inTransaction {
            deleteFromFts(clipId)
            execute(
                "DELETE FROM lexical_documents WHERE clip_id = ?",
            ) { statement -> statement.bindLong(1, clipId) }
            documents.forEach { document ->
                insertDocument(document, NORMAL_FTS_TABLE)
                insertDocument(document, TRIGRAM_FTS_TABLE)
            }
        }
    }

    suspend fun deleteClipDocuments(clipId: Long) = withStorage {
        inTransaction {
            deleteFromFts(clipId)
            execute("DELETE FROM lexical_documents WHERE clip_id = ?") { statement ->
                statement.bindLong(1, clipId)
            }
        }
    }

    suspend fun searchNormal(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<LexicalSearchResult> =
        search(NORMAL_FTS_TABLE, query, limit)

    suspend fun searchTrigram(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<LexicalSearchResult> =
        search(TRIGRAM_FTS_TABLE, query, limit)

    suspend fun clear() = withStorage {
        inTransaction {
            execute("DELETE FROM $NORMAL_FTS_TABLE")
            execute("DELETE FROM $TRIGRAM_FTS_TABLE")
            execute("DELETE FROM lexical_documents")
        }
    }

    /** Closes the private connection. A later operation may safely reopen the derived DB. */
    suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) {
            connection?.close()
            connection = null
        }
    }

    private suspend fun search(
        tableName: String,
        query: String,
        limit: Int,
    ): List<LexicalSearchResult> = withStorage {
        require(tableName == NORMAL_FTS_TABLE || tableName == TRIGRAM_FTS_TABLE)
        require(limit > 0) { "Search limit must be positive" }
        if (query.isBlank()) {
            emptyList<LexicalSearchResult>()
        } else {
            queryRows<LexicalSearchResult>(
                sql = """
                    SELECT document_id, clip_id, source_type, source_ordinal
                    FROM $tableName
                    WHERE $tableName MATCH ?
                    LIMIT ?
                """.trimIndent(),
                bind = { statement ->
                    statement.bindText(1, query)
                    statement.bindLong(2, limit.toLong())
                },
                map = { statement ->
                LexicalSearchResult(
                    documentId = statement.getText(0),
                    clipId = statement.getLong(1),
                    sourceType = statement.getText(2),
                    sourceOrdinal = statement.getLong(3).toInt(),
                )
                },
            )
        }
    }

    private suspend fun <T> withStorage(block: SQLiteConnection.() -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val activeConnection = connection ?: openAndPrepareWithRecovery().also { connection = it }
            activeConnection.block()
        }
    }

    private fun openAndPrepareWithRecovery(): SQLiteConnection {
        var firstFailure: Exception? = null
        repeat(2) { attempt ->
            var opened: SQLiteConnection? = null
            try {
                check(databaseDirectory.mkdirs() || databaseDirectory.isDirectory) {
                    "Cannot create derived search directory: ${databaseDirectory.absolutePath}"
                }
                opened = driver.open(
                    databaseFile.absolutePath,
                    SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or
                        SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_EXRESCODE,
                )
                initializeOrValidate(opened)
                return opened
            } catch (error: Exception) {
                opened?.close()
                if (attempt == 0) {
                    firstFailure = error
                    deleteDerivedDatabaseFiles()
                } else {
                    throw IllegalStateException(
                        "派生検索DBの再作成に失敗しました",
                        error,
                    ).also { failure -> firstFailure?.let(failure::addSuppressed) }
                }
            }
        }
        error("Unreachable derived search database recovery state")
    }

    private fun initializeOrValidate(connection: SQLiteConnection) {
        val userVersion = connection.queryLong("PRAGMA user_version")
        val hasAnyUserObject = connection.queryLong(
            "SELECT COUNT(*) FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' AND type IN ('table', 'index', 'trigger', 'view')",
        ) > 0

        if (userVersion == 0L && !hasAnyUserObject) {
            connection.inTransaction {
                execute(
                    """
                    CREATE TABLE lexical_documents (
                        document_id TEXT NOT NULL PRIMARY KEY,
                        clip_id INTEGER NOT NULL,
                        source_type TEXT NOT NULL,
                        source_ordinal INTEGER NOT NULL,
                        raw_text TEXT NOT NULL,
                        normalized_text TEXT NOT NULL,
                        reading_text TEXT NOT NULL,
                        romanized_text TEXT NOT NULL,
                        compact_text TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                execute("CREATE INDEX lexical_documents_clip_id ON lexical_documents(clip_id)")
                execute(
                    """
                    CREATE VIRTUAL TABLE $NORMAL_FTS_TABLE USING fts5(
                        document_id UNINDEXED,
                        clip_id UNINDEXED,
                        source_type UNINDEXED,
                        source_ordinal UNINDEXED,
                        raw_text,
                        normalized_text,
                        reading_text,
                        romanized_text,
                        compact_text
                    )
                    """.trimIndent(),
                )
                execute(
                    """
                    CREATE VIRTUAL TABLE $TRIGRAM_FTS_TABLE USING fts5(
                        document_id UNINDEXED,
                        clip_id UNINDEXED,
                        source_type UNINDEXED,
                        source_ordinal UNINDEXED,
                        raw_text,
                        normalized_text,
                        reading_text,
                        romanized_text,
                        compact_text,
                        tokenize = 'trigram'
                    )
                    """.trimIndent(),
                )
                execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
            return
        }

        check(userVersion == SCHEMA_VERSION.toLong()) {
            "Unsupported derived search schema version: $userVersion"
        }
        REQUIRED_OBJECTS.forEach { objectName ->
            check(
                connection.queryLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = ?",
                    objectName,
                ) == 1L,
            ) { "Missing derived search schema object: $objectName" }
        }
        val trigramSql = connection.queryText(
            "SELECT sql FROM sqlite_master WHERE name = ?",
            TRIGRAM_FTS_TABLE,
        )
        check(trigramSql.contains("trigram", ignoreCase = true)) {
            "Derived trigram FTS schema is incompatible"
        }
        check(connection.queryText("PRAGMA integrity_check") == "ok") {
            "Derived search database integrity check failed"
        }
    }

    private fun SQLiteConnection.insertDocument(document: LexicalDocument, tableName: String) {
        execute(
            """
            INSERT INTO $tableName(
                document_id, clip_id, source_type, source_ordinal,
                raw_text, normalized_text, reading_text, romanized_text, compact_text
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.bindText(1, document.documentId)
            statement.bindLong(2, document.clipId)
            statement.bindText(3, document.sourceType)
            statement.bindLong(4, document.sourceOrdinal.toLong())
            statement.bindText(5, document.rawText)
            statement.bindText(6, document.normalizedText)
            statement.bindText(7, document.readingText)
            statement.bindText(8, document.romanizedText)
            statement.bindText(9, document.compactText)
        }
    }

    private fun SQLiteConnection.deleteFromFts(clipId: Long) {
        listOf(NORMAL_FTS_TABLE, TRIGRAM_FTS_TABLE).forEach { tableName ->
            val rowIds = queryRows<Long>(
                sql = "SELECT rowid FROM $tableName WHERE clip_id = ?",
                bind = { statement -> statement.bindLong(1, clipId) },
                map = { statement -> statement.getLong(0) },
            )
            rowIds.forEach { rowId ->
                execute("DELETE FROM $tableName WHERE rowid = ?") { statement ->
                    statement.bindLong(1, rowId)
                }
            }
        }
    }

    private fun deleteDerivedDatabaseFiles() {
        listOf(
            databaseFile,
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
            File(databaseFile.path + "-journal"),
        ).forEach { file ->
            if (file.exists()) check(file.delete()) { "Cannot delete derived search file: ${file.path}" }
        }
    }

    private fun validateDocument(document: LexicalDocument) {
        require(document.documentId.isNotBlank()) { "Lexical document ID must not be blank" }
        require(document.sourceType.isNotBlank()) { "Lexical source type must not be blank" }
        require(document.sourceOrdinal >= 0) { "Lexical source ordinal must not be negative" }
    }

    private fun SQLiteConnection.inTransaction(block: SQLiteConnection.() -> Unit) {
        execute("BEGIN IMMEDIATE")
        var committed = false
        try {
            block()
            execute("COMMIT")
            committed = true
        } finally {
            if (!committed) runCatching { execute("ROLLBACK") }
        }
    }

    private fun SQLiteConnection.execute(sql: String, bind: ((SQLiteStatement) -> Unit)? = null) {
        val statement = prepare(sql)
        try {
            bind?.invoke(statement)
            statement.step()
        } finally {
            statement.close()
        }
    }

    private fun <T> SQLiteConnection.queryRows(
        sql: String,
        bind: (SQLiteStatement) -> Unit,
        map: (SQLiteStatement) -> T,
    ): List<T> {
        val statement = prepare(sql)
        return try {
            bind(statement)
            buildList {
                while (statement.step()) add(map(statement))
            }
        } finally {
            statement.close()
        }
    }

    private fun SQLiteConnection.queryLong(sql: String, vararg args: String): Long =
        queryRows(sql, { statement -> args.forEachIndexed { index, value -> statement.bindText(index + 1, value) } }) {
            it.getLong(0)
        }.single()

    private fun SQLiteConnection.queryText(sql: String, vararg args: String): String =
        queryRows(sql, { statement -> args.forEachIndexed { index, value -> statement.bindText(index + 1, value) } }) {
            it.getText(0)
        }.single()

    companion object {
        const val DIRECTORY_NAME = "derived_search"
        const val DATABASE_NAME = "search_index.db"
        const val SCHEMA_VERSION = 1
        const val DEFAULT_SEARCH_LIMIT = 100

        private const val NORMAL_FTS_TABLE = "lexical_documents_fts"
        private const val TRIGRAM_FTS_TABLE = "lexical_documents_trigram_fts"
        private val REQUIRED_OBJECTS = listOf(
            "lexical_documents",
            NORMAL_FTS_TABLE,
            TRIGRAM_FTS_TABLE,
        )
    }
}
