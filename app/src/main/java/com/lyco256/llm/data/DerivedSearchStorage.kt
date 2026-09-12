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

data class SearchDataSnapshot<T>(val revision: Long, val documents: List<T>)

data class LexicalRetrievalSnapshot(
    val revision: Long,
    /** Null means the caller's revision is current; an empty list means an empty corpus. */
    val documents: List<LexicalDocument>?,
    val candidateDocumentIds: Set<String>,
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
    private var lexicalRevision = 0L
    private var semanticRevision = 0L
    private var imageRevision = 0L

    val databasePath: File
        get() = databaseFile

    /**
     * Compatibility overload for callers that only exercise the original storage foundation.
     * Synchronization code must use the fingerprint-bearing overload below.
     */
    suspend fun replaceClipDocuments(clipId: Long, documents: List<LexicalDocument>) =
        replaceClipDocuments(clipId, LEGACY_FINGERPRINT, documents)

    suspend fun replaceClipDocuments(
        clipId: Long,
        sourceFingerprint: String,
        documents: List<LexicalDocument>,
    ) = withStorage {
        require(sourceFingerprint.isNotBlank()) { "Source fingerprint must not be blank" }
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
                insertDocument(document, LEXICAL_DOCUMENTS_TABLE)
                insertDocument(document, NORMAL_FTS_TABLE)
                insertDocument(document, TRIGRAM_FTS_TABLE)
            }
            upsertFingerprint(clipId, sourceFingerprint)
        }
        lexicalRevision++
    }

    suspend fun deleteClipDocuments(clipId: Long) = withStorage {
        inTransaction {
            deleteFromFts(clipId)
            execute("DELETE FROM lexical_documents WHERE clip_id = ?") { statement ->
                statement.bindLong(1, clipId)
            }
            execute("DELETE FROM lexical_sync_state WHERE clip_id = ?") { statement ->
                statement.bindLong(1, clipId)
            }
        }
        lexicalRevision++
    }

    suspend fun replaceSemanticSource(
        clipId: Long,
        sourceType: SemanticSourceType,
        sourceFingerprint: String,
        documents: List<SemanticDocument>,
    ) = withStorage {
        require(sourceFingerprint.isNotBlank()) { "Semantic source fingerprint must not be blank" }
        require(documents.isNotEmpty()) { "Semantic source must contain at least one document" }
        require(documents.all { document ->
            document.clipId == clipId && document.sourceType == sourceType
        }) { "All semantic documents must belong to $clipId/$sourceType" }
        require(documents.map(SemanticDocument::sourceOrdinal).toSet() == (0 until documents.size).toSet()) {
            "Semantic document ordinals must be consecutive from zero"
        }
        require(documents.map(SemanticDocument::documentId).distinct().size == documents.size) {
            "Semantic document IDs must be unique"
        }
        documents.forEach { document ->
            require(document.documentId == SemanticTextChunker.documentId(clipId, sourceType, document.sourceOrdinal)) {
                "Semantic document ID does not match its source"
            }
            SemanticEmbeddingBlobCodec.encode(document.embedding)
        }

        inTransaction {
            deleteSemanticSourceRows(clipId, sourceType)
            documents.forEach { document -> insertSemanticDocument(document) }
            upsertSemanticFingerprint(clipId, sourceType, sourceFingerprint)
        }
        semanticRevision++
    }

    suspend fun deleteSemanticSource(clipId: Long, sourceType: SemanticSourceType) = withStorage {
        inTransaction { deleteSemanticSourceRows(clipId, sourceType) }
        semanticRevision++
    }

    suspend fun deleteSemanticClip(clipId: Long) = withStorage {
        inTransaction {
            execute("DELETE FROM semantic_documents WHERE clip_id = ?") { statement ->
                statement.bindLong(1, clipId)
            }
            execute("DELETE FROM semantic_source_sync_state WHERE clip_id = ?") { statement ->
                statement.bindLong(1, clipId)
            }
        }
        semanticRevision++
    }

    suspend fun getAllSemanticSourceFingerprints(): Map<SemanticSourceKey, String> = withStorage {
        queryRows(
            sql = "SELECT clip_id, source_type, source_fingerprint FROM semantic_source_sync_state ORDER BY clip_id, source_type",
            bind = {},
            map = { statement ->
                SemanticSourceKey(
                    clipId = statement.getLong(0),
                    sourceType = SemanticSourceType.fromStorageValue(statement.getText(1)),
                ) to statement.getText(2)
            },
        ).toMap()
    }

    suspend fun getSemanticDocuments(clipId: Long): List<SemanticDocument> = withStorage {
        queryRows(
            sql = """
                SELECT document_id, clip_id, source_type, source_ordinal, embedding
                FROM semantic_documents
                WHERE clip_id = ?
                ORDER BY source_type, source_ordinal
            """.trimIndent(),
            bind = { statement -> statement.bindLong(1, clipId) },
            map = { statement -> decodeSemanticDocument(statement) },
        )
    }

    suspend fun getAllSemanticDocuments(): List<SemanticDocument> = withStorage {
        queryRows(
            sql = """
                SELECT document_id, clip_id, source_type, source_ordinal, embedding
                FROM semantic_documents
                ORDER BY clip_id, source_type, source_ordinal
            """.trimIndent(),
            bind = {},
            map = { statement -> decodeSemanticDocument(statement) },
        )
    }

    suspend fun replaceImageEmbedding(
        assetId: Long,
        clipId: Long,
        sourceFingerprint: String,
        embeddingBlob: ByteArray,
    ) = withStorage {
        require(assetId > 0L) { "Image embedding asset ID must be positive" }
        require(clipId > 0L) { "Image embedding clip ID must be positive" }
        require(sourceFingerprint.isNotBlank()) { "Image embedding source fingerprint must not be blank" }
        ImageEmbeddingBlobCodec.decode(embeddingBlob)

        inTransaction {
            execute(
                """
                INSERT INTO image_embeddings(asset_id, clip_id, source_fingerprint, embedding)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(asset_id) DO UPDATE SET
                    clip_id = excluded.clip_id,
                    source_fingerprint = excluded.source_fingerprint,
                    embedding = excluded.embedding
                """.trimIndent(),
            ) { statement ->
                statement.bindLong(1, assetId)
                statement.bindLong(2, clipId)
                statement.bindText(3, sourceFingerprint)
                statement.bindBlob(4, embeddingBlob)
            }
        }
        imageRevision++
    }

    suspend fun deleteImageEmbedding(assetId: Long) = withStorage {
        execute("DELETE FROM image_embeddings WHERE asset_id = ?") { statement ->
            statement.bindLong(1, assetId)
        }
        imageRevision++
    }

    suspend fun deleteImageEmbeddingsForClip(clipId: Long) = withStorage {
        execute("DELETE FROM image_embeddings WHERE clip_id = ?") { statement ->
            statement.bindLong(1, clipId)
        }
        imageRevision++
    }

    suspend fun getAllImageEmbeddingFingerprints(): Map<Long, String> = withStorage {
        queryRows(
            sql = "SELECT asset_id, source_fingerprint FROM image_embeddings ORDER BY asset_id",
            bind = {},
            map = { statement -> statement.getLong(0) to statement.getText(1) },
        ).toMap()
    }

    suspend fun getImageEmbedding(assetId: Long): ImageEmbeddingDocument? = withStorage {
        queryRows(
            sql = """
                SELECT asset_id, clip_id, source_fingerprint, embedding
                FROM image_embeddings
                WHERE asset_id = ?
                LIMIT 1
            """.trimIndent(),
            bind = { statement -> statement.bindLong(1, assetId) },
            map = { statement -> decodeImageEmbedding(statement) },
        ).singleOrNull()
    }

    suspend fun getImageEmbeddingsForClip(clipId: Long): List<ImageEmbeddingDocument> = withStorage {
        queryRows(
            sql = """
                SELECT asset_id, clip_id, source_fingerprint, embedding
                FROM image_embeddings
                WHERE clip_id = ?
                ORDER BY asset_id
            """.trimIndent(),
            bind = { statement -> statement.bindLong(1, clipId) },
            map = { statement -> decodeImageEmbedding(statement) },
        )
    }

    suspend fun getAllImageEmbeddings(): List<ImageEmbeddingDocument> = withStorage {
        queryRows(
            sql = """
                SELECT asset_id, clip_id, source_fingerprint, embedding
                FROM image_embeddings
                ORDER BY asset_id
            """.trimIndent(),
            bind = {},
            map = { statement -> decodeImageEmbedding(statement) },
        )
    }

    suspend fun getAllClipFingerprints(): Map<Long, String> = withStorage {
        queryRows(
            sql = "SELECT clip_id, source_fingerprint FROM lexical_sync_state ORDER BY clip_id",
            bind = {},
            map = { statement -> statement.getLong(0) to statement.getText(1) },
        ).toMap()
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
            execute("DELETE FROM lexical_sync_state")
            execute("DELETE FROM semantic_documents")
            execute("DELETE FROM semantic_source_sync_state")
            execute("DELETE FROM image_embeddings")
        }
        lexicalRevision++
        semanticRevision++
        imageRevision++
    }

    suspend fun getAllLexicalDocuments(): List<LexicalDocument> = withStorage { readLexicalDocuments() }

    suspend fun getLexicalSnapshot(): SearchDataSnapshot<LexicalDocument> = withStorage {
        SearchDataSnapshot(lexicalRevision, readLexicalDocuments())
    }

    /** Revision and rows (if changed) are read in the same mutex interval. */
    suspend fun getSemanticSnapshot(knownRevision: Long? = null): SearchDataSnapshot<SemanticDocument>? = withStorage {
        if (knownRevision == semanticRevision) null else SearchDataSnapshot(
            semanticRevision,
            queryRows(
                "SELECT document_id, clip_id, source_type, source_ordinal, embedding FROM semantic_documents " +
                    "ORDER BY clip_id, source_type, source_ordinal, document_id",
                {}, ::decodeSemanticDocument,
            ),
        )
    }

    suspend fun getImageSnapshot(knownRevision: Long? = null): SearchDataSnapshot<ImageEmbeddingDocument>? = withStorage {
        if (knownRevision == imageRevision) null else SearchDataSnapshot(
            imageRevision,
            queryRows(
                "SELECT asset_id, clip_id, source_fingerprint, embedding FROM image_embeddings ORDER BY asset_id",
                {}, ::decodeImageEmbedding,
            ),
        )
    }

    /** FTS candidates and lexical cache refresh refer to exactly the same corpus. */
    internal suspend fun retrieveLexical(
        knownRevision: Long?,
        queries: Fts5LiteralQueries,
    ): LexicalRetrievalSnapshot = withStorage {
        val documents = if (knownRevision == lexicalRevision) null else readLexicalDocuments()
        val count = queryLong("SELECT COUNT(*) FROM lexical_documents")
        val ids = linkedSetOf<String>()
        if (count > 0) {
            listOf(NORMAL_FTS_TABLE to queries.normal, TRIGRAM_FTS_TABLE to queries.trigram).forEach { (table, literals) ->
                literals.forEach { literal ->
                    ids.addAll(queryRows(
                        "SELECT document_id FROM $table WHERE $table MATCH ? LIMIT ?",
                        { statement -> statement.bindText(1, literal); statement.bindLong(2, count) },
                        { statement -> statement.getText(0) },
                    ))
                }
            }
        }
        LexicalRetrievalSnapshot(lexicalRevision, documents, ids)
    }

    private fun SQLiteConnection.readLexicalDocuments(): List<LexicalDocument> = queryRows(
        "SELECT document_id, clip_id, source_type, source_ordinal, raw_text, normalized_text, " +
            "reading_text, romanized_text, compact_text FROM lexical_documents ORDER BY clip_id, document_id",
        {},
        { row -> LexicalDocument(
            row.getText(0), row.getLong(1), row.getText(2), row.getLong(3).toInt(),
            row.getText(4), row.getText(5), row.getText(6), row.getText(7), row.getText(8),
        ) },
    )

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
                    lexicalRevision++
                    semanticRevision++
                    imageRevision++
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
                    CREATE TABLE lexical_sync_state (
                        clip_id INTEGER NOT NULL PRIMARY KEY,
                        source_fingerprint TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
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
                execute(
                    """
                    CREATE TABLE semantic_documents (
                        document_id TEXT NOT NULL PRIMARY KEY,
                        clip_id INTEGER NOT NULL,
                        source_type TEXT NOT NULL,
                        source_ordinal INTEGER NOT NULL,
                        embedding BLOB NOT NULL
                    )
                    """.trimIndent(),
                )
                execute("CREATE INDEX semantic_documents_clip_source ON semantic_documents(clip_id, source_type, source_ordinal)")
                execute(
                    """
                    CREATE TABLE semantic_source_sync_state (
                        clip_id INTEGER NOT NULL,
                        source_type TEXT NOT NULL,
                        source_fingerprint TEXT NOT NULL,
                        PRIMARY KEY(clip_id, source_type)
                    )
                    """.trimIndent(),
                )
                execute(
                    """
                    CREATE TABLE image_embeddings (
                        asset_id INTEGER NOT NULL PRIMARY KEY,
                        clip_id INTEGER NOT NULL,
                        source_fingerprint TEXT NOT NULL,
                        embedding BLOB NOT NULL
                    )
                    """.trimIndent(),
                )
                execute("CREATE INDEX image_embeddings_clip_id ON image_embeddings(clip_id)")
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

    private fun SQLiteConnection.insertSemanticDocument(document: SemanticDocument) {
        execute(
            """
            INSERT INTO semantic_documents(
                document_id, clip_id, source_type, source_ordinal, embedding
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.bindText(1, document.documentId)
            statement.bindLong(2, document.clipId)
            statement.bindText(3, document.sourceType.storageValue)
            statement.bindLong(4, document.sourceOrdinal.toLong())
            statement.bindBlob(5, SemanticEmbeddingBlobCodec.encode(document.embedding))
        }
    }

    private fun decodeSemanticDocument(statement: SQLiteStatement): SemanticDocument {
        val clipId = statement.getLong(1)
        val sourceType = SemanticSourceType.fromStorageValue(statement.getText(2))
        val sourceOrdinal = statement.getLong(3).toInt()
        return SemanticDocument(
            documentId = statement.getText(0),
            clipId = clipId,
            sourceType = sourceType,
            sourceOrdinal = sourceOrdinal,
            embedding = SemanticEmbeddingBlobCodec.decode(statement.getBlob(4)),
        )
    }

    private fun decodeImageEmbedding(statement: SQLiteStatement): ImageEmbeddingDocument {
        val sourceFingerprint = statement.getText(2)
        require(sourceFingerprint.isNotBlank()) { "Image embedding source fingerprint must not be blank" }
        return ImageEmbeddingDocument(
            assetId = statement.getLong(0),
            clipId = statement.getLong(1),
            sourceFingerprint = sourceFingerprint,
            embedding = ImageEmbeddingBlobCodec.decode(statement.getBlob(3)),
        )
    }

    private fun SQLiteConnection.deleteSemanticSourceRows(clipId: Long, sourceType: SemanticSourceType) {
        execute("DELETE FROM semantic_documents WHERE clip_id = ? AND source_type = ?") { statement ->
            statement.bindLong(1, clipId)
            statement.bindText(2, sourceType.storageValue)
        }
        execute("DELETE FROM semantic_source_sync_state WHERE clip_id = ? AND source_type = ?") { statement ->
            statement.bindLong(1, clipId)
            statement.bindText(2, sourceType.storageValue)
        }
    }

    private fun SQLiteConnection.upsertSemanticFingerprint(
        clipId: Long,
        sourceType: SemanticSourceType,
        sourceFingerprint: String,
    ) {
        execute(
            """
            INSERT INTO semantic_source_sync_state(clip_id, source_type, source_fingerprint)
            VALUES (?, ?, ?)
            ON CONFLICT(clip_id, source_type) DO UPDATE SET source_fingerprint = excluded.source_fingerprint
            """.trimIndent(),
        ) { statement ->
            statement.bindLong(1, clipId)
            statement.bindText(2, sourceType.storageValue)
            statement.bindText(3, sourceFingerprint)
        }
    }

    private fun SQLiteConnection.upsertFingerprint(clipId: Long, sourceFingerprint: String) {
        execute(
            """
            INSERT INTO lexical_sync_state(clip_id, source_fingerprint)
            VALUES (?, ?)
            ON CONFLICT(clip_id) DO UPDATE SET source_fingerprint = excluded.source_fingerprint
            """.trimIndent(),
        ) { statement ->
            statement.bindLong(1, clipId)
            statement.bindText(2, sourceFingerprint)
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
        const val SCHEMA_VERSION = 4
        const val DEFAULT_SEARCH_LIMIT = 100

        private const val LEGACY_FINGERPRINT = "legacy-foundation-document"

        private const val LEXICAL_DOCUMENTS_TABLE = "lexical_documents"
        private const val NORMAL_FTS_TABLE = "lexical_documents_fts"
        private const val TRIGRAM_FTS_TABLE = "lexical_documents_trigram_fts"
        private const val SEMANTIC_DOCUMENTS_TABLE = "semantic_documents"
        private const val SEMANTIC_SOURCE_SYNC_STATE_TABLE = "semantic_source_sync_state"
        private const val IMAGE_EMBEDDINGS_TABLE = "image_embeddings"
        private val REQUIRED_OBJECTS = listOf(
            "lexical_documents",
            "lexical_sync_state",
            NORMAL_FTS_TABLE,
            TRIGRAM_FTS_TABLE,
            SEMANTIC_DOCUMENTS_TABLE,
            SEMANTIC_SOURCE_SYNC_STATE_TABLE,
            IMAGE_EMBEDDINGS_TABLE,
            "image_embeddings_clip_id",
        )
    }
}
