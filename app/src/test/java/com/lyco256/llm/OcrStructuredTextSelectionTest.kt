package com.lyco256.llm

import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrPoint
import com.lyco256.llm.data.OcrPostRecognitionResult
import com.lyco256.llm.data.OcrRecognitionResult
import com.lyco256.llm.data.OcrTextRegion
import com.lyco256.llm.data.withReadingOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrStructuredTextSelectionTest {
    @Test
    fun rangesComeFromTheSameReconstructionAndUpdateAfterRegionEdit() {
        val recognition = OcrRecognitionResult(
            200,
            100,
            "raw",
            listOf(
                OcrTextRegion("first", box(10f, 10f, 50f, 20f)),
                OcrTextRegion("second", box(60f, 10f, 100f, 20f)),
            ),
        ).withReadingOrder()
        val initial = OcrPostRecognitionResult(
            clipId = 1L,
            assets = listOf(OcrAssetRecognitionResult(7L, "/tmp/one.webp", recognition)),
            fullText = recognition.fullText,
        ).rebuildFromRegions()

        assertEquals("first second", initial.fullText)
        assertEquals(0, initial.regionRanges[0].start)
        assertEquals(5, initial.regionRanges[0].end)
        assertEquals(6, initial.regionRanges[1].start)
        assertEquals(12, initial.regionRanges[1].end)
        assertEquals(OcrRegionKey(7L, 1), initial.regionKeyAtTextOffset(7))

        val edited = initial.withRegionText(OcrRegionKey(7L, 0), "longer")!!
        assertEquals("longer second", edited.fullText)
        assertEquals(OcrRegionKey(7L, 1), edited.regionKeyAtTextOffset(8))
        assertEquals(7, edited.regionRanges[1].start)
    }

    @Test
    fun separatorsAndPolygonlessTextAreNotSelectable() {
        val recognition = OcrRecognitionResult(
            200,
            100,
            "raw",
            listOf(
                OcrTextRegion("first", box(10f, 10f, 50f, 20f)),
                OcrTextRegion("no polygon"),
                OcrTextRegion("second", box(120f, 10f, 160f, 20f)),
            ),
        ).withReadingOrder()
        val result = OcrPostRecognitionResult(
            1L,
            listOf(OcrAssetRecognitionResult(7L, "/tmp/one.webp", recognition)),
            recognition.fullText,
        ).rebuildFromRegions()

        assertNull(result.regionKeyAtTextOffset(result.fullText.indexOf("no polygon")))
        assertNull(result.regionKeyAtTextOffset(result.fullText.indexOf("\n\n")))
        assertEquals(OcrRegionKey(7L, 2), result.regionKeyAtTextOffset(result.fullText.lastIndex))
    }

    @Test
    fun postRangeCarriesAssetIdentityAcrossAssetSeparators() {
        fun asset(assetId: Long, text: String, x: Float) = OcrRecognitionResult(
            200,
            100,
            "raw",
            listOf(OcrTextRegion(text, box(x, 10f, x + 30f, 20f))),
        ).withReadingOrder().let { recognition ->
            OcrAssetRecognitionResult(assetId, "/tmp/$assetId.webp", recognition)
        }
        val result = OcrPostRecognitionResult(
            1L,
            listOf(asset(7L, "first", 10f), asset(8L, "second", 10f)),
            "",
        ).rebuildFromRegions()

        assertEquals(OcrRegionKey(7L, 0), result.regionKeyAtTextOffset(1))
        assertEquals(OcrRegionKey(8L, 0), result.regionKeyAtTextOffset(result.fullText.lastIndex))
        assertNull(result.regionKeyAtTextOffset(result.fullText.indexOf("\n\n")))
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = com.lyco256.llm.data.OcrPolygon(
        listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )
}
