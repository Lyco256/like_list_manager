package com.lyco256.llm.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class LocalTextEmbedderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun promptsUseTheFixedRetrievalFormats() {
        assertEquals(
            "task: search result | query: 日本語 query",
            EmbeddingPrompts.query("日本語 query"),
        )
        assertEquals(
            "title: none | text: document text",
            EmbeddingPrompts.document("document text"),
        )
    }

    @Test
    fun blankInputIsRejectedBeforeAnyRuntimeWork() = runBlocking {
        var opened = false
        val source = object : EmbeddingAssetSource {
            override fun open(path: String): InputStream {
                opened = true
                error("blank input must be rejected before opening assets")
            }
        }
        val embedder = LocalTextEmbedder(
            source,
            temporaryFolder.newFolder("blank-input"),
            Dispatchers.IO,
        )

        val failure = runCatching { embedder.embedQuery("   ") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(opened)
    }

    @Test
    fun modelOutputIsValidatedAndReturnedAsAnImmutableNormalizedVector() {
        val values = FloatArray(EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION) { 2f }
        val embedding = TextEmbedding.fromModelOutput(values)

        values[0] = 0f
        assertEquals(EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION, embedding.dimension)
        assertEquals(1f / kotlin.math.sqrt(EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION.toFloat()), embedding[0], 0.00001f)
        val copy = embedding.toFloatArray()
        copy[1] = 0f
        assertEquals(embedding[1], 1f / kotlin.math.sqrt(EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION.toFloat()), 0.00001f)
    }

    @Test
    fun installerVerifiesAndReusesAllAssetsWithoutRecopying() {
        val fixture = fixture()
        val source = FakeEmbeddingAssets(fixture)
        val directory = temporaryFolder.newFolder("embedding")
        val installer = fixture.installer(source, directory)

        installer.install()
        installer.install()

        fixture.files.forEach { file ->
            assertEquals(1, source.openCounts.getValue(file.name))
            assertEquals(file.bytes.toList(), File(directory, file.name).readBytes().toList())
        }
        assertFalse(File(directory, ".model_q4.onnx.partial").exists())
        assertEquals(2, source.openCounts.getValue(EmbeddingGemmaModelSpec.METADATA_FILE))
    }

    @Test
    fun installerRepairsOnlyTheCorruptAsset() {
        val fixture = fixture()
        val source = FakeEmbeddingAssets(fixture)
        val directory = temporaryFolder.newFolder("embedding-repair")
        val installer = fixture.installer(source, directory)
        installer.install()

        File(directory, EmbeddingGemmaModelSpec.MODEL_EXTERNAL_DATA_FILE).writeText("corrupt")
        installer.install()

        assertEquals(1, source.openCounts.getValue(EmbeddingGemmaModelSpec.MODEL_FILE))
        assertEquals(2, source.openCounts.getValue(EmbeddingGemmaModelSpec.MODEL_EXTERNAL_DATA_FILE))
        assertEquals(1, source.openCounts.getValue(EmbeddingGemmaModelSpec.TOKENIZER_FILE))
        assertEquals(
            fixture.file(EmbeddingGemmaModelSpec.MODEL_EXTERNAL_DATA_FILE).bytes.toList(),
            File(directory, EmbeddingGemmaModelSpec.MODEL_EXTERNAL_DATA_FILE).readBytes().toList(),
        )
    }

    @Test
    fun interruptedCopyIsRemovedAndCopyFailureLeavesNoFinalOrPartialFile() {
        val fixture = fixture()
        val source = FakeEmbeddingAssets(fixture)
        val directory = temporaryFolder.newFolder("embedding-interrupted")
        File(directory, ".model_q4.onnx.partial").writeText("partial")
        val installer = fixture.installer(source, directory)
        installer.install()
        assertFalse(File(directory, ".model_q4.onnx.partial").exists())

        val failureDirectory = temporaryFolder.newFolder("embedding-failure")
        val failingSource = FakeEmbeddingAssets(fixture, failingFile = EmbeddingGemmaModelSpec.MODEL_EXTERNAL_DATA_FILE)
        val failure = runCatching { fixture.installer(failingSource, failureDirectory).install() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(File(failureDirectory, EmbeddingGemmaModelSpec.MODEL_EXTERNAL_DATA_FILE).exists())
        assertFalse(File(failureDirectory, ".${EmbeddingGemmaModelSpec.MODEL_EXTERNAL_DATA_FILE}.partial").exists())
    }

    private fun fixture(): Fixture {
        val files = listOf(
            EmbeddingGemmaModelSpec.MODEL_FILE to "model".encodeToByteArray(),
            EmbeddingGemmaModelSpec.MODEL_EXTERNAL_DATA_FILE to "external-data".encodeToByteArray(),
            EmbeddingGemmaModelSpec.TOKENIZER_FILE to "tokenizer".encodeToByteArray(),
        ).map { (name, bytes) -> FixtureFile(name, bytes, sha256(bytes)) }
        return Fixture(files)
    }

    private class Fixture(
        val files: List<FixtureFile>,
    ) {
        fun file(name: String): FixtureFile = files.first { it.name == name }

        fun installer(source: FakeEmbeddingAssets, directory: java.io.File): EmbeddingGemmaAssetInstaller =
            EmbeddingGemmaAssetInstaller(
                source = source,
                modelDirectory = directory,
                expectedAssets = files.map { EmbeddingGemmaModelSpec.Asset(it.name, it.sha256) },
                expectedRepository = "fixture-repository",
                expectedRevision = "fixture-revision",
                assetRoot = "fixture-root",
            )
    }

    private data class FixtureFile(
        val name: String,
        val bytes: ByteArray,
        val sha256: String,
    )

    private class FakeEmbeddingAssets(
        private val fixture: Fixture,
        private val failingFile: String? = null,
    ) : EmbeddingAssetSource {
        val openCounts = mutableMapOf<String, Int>().withDefault { 0 }

        override fun open(path: String): InputStream {
            val name = path.substringAfterLast('/')
            openCounts[name] = openCounts.getValue(name) + 1
            if (name == EmbeddingGemmaModelSpec.METADATA_FILE) {
                val json = buildString {
                    append("{\"repository\":\"fixture-repository\",\"revision\":\"fixture-revision\",\"files\":[")
                    fixture.files.forEachIndexed { index, file ->
                        if (index > 0) append(',')
                        append("{\"name\":\"${file.name}\",\"sha256\":\"${file.sha256}\",\"byteSize\":${file.bytes.size}}")
                    }
                    append("]}")
                }
                return ByteArrayInputStream(json.encodeToByteArray())
            }
            val file = fixture.file(name)
            if (name == failingFile) throw IOException("simulated asset read failure")
            return ByteArrayInputStream(file.bytes)
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
