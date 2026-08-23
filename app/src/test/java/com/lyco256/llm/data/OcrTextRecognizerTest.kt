package com.lyco256.llm.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextRecognizerTest {
    @Test
    fun qualityModesMapToTheExpectedModelBundle() {
        assertEquals(PaddleOcrModelVariant.SMALL, OcrQualityMode.FAST.modelVariant())
        assertEquals(PaddleOcrModelVariant.MEDIUM, OcrQualityMode.ACCURATE.modelVariant())
        assertEquals("models/small/det/inference.onnx", PaddleOcrModelVariant.SMALL.detectorAssetPath)
        assertEquals("models/small/rec/inference.onnx", PaddleOcrModelVariant.SMALL.recognizerAssetPath)
        assertEquals("models/medium/det/inference.onnx", PaddleOcrModelVariant.MEDIUM.detectorAssetPath)
        assertEquals("models/medium/rec/inference.onnx", PaddleOcrModelVariant.MEDIUM.recognizerAssetPath)
    }

    @Test
    fun paddleResultsUseTheCommonStructuredContract() {
        val result = listOf(
            PaddleOcrRawResult(
                text = "  first ",
                confidence = 0.91f,
                points = listOf(
                    OcrPoint(40f, 30f),
                    OcrPoint(10f, 30f),
                    OcrPoint(10f, 10f),
                    OcrPoint(40f, 10f),
                ),
            ),
            PaddleOcrRawResult("second", 0.72f, emptyList()),
        ).toOcrRecognitionResult(100, 80)

        assertEquals(100, result.imageWidth)
        assertEquals(80, result.imageHeight)
        assertEquals("first\nsecond", result.fullText)
        assertEquals(listOf("first", "second"), result.regions.map { it.text })
        assertEquals(0.91f, result.regions[0].confidence)
        assertEquals(4, result.regions[0].polygon?.points?.size)
        assertNull(result.regions[1].polygon)
    }

    @Test
    fun invalidPolygonCoordinatesAreDroppedWithoutDroppingText() {
        val result = listOf(
            PaddleOcrRawResult(
                text = "text",
                confidence = 0.5f,
                points = listOf(
                    OcrPoint(Float.NaN, 0f),
                    OcrPoint(1f, 0f),
                    OcrPoint(1f, 1f),
                    OcrPoint(0f, 1f),
                ),
            ),
        ).toOcrRecognitionResult(10, 10)

        assertEquals("text", result.fullText)
        assertNull(result.regions.single().polygon)
    }

    @Test
    fun enginePoolReusesSameVariantAndReleasesBeforeSwitching() = runBlocking {
        val events = mutableListOf<String>()
        val factory = PaddleOcrEngineFactory { variant ->
            events += "create:${variant.name}"
            object : PaddleOcrEngine {
                override suspend fun recognize(bitmap: android.graphics.Bitmap) = emptyList<PaddleOcrRawResult>()
                override suspend fun release() {
                    events += "release:${variant.name}"
                }
            }
        }
        val pool = PaddleOcrEnginePool(factory)

        pool.get(PaddleOcrModelVariant.SMALL)
        pool.get(PaddleOcrModelVariant.SMALL)
        pool.get(PaddleOcrModelVariant.MEDIUM)
        pool.get(PaddleOcrModelVariant.SMALL)
        pool.close()

        assertEquals(
            listOf(
                "create:SMALL",
                "release:SMALL",
                "create:MEDIUM",
                "release:MEDIUM",
                "create:SMALL",
                "release:SMALL",
            ),
            events,
        )
        assertTrue(events.count { it.startsWith("create:") } == 3)
    }
}
