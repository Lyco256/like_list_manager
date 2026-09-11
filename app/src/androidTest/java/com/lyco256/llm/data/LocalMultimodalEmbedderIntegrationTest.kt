package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMultimodalEmbedderIntegrationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun realQ4f16CpuRuntimeLazilyLoadsAndReusesIndependentTextAndVisionSessions() = runBlocking {
        assertTestHarness()
        val before = snapshotProtectedData()
        val embedder = LocalMultimodalEmbedder(context)
        try {
            assertEquals(0, embedder.assetInstallationCountForTest)
            assertEquals(0, embedder.tokenizerInitializationCountForTest)
            assertEquals(0, embedder.textSessionInitializationCountForTest)
            assertEquals(0, embedder.visionSessionInitializationCountForTest)

            val textEmbedding = embedder.embedText("日本語のローカル検索テスト")
            assertEmbedding(textEmbedding)
            assertEquals(1, embedder.assetInstallationCountForTest)
            assertEquals(1, embedder.tokenizerInitializationCountForTest)
            assertEquals(1, embedder.textSessionInitializationCountForTest)
            assertEquals(0, embedder.visionSessionInitializationCountForTest)
            assertEquals(setOf("input_ids", "attention_mask", "position_ids"), embedder.textInputNamesForTest)
            assertEquals(1, embedder.textOutputNamesForTest.size)

            assertEmbedding(embedder.embedText("再利用される同じテキストセッション"))
            assertEquals(1, embedder.assetInstallationCountForTest)
            assertEquals(1, embedder.tokenizerInitializationCountForTest)
            assertEquals(1, embedder.textSessionInitializationCountForTest)

            val bitmap = solidBitmap(3, 2, Color.rgb(32, 128, 224))
            try {
                val imageEmbedding = embedder.embedImage(bitmap)
                assertEmbedding(imageEmbedding)
            } finally {
                bitmap.recycle()
            }
            assertEquals(1, embedder.assetInstallationCountForTest)
            assertEquals(1, embedder.visionSessionInitializationCountForTest)
            assertEquals(setOf("pixel_values"), embedder.visionInputNamesForTest)
            assertEquals(1, embedder.visionOutputNamesForTest.size)
            assertInstalledAssetsMatchFixedSpec()

            val secondBitmap = solidBitmap(1, 1, Color.WHITE)
            try {
                assertEmbedding(embedder.embedImage(secondBitmap))
            } finally {
                secondBitmap.recycle()
            }
            assertEquals(1, embedder.visionSessionInitializationCountForTest)
        } finally {
            embedder.close()
            embedder.close()
        }
        assertProtectedDataUnchanged(before)
        assertTrue(runCatching { embedder.embedText("closed") }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun textOnlyAndImageOnlyCallsDoNotInitializeTheOtherModality() = runBlocking {
        assertTestHarness()
        val textOnly = LocalMultimodalEmbedder(context)
        try {
            assertEmbedding(textOnly.embedText("text only"))
            assertEquals(1, textOnly.textSessionInitializationCountForTest)
            assertEquals(0, textOnly.visionSessionInitializationCountForTest)
        } finally {
            textOnly.close()
        }

        val imageOnly = LocalMultimodalEmbedder(context)
        val bitmap = solidBitmap(224, 224, Color.BLACK)
        try {
            assertEmbedding(imageOnly.embedImage(bitmap))
            assertEquals(0, imageOnly.tokenizerInitializationCountForTest)
            assertEquals(0, imageOnly.textSessionInitializationCountForTest)
            assertEquals(1, imageOnly.visionSessionInitializationCountForTest)
        } finally {
            bitmap.recycle()
            imageOnly.close()
        }
    }

    @Test
    fun concurrentTextAndImageCallsAreSerializedWithoutUnboundedWork() = runBlocking {
        assertTestHarness()
        val embedder = LocalMultimodalEmbedder(context)
        try {
            val results = withContext(Dispatchers.Default) {
                (0 until 8).map { index ->
                    async {
                        if (index % 2 == 0) {
                            embedder.embedText("同時実行テスト $index " + "長い入力 ".repeat(100))
                        } else {
                            val bitmap = solidBitmap(4 + index, 3, Color.WHITE)
                            try {
                                embedder.embedImage(bitmap)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }.awaitAll()
            }
            results.forEach(::assertEmbedding)
            assertEquals(1, embedder.assetInstallationCountForTest)
            assertEquals(1, embedder.tokenizerInitializationCountForTest)
            assertEquals(1, embedder.textSessionInitializationCountForTest)
            assertEquals(1, embedder.visionSessionInitializationCountForTest)
        } finally {
            embedder.close()
        }
    }

    @Test
    fun badInputAndAssetRecoveryFailClosedWithoutChangingProtectedData() = runBlocking {
        assertTestHarness()
        val before = snapshotProtectedData()
        val blank = LocalMultimodalEmbedder(context)
        try {
            assertTrue(runCatching { blank.embedText("  \n") }.exceptionOrNull() is IllegalArgumentException)
            val recycled = solidBitmap(1, 1, Color.WHITE)
            recycled.recycle()
            assertTrue(runCatching { blank.embedImage(recycled) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, blank.assetInstallationCountForTest)
        } finally {
            blank.close()
        }

        val seed = LocalMultimodalEmbedder(context)
        try {
            assertEmbedding(seed.embedText("asset recovery seed"))
        } finally {
            seed.close()
        }
        val modelDirectory = modelDirectory()
        val tokenizer = File(modelDirectory, JapaneseClipModelSpec.TOKENIZER_FILE)
        assertTrue(tokenizer.isFile)
        val originalTokenizer = tokenizer.readBytes()
        tokenizer.writeText("corrupt-tokenizer")
        val recovered = LocalMultimodalEmbedder(context)
        try {
            assertEmbedding(recovered.embedText("復旧後の入力"))
            assertEquals(1, recovered.assetInstallationCountForTest)
        } finally {
            recovered.close()
        }
        assertEquals(sha256(originalTokenizer), sha256(tokenizer.readBytes()))
        assertFalse(modelDirectory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
        assertProtectedDataUnchanged(before)
    }

    @Test
    fun imagePreprocessingUsesBlackCenteredAspectFitAndPreservesInputBitmap() {
        assertTestHarness()
        val blackRed = (0f - JapaneseClipModelSpec.IMAGE_MEAN[0]) / JapaneseClipModelSpec.IMAGE_STD[0]
        val blackGreen = (0f - JapaneseClipModelSpec.IMAGE_MEAN[1]) / JapaneseClipModelSpec.IMAGE_STD[1]
        val blackBlue = (0f - JapaneseClipModelSpec.IMAGE_MEAN[2]) / JapaneseClipModelSpec.IMAGE_STD[2]
        val source = solidBitmap(2, 1, Color.RED)
        try {
            val values = preprocessJapaneseClipImage(source)
            val planeSize = JapaneseClipModelSpec.IMAGE_SIZE * JapaneseClipModelSpec.IMAGE_SIZE
            assertEquals(planeSize * 3, values.size)
            val redRed = (1f - JapaneseClipModelSpec.IMAGE_MEAN[0]) / JapaneseClipModelSpec.IMAGE_STD[0]
            assertEquals(blackRed, values[0], 0.0001f)
            assertEquals(redRed, values[112 * JapaneseClipModelSpec.IMAGE_SIZE + 112], 0.0001f)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }

        val transparent = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also {
            it.setPixel(0, 0, Color.TRANSPARENT)
        }
        try {
            val values = preprocessJapaneseClipImage(transparent)
            val planeSize = JapaneseClipModelSpec.IMAGE_SIZE * JapaneseClipModelSpec.IMAGE_SIZE
            assertEquals(blackRed, values[planeSize / 2], 0.0001f)
            assertEquals(blackGreen, values[planeSize + planeSize / 2], 0.0001f)
            assertEquals(blackBlue, values[planeSize * 2 + planeSize / 2], 0.0001f)
        } finally {
            transparent.recycle()
        }

        val portrait = solidBitmap(1, 2, Color.GREEN)
        try {
            val values = preprocessJapaneseClipImage(portrait)
            val planeSize = JapaneseClipModelSpec.IMAGE_SIZE * JapaneseClipModelSpec.IMAGE_SIZE
            val greenGreen = (1f - JapaneseClipModelSpec.IMAGE_MEAN[1]) / JapaneseClipModelSpec.IMAGE_STD[1]
            assertEquals(blackGreen, values[planeSize + 112 * JapaneseClipModelSpec.IMAGE_SIZE], 0.0001f)
            assertEquals(greenGreen, values[planeSize + 112 * JapaneseClipModelSpec.IMAGE_SIZE + 112], 0.0001f)
            assertFalse(portrait.isRecycled)
            assertEquals(planeSize * 3, values.size)
        } finally {
            portrait.recycle()
        }

        val square = solidBitmap(2, 2, Color.BLUE)
        try {
            val values = preprocessJapaneseClipImage(square)
            val planeSize = JapaneseClipModelSpec.IMAGE_SIZE * JapaneseClipModelSpec.IMAGE_SIZE
            val blueBlue = (1f - JapaneseClipModelSpec.IMAGE_MEAN[2]) / JapaneseClipModelSpec.IMAGE_STD[2]
            assertEquals(blueBlue, values[112 * JapaneseClipModelSpec.IMAGE_SIZE + 112 + planeSize * 2], 0.0001f)
            assertFalse(square.isRecycled)
        } finally {
            square.recycle()
        }
    }

    private fun assertTestHarness() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
    }

    private fun modelDirectory(): File = File(
        context.noBackupFilesDir,
        "multimodal_embedding/japanese_clip/${JapaneseClipModelSpec.REVISION}",
    )

    private fun assertInstalledAssetsMatchFixedSpec() {
        val expectedSizes = mapOf(
            JapaneseClipModelSpec.TEXT_MODEL_FILE to 94_564_770L,
            JapaneseClipModelSpec.VISION_MODEL_FILE to 47_651_034L,
            JapaneseClipModelSpec.TOKENIZER_FILE to 2_411_619L,
        )
        JapaneseClipModelSpec.assets.forEach { asset ->
            val file = File(modelDirectory(), asset.name)
            assertTrue(file.isFile)
            assertEquals(expectedSizes.getValue(asset.name), file.length())
            assertEquals(asset.sha256, sha256(file))
        }
    }

    private data class ProtectedDataSnapshot(
        val database: Map<String, String>,
        val images: Map<String, String>,
        val undo: Map<String, String>,
        val derived: Map<String, String>,
    )

    private fun snapshotProtectedData(): ProtectedDataSnapshot {
        val database = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
        return ProtectedDataSnapshot(
            database = snapshotTree(database.parentFile, database.name),
            images = snapshotTree(File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY)),
            undo = snapshotTree(File(context.filesDir, "clip_delete_undo_staging")),
            derived = snapshotTree(File(context.noBackupFilesDir, DerivedSearchStorage.DIRECTORY_NAME)),
        )
    }

    private fun assertProtectedDataUnchanged(before: ProtectedDataSnapshot) {
        assertEquals(before, snapshotProtectedData())
    }

    private fun snapshotTree(root: File?, onlyFileName: String? = null): Map<String, String> {
        if (root == null || !root.exists()) return emptyMap()
        return root.walkTopDown()
            .filter(File::isFile)
            .filter { onlyFileName == null || it.name == onlyFileName }
            .associate { file ->
                file.relativeTo(root).path to "${file.length()}:${sha256(file.readBytes())}"
            }
    }

    private fun assertEmbedding(embedding: MultimodalEmbedding) {
        assertEquals(JapaneseClipModelSpec.EMBEDDING_DIMENSION, embedding.dimension)
        val values = embedding.toFloatArray()
        assertTrue(values.all(Float::isFinite))
        val norm = kotlin.math.sqrt(values.sumOf { it.toDouble() * it.toDouble() })
        assertEquals(1.0, norm, 0.0001)
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color)
        }

    private fun sha256(fileBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(fileBytes).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
