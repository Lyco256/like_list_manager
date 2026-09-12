package com.lyco256.llm.data

import android.content.Context
import android.content.ContextWrapper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSearchEngineIntegrationTest {
    private lateinit var root: File
    private lateinit var storage: DerivedSearchStorage

    @Before fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "local-search-engine-fixture")
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
        check(root.mkdirs())
        storage = DerivedSearchStorage(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        })
    }

    @After fun tearDown() = runBlocking {
        storage.close()
        root.deleteRecursively()
        Unit
    }

    @Test fun realFtsAndBothNativeAnnDimensionsIntegrateRefreshAndRelease() = runBlocking {
        storage.replaceClipDocuments(1, listOf(lexical(1)))
        storage.replaceSemanticSource(1, SemanticSourceType.TEXT, "s1", listOf(semantic(1)))
        storage.replaceSemanticSource(2, SemanticSourceType.TEXT, "s2", listOf(semantic(2)))
        storage.replaceImageEmbedding(10, 1, "i1", imageBlob())
        storage.replaceImageEmbedding(20, 3, "i2", imageBlob())
        val queries = Fts5LiteralQueryBuilder.build(representation("abcd"))
        assertEquals(listOf(1L), storage.searchNormal(queries.normal.single()).map { it.clipId })
        assertEquals(listOf(1L), storage.searchTrigram(queries.trigram.single()).map { it.clipId })
        val owned = mutableListOf<TrackingAnn>()
        val engine = LocalSearchEngine(StoredLocalSearchDataSource(storage),
            LexicalTextAnalyzer { SudachiLexicalTextAnalysis(it, it, it, searchCompact(it)) },
            QueryEmbedder { TextEmbedding.fromModelOutput(vector(768)) },
            MultimodalTextEmbedder { MultimodalEmbedding.fromModelOutput(vector(256)) },
            SearchAnnFactory { dimension, entries ->
                TrackingAnn(dimension, NativeSearchAnnFactory.build(dimension, entries)).also(owned::add)
            })
        try {
            val first = engine.search("abcd")
            assertEquals(setOf(1L, 2L, 3L), first.map { it.clipId }.toSet())
            assertEquals(first.size, first.map { it.clipId }.distinct().size)
            assertTrue(first.all { it.score.isFinite() })
            assertTrue(first.zipWithNext().all { (a, b) -> a.score >= b.score })
            repeat(6) { assertEquals(first, engine.search("abcd")) }
            assertEquals(listOf(768, 256), owned.map { it.dimension })
            storage.deleteClipDocuments(1)
            storage.deleteSemanticClip(2)
            storage.deleteImageEmbedding(20)
            val second = engine.search("abcd")
            assertEquals(listOf(1L), second.map { it.clipId })
            assertEquals(4, owned.size)
            assertTrue(owned.take(2).all { it.closed })
            assertTrue(owned.drop(2).none { it.closed })
            repeat(6) { assertEquals(second, engine.search("abcd")) }
            // Literal syntax coverage uses only fixture data, not linguistic hit/quality assertions.
            listOf("AND OR NOT NEAR * ( ) : -", "\"", "a\"b", "https://x.invalid/a:b", "日\n本", "ＡＢＣ", "😀", "ab")
                .forEach { query -> assertTrue(engine.search(query).all { it.score.isFinite() }) }
        } finally { engine.close(); engine.close() }
        assertTrue(owned.all { it.closed })
        owned.forEach { assertTrue(runCatching { it.delegate.search(vector(it.dimension), 1) }.isFailure) }
        assertTrue(runCatching { engine.search("abcd") }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun revisionsTrackSuccessfulMutationsRollbackAndRecoveryWithoutSchemaChanges() = runBlocking {
        suspend fun revisions() = listOf(storage.getLexicalSnapshot().revision,
            storage.getSemanticSnapshot()!!.revision, storage.getImageSnapshot()!!.revision)
        val before = revisions()
        storage.replaceClipDocuments(1, listOf(lexical(1)))
        assertEquals(listOf(before[0] + 1, before[1], before[2]), revisions())
        storage.replaceSemanticSource(1, SemanticSourceType.TEXT, "s", listOf(semantic(1)))
        storage.replaceImageEmbedding(1, 1, "i", imageBlob())
        val saved = revisions()
        assertNull(storage.getSemanticSnapshot(saved[1]))
        assertNull(storage.getImageSnapshot(saved[2]))
        // Fail inside each transaction, after deletion/upsert begins; rollback must not advance revisions.
        storage.close()
        fixtureSql("CREATE TRIGGER fail_lexical BEFORE INSERT ON lexical_documents BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        fixtureSql("CREATE TRIGGER fail_semantic BEFORE INSERT ON semantic_documents BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        fixtureSql("CREATE TRIGGER fail_image BEFORE INSERT ON image_embeddings BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { storage.replaceClipDocuments(1, listOf(lexical(1))) }.isFailure)
        assertTrue(runCatching { storage.replaceSemanticSource(1, SemanticSourceType.TEXT, "s2", listOf(semantic(1))) }.isFailure)
        assertTrue(runCatching { storage.replaceImageEmbedding(1, 1, "i2", imageBlob()) }.isFailure)
        assertEquals(saved, revisions())
        assertEquals("i", storage.getImageEmbedding(1)!!.sourceFingerprint)
        assertEquals(1, storage.getAllLexicalDocuments().size)
        storage.close()
        fixtureSql("DROP TRIGGER fail_lexical"); fixtureSql("DROP TRIGGER fail_semantic"); fixtureSql("DROP TRIGGER fail_image")
        storage.deleteClipDocuments(1)
        storage.deleteSemanticSource(1, SemanticSourceType.TEXT)
        storage.deleteImageEmbedding(1)
        assertEquals(saved.map { it + 1 }, revisions())
        storage.deleteSemanticClip(1); storage.deleteImageEmbeddingsForClip(1)
        val deleted = revisions()
        assertEquals(listOf(saved[0] + 1, saved[1] + 2, saved[2] + 2), deleted)
        storage.clear()
        assertEquals(deleted.map { it + 1 }, revisions())
        val cleared = revisions()
        storage.close()
        fixtureSql("PRAGMA user_version = 999")
        assertEquals(cleared.map { it + 1 }, revisions())
        assertTrue(storage.getAllLexicalDocuments().isEmpty())
    }

    @Test fun lexicalSnapshotUsesCorpusSizedLimitsAndAtomicCacheRevision() = runBlocking {
        for (id in 1L..105L) storage.replaceClipDocuments(id, listOf(lexical(id)))
        val query = Fts5LiteralQueryBuilder.build(representation("abcd"))
        val first = storage.retrieveLexical(null, query)
        assertEquals(105, first.candidateDocumentIds.size)
        assertEquals(first.documents!!.map { it.documentId }.toSet(), first.candidateDocumentIds)
        val cached = storage.retrieveLexical(first.revision, query)
        assertNull(cached.documents)
        assertEquals(first.candidateDocumentIds, cached.candidateDocumentIds)
        storage.deleteClipDocuments(1)
        val updated = storage.retrieveLexical(first.revision, query)
        assertEquals(first.revision + 1, updated.revision)
        assertEquals(104, updated.documents!!.size)
        assertEquals(updated.documents.map { it.documentId }.toSet(), updated.candidateDocumentIds)
    }

    private fun fixtureSql(sql: String) {
        check(storage.databasePath.canonicalPath.startsWith(root.canonicalPath + File.separator))
        BundledSQLiteDriver().open(storage.databasePath.absolutePath).use { connection ->
            connection.prepare(sql).use { it.step() }
        }
    }

    private class TrackingAnn(val dimension: Int, val delegate: SearchAnn) : SearchAnn {
        var closed = false
        override suspend fun search(query: FloatArray, candidateCount: Int) = delegate.search(query, candidateCount)
        override fun close() { check(!closed); closed = true; delegate.close() }
    }

    private fun representation(text: String) = SearchRepresentation(text, text, text, text, text)
    private fun lexical(id: Long) = LexicalDocument("$id", id, "text", 0, "abcd", "abcd", "abcd", "abcd", "abcd")
    private fun semantic(id: Long) = SemanticDocument(SemanticTextChunker.documentId(id, SemanticSourceType.TEXT, 0),
        id, SemanticSourceType.TEXT, 0, vector(768))
    private fun imageBlob() = ImageEmbeddingBlobCodec.encode(vector(256))
    private fun vector(dimension: Int) = FloatArray(dimension) { if (it == 0) 1f else 0f }
}
