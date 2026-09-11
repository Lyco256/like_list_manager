package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageEmbeddingBitmapDecoderIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.cacheDir, "image-embedding-decoder-test")
        directory.deleteRecursively()
        directory.mkdirs()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun largeLandscapeAndPortraitImagesAreDecodedWithBoundedDimensions() {
        val decoder = LocalImageEmbeddingBitmapDecoder()
        val landscape = writeBitmap("landscape.png", 1200, 600)
        val portrait = writeBitmap("portrait.png", 600, 1200)

        val landscapeDecoded = decoder.decode(landscape)
        val portraitDecoded = decoder.decode(portrait)
        try {
            assertTrue(maxOf(landscapeDecoded.width, landscapeDecoded.height) in 224..447)
            assertTrue(maxOf(portraitDecoded.width, portraitDecoded.height) in 224..447)
        } finally {
            landscapeDecoded.recycle()
            portraitDecoded.recycle()
        }
    }

    @Test
    fun smallImagesAreNotUpscaledAndCorruptFilesAreRejected() {
        val decoder = LocalImageEmbeddingBitmapDecoder()
        val small = writeBitmap("small.webp", 40, 20, Bitmap.CompressFormat.WEBP_LOSSY)
        val corrupt = File(directory, "corrupt.bin").apply { writeText("not an image") }
        val originalBytes = small.readBytes()
        val originalLastModified = small.lastModified()

        val decoded = decoder.decode(small)
        try {
            assertEquals(40, decoded.width)
            assertEquals(20, decoded.height)
        } finally {
            decoded.recycle()
        }
        assertEquals(originalBytes.toList(), small.readBytes().toList())
        assertEquals(originalLastModified, small.lastModified())

        assertTrue(runCatching { decoder.decode(corrupt) }.isFailure)
    }

    @Test
    fun sampleSizeUsesPowersOfTwoForTheRequestedBound() = runBlocking {
        assertEquals(1, LocalImageEmbeddingBitmapDecoder.sampleSizeFor(224, 100, 224))
        assertEquals(2, LocalImageEmbeddingBitmapDecoder.sampleSizeFor(448, 100, 224))
        assertEquals(4, LocalImageEmbeddingBitmapDecoder.sampleSizeFor(896, 100, 224))
        assertEquals(1, LocalImageEmbeddingBitmapDecoder.sampleSizeFor(447, 100, 224))
    }

    private fun writeBitmap(
        name: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val file = File(directory, name)
            file.outputStream().use { output -> check(bitmap.compress(format, 90, output)) }
            file
        } finally {
            bitmap.recycle()
        }
    }
}
