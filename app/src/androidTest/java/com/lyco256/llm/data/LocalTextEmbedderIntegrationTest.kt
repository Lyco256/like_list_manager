package com.lyco256.llm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lyco256.llm.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextEmbedderIntegrationTest {
    @Test
    fun realBundledEmbeddingGemmaRunsLocallyRepeatedlyConcurrentlyAndCloses() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val protectedBefore = protectedDataManifest(context)
        val embedder = LocalTextEmbedder(context)

        try {
            val query = embedder.embedQuery("日本語の検索クエリとOCR記号: #タグ https://example.test/a?b=1")
            val document = embedder.embedDocument("An English document with numbers 123 and line\nbreaks.")
            val truncated = embedder.embedDocument(buildString { repeat(2_200) { append("long-token-$it ") } })
            val concurrent = coroutineScope {
                (0 until 4).map { index ->
                    async(Dispatchers.Default) {
                        if (index % 2 == 0) {
                            embedder.embedQuery("同時実行 $index")
                        } else {
                            embedder.embedDocument("Concurrent document $index")
                        }
                    }
                }.awaitAll()
            }

            listOf(query, document, truncated).plus(concurrent).forEach(::assertStructuralEmbedding)
            assertEquals(1, embedder.initializationCountForTest)
            assertEquals(setOf("input_ids", "attention_mask"), embedder.inputNamesForTest)
            assertTrue("sentence_embedding" in embedder.outputNamesForTest)
            assertBundledFilesAreVerified(context)
        } finally {
            embedder.close()
            embedder.close()
        }

        assertTrue(runCatching { embedder.embedQuery("after close") }.exceptionOrNull() is IllegalStateException)
        assertEquals(protectedBefore, protectedDataManifest(context))
    }

    private fun assertStructuralEmbedding(embedding: TextEmbedding) {
        val values = embedding.toFloatArray()
        assertEquals(EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION, values.size)
        assertTrue(values.all { it.isFinite() })
        val norm = kotlin.math.sqrt(values.sumOf { it.toDouble() * it.toDouble() })
        assertEquals(1.0, norm, 0.0001)
    }

    private fun assertBundledFilesAreVerified(context: Context) {
        val modelDirectory = File(
            context.noBackupFilesDir,
            "text_embedding/embeddinggemma/${EmbeddingGemmaModelSpec.REVISION}",
        )
        assertTrue(modelDirectory.isDirectory)
        val metadata = context.assets.open(
            "${EmbeddingGemmaModelSpec.ASSET_ROOT}/${EmbeddingGemmaModelSpec.METADATA_FILE}",
        ).bufferedReader().use { JSONObject(it.readText()) }
        val files = metadata.getJSONArray("files")
        assertEquals(EmbeddingGemmaModelSpec.assets.size, files.length())
        for (index in 0 until files.length()) {
            val item = files.getJSONObject(index)
            val file = File(modelDirectory, item.getString("name"))
            assertTrue(file.isFile)
            assertEquals(item.getLong("byteSize"), file.length())
            assertEquals(item.getString("sha256"), sha256(file))
        }
        assertFalse(modelDirectory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    private fun protectedDataManifest(context: Context): Map<String, String> {
        val database = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
        val roots = listOf(
            database,
            File(database.path + "-wal"),
            File(database.path + "-shm"),
            File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY),
            File(context.filesDir, "clip_delete_undo_staging"),
            File(context.noBackupFilesDir, "derived_search"),
        )
        return roots.flatMap { root ->
            if (root.isFile) listOf(root) else root.walkTopDown().filter(File::isFile).toList()
        }.distinctBy { it.getCanonicalPath() }.associate { file ->
            file.getCanonicalPath() to "${file.length()}:${sha256(file)}"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
