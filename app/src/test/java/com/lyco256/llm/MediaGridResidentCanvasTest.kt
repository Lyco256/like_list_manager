package com.lyco256.llm

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridResidentCanvasTest {
    @Test
    fun actualCellRectFormulaMatchesTwoFourEightAndTwelveColumnGeometry() {
        listOf(2, 4, 8, 12).forEach { columns ->
            val cellWidth = 240 / columns
            val cell = mediaGridCanvasDestinationRect(
                offset = IntOffset(cellWidth, cellWidth * 2),
                size = IntSize(cellWidth, cellWidth),
                viewportStartOffset = 10,
            )
            assertEquals(cellWidth.toFloat(), cell.left, 0.01f)
            assertEquals((cellWidth * 2 - 10).toFloat(), cell.top, 0.01f)
            assertEquals((cellWidth * 2).toFloat(), cell.right, 0.01f)
            assertEquals((cellWidth * 3 - 10).toFloat(), cell.bottom, 0.01f)
        }
    }

    @Test
    fun cropUsesWholeSquareAndCenteredHorizontalCrop() {
        assertEquals(RectExpectation(0f, 0f, 256f, 256f), mediaGridCropSourceRect(256, 256, 128, 128).toExpectation())
        assertEquals(RectExpectation(256f, 0f, 768f, 512f), mediaGridCropSourceRect(1024, 512, 1, 1).toExpectation())
        assertEquals(RectExpectation(0f, 512f, 1024f, 1536f), mediaGridCropSourceRect(1024, 2048, 1, 1).toExpectation())
    }

    @Test
    fun cropHandlesWideTallDestinationsAndInvalidDimensions() {
        val wide = mediaGridCropSourceRect(400, 200, 300, 100)!!
        assertEquals(400f, wide.width, 0.01f)
        assertEquals(133.33f, wide.height, 0.02f)
        assertEquals(33.33f, wide.top, 0.02f)
        val tall = mediaGridCropSourceRect(200, 400, 100, 300)!!
        assertEquals(133.33f, tall.width, 0.02f)
        assertEquals(400f, tall.height, 0.01f)
        assertNull(mediaGridCropSourceRect(0, 10, 1, 1))
        assertNull(mediaGridCropSourceRect(10, 10, 0, 1))
        assertTrue(wide.left >= 0f && wide.right <= 400f && wide.top >= 0f && wide.bottom <= 200f)
    }

    @Test
    fun residentCanvasViewportIsGridLocalAndNeverNegative() {
        assertEquals(RectExpectation(0f, 0f, 240f, 160f), mediaGridResidentCanvasViewportRect(240, 160).toExpectation())
        assertEquals(RectExpectation(0f, 0f, 0f, 0f), mediaGridResidentCanvasViewportRect(-1, -1).toExpectation())
    }

    private data class RectExpectation(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun androidx.compose.ui.geometry.Rect?.toExpectation(): RectExpectation? = this?.let {
        RectExpectation(left, top, right, bottom)
    }
}
