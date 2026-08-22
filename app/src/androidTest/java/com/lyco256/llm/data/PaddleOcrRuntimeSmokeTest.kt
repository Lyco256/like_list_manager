package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleOcrRuntimeSmokeTest {
    @Test
    fun bundledModelsInitializeAndRunOnDevice() = runBlocking {
        val gateway = PaddleOcrTextGateway(ApplicationProvider.getApplicationContext())
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        try {
            val result = gateway.recognize(bitmap)
            assertEquals(64, result.imageWidth)
            assertEquals(64, result.imageHeight)
        } finally {
            bitmap.recycle()
            gateway.close()
        }
    }
}
