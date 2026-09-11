package com.lyco256.llm.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMultimodalEmbedderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun modelOutputIsValidatedAndReturnedAsAnImmutableNormalizedVector() {
        val values = FloatArray(JapaneseClipModelSpec.EMBEDDING_DIMENSION) { 2f }
        val embedding = MultimodalEmbedding.fromModelOutput(values)

        values[0] = 0f
        assertEquals(JapaneseClipModelSpec.EMBEDDING_DIMENSION, embedding.dimension)
        assertEquals(
            1f / kotlin.math.sqrt(JapaneseClipModelSpec.EMBEDDING_DIMENSION.toFloat()),
            embedding[0],
            0.00001f,
        )
        val copy = embedding.toFloatArray()
        copy[1] = 0f
        assertEquals(embedding[1], 1f / kotlin.math.sqrt(JapaneseClipModelSpec.EMBEDDING_DIMENSION.toFloat()), 0.00001f)

        assertTrue(runCatching { MultimodalEmbedding.fromModelOutput(FloatArray(255)) }.isFailure)
        assertTrue(runCatching { MultimodalEmbedding.fromModelOutput(FloatArray(256) { Float.NaN }) }.isFailure)
        assertTrue(runCatching { MultimodalEmbedding.fromModelOutput(FloatArray(256)) }.isFailure)
    }

    @Test
    fun tokenInputsPrependClsAndUseOneBasedLengthIndependentPositions() {
        val inputs = buildJapaneseClipTokenInputs(longArrayOf(10L, 11L, 12L))
        val maximumInputs = buildJapaneseClipTokenInputs(
            LongArray(JapaneseClipModelSpec.MAX_TOKENIZER_TOKEN_LENGTH) { it.toLong() + 10L },
        )

        assertArrayEquals(longArrayOf(4L, 10L, 11L, 12L), inputs.inputIds)
        assertArrayEquals(longArrayOf(1L, 1L, 1L, 1L), inputs.attentionMask)
        assertArrayEquals(longArrayOf(0L, 1L, 2L, 3L), inputs.positionIds)
        assertEquals(JapaneseClipModelSpec.MAX_TEXT_TOKEN_LENGTH, maximumInputs.inputIds.size)
        assertEquals(JapaneseClipModelSpec.MAX_TEXT_TOKEN_LENGTH - 1L, maximumInputs.positionIds.last())
        assertTrue(
            runCatching {
                buildJapaneseClipTokenInputs(LongArray(JapaneseClipModelSpec.MAX_TOKENIZER_TOKEN_LENGTH + 1))
            }.isFailure,
        )
    }

    @Test
    fun installerVerifiesAndReusesAllAssetsWithoutRecopying() {
        val fixture = fixture()
        val source = FakeJapaneseClipAssets(fixture)
        val directory = temporaryFolder.newFolder("japanese-clip")
        val outsideFile = File(directory.parentFile, "outside.txt").apply { writeText("must survive") }
        val installer = fixture.installer(source, directory)

        installer.install()
        installer.install()

        fixture.files.forEach { file ->
            assertEquals(1, source.openCounts.getValue(file.name))
            assertArrayEquals(file.bytes, File(directory, file.name).readBytes())
        }
        assertTrue(outsideFile.isFile)
        assertEquals("must survive", outsideFile.readText())
        assertFalse(File(directory, ".text_model_q4f16.onnx.partial").exists())
        assertEquals(2, source.openCounts.getValue("metadata.json"))
    }

    @Test
    fun installerRepairsOnlyTheCorruptAsset() {
        val fixture = fixture()
        val source = FakeJapaneseClipAssets(fixture)
        val directory = temporaryFolder.newFolder("japanese-clip-repair")
        val installer = fixture.installer(source, directory)
        installer.install()

        File(directory, JapaneseClipModelSpec.TOKENIZER_FILE).writeText("corrupt")
        installer.install()

        assertEquals(1, source.openCounts.getValue(JapaneseClipModelSpec.TEXT_MODEL_FILE))
        assertEquals(1, source.openCounts.getValue(JapaneseClipModelSpec.VISION_MODEL_FILE))
        assertEquals(2, source.openCounts.getValue(JapaneseClipModelSpec.TOKENIZER_FILE))
        assertArrayEquals(
            fixture.file(JapaneseClipModelSpec.TOKENIZER_FILE).bytes,
            File(directory, JapaneseClipModelSpec.TOKENIZER_FILE).readBytes(),
        )
    }

    @Test
    fun interruptedCopyIsRemovedAndCopyFailureLeavesNoFinalOrPartialFile() {
        val fixture = fixture()
        val source = FakeJapaneseClipAssets(fixture)
        val directory = temporaryFolder.newFolder("japanese-clip-interrupted")
        File(directory, ".${JapaneseClipModelSpec.TEXT_MODEL_FILE}.partial").writeText("partial")
        fixture.installer(source, directory).install()
        assertFalse(File(directory, ".${JapaneseClipModelSpec.TEXT_MODEL_FILE}.partial").exists())

        val failureDirectory = temporaryFolder.newFolder("japanese-clip-failure")
        val failingSource = FakeJapaneseClipAssets(
            fixture,
            failingFile = JapaneseClipModelSpec.VISION_MODEL_FILE,
        )
        val failure = runCatching { fixture.installer(failingSource, failureDirectory).install() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(File(failureDirectory, JapaneseClipModelSpec.VISION_MODEL_FILE).exists())
        assertFalse(File(failureDirectory, ".${JapaneseClipModelSpec.VISION_MODEL_FILE}.partial").exists())
    }

    private fun fixture(): Fixture {
        val files = listOf(
            JapaneseClipModelSpec.TEXT_MODEL_FILE to "text-model".encodeToByteArray(),
            JapaneseClipModelSpec.VISION_MODEL_FILE to "vision-model".encodeToByteArray(),
            JapaneseClipModelSpec.TOKENIZER_FILE to "tokenizer".encodeToByteArray(),
        ).map { (name, bytes) -> FixtureFile(name, bytes, sha256(bytes)) }
        return Fixture(files)
    }

    private class Fixture(
        val files: List<FixtureFile>,
    ) {
        fun file(name: String): FixtureFile = files.first { it.name == name }

        fun installer(source: FakeJapaneseClipAssets, directory: File): JapaneseClipAssetInstaller =
            JapaneseClipAssetInstaller(
                source = source,
                modelDirectory = directory,
                expectedAssets = files.map { JapaneseClipModelSpec.Asset(it.name, it.sha256) },
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

    private class FakeJapaneseClipAssets(
        private val fixture: Fixture,
        private val failingFile: String? = null,
    ) : JapaneseClipAssetSource {
        val openCounts = mutableMapOf<String, Int>().withDefault { 0 }

        override fun open(path: String): InputStream {
            val name = path.substringAfterLast('/')
            openCounts[name] = openCounts.getValue(name) + 1
            if (name == "metadata.json") {
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
            if (name != failingFile) return ByteArrayInputStream(file.bytes)
            return object : ByteArrayInputStream(file.bytes) {
                private var failed = false

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (failed) throw IOException("simulated asset read failure")
                    failed = true
                    return super.read(buffer, offset, length)
                }
            }
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
