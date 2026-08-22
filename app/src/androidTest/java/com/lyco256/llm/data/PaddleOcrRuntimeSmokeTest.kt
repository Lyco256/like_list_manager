package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleOcrRuntimeSmokeTest {
    @Test
    fun bundledModelsInitializeAndRunOnDevice() = runBlocking {
        val gateway = PaddleOcrTextGateway(ApplicationProvider.getApplicationContext())
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        try {
            val small = gateway.recognizeForComparison(bitmap, OcrEngine.PP_OCRV6_SMALL)
            val medium = gateway.recognizeForComparison(bitmap, OcrEngine.PP_OCRV6_MEDIUM)
            val tiled = gateway.recognizeForComparison(bitmap, OcrEngine.PP_OCRV6_MEDIUM_TILE)
            assertEquals(64, small.recognition.imageWidth)
            assertEquals(64, medium.recognition.imageHeight)
            assertEquals(1, tiled.tileCount)
            assertEquals(0, tiled.tileFailureCount)
            assertTrue(tiled.recognition.fullText.isEmpty())
        } finally {
            bitmap.recycle()
            gateway.close()
        }
    }
}
