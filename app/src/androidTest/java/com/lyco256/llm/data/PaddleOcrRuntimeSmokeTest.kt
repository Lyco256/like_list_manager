package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleOcrRuntimeSmokeTest {
    @Test
    fun bundledSmallAndMediumModelsSwitchOfflineWithoutCrash() = runBlocking {
        val gateway = PaddleOcrTextGateway(ApplicationProvider.getApplicationContext())
        val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("OCR offline", 24f, 128f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 48f
            })
        }
        try {
            val fast = gateway.recognize(bitmap, OcrQualityMode.FAST)
            val accurate = gateway.recognize(bitmap, OcrQualityMode.ACCURATE)
            val fastAgain = gateway.recognize(bitmap, OcrQualityMode.FAST)

            assertEquals(512, fast.imageWidth)
            assertEquals(256, accurate.imageHeight)
            assertEquals(fast.imageWidth, fastAgain.imageWidth)
            assertTrue(fast.regions.all { it.polygon == null || it.polygon.points.size == 4 })
            assertTrue(accurate.regions.all { it.polygon == null || it.polygon.points.size == 4 })
        } finally {
            bitmap.recycle()
            gateway.close()
        }
    }
}
