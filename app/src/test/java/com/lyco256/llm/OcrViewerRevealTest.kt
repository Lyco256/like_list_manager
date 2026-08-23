package com.lyco256.llm

import com.lyco256.llm.data.OcrPoint
import com.lyco256.llm.data.OcrPolygon
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrViewerRevealTest {
    @Test
    fun alreadyVisiblePolygonDoesNotChangeTheTransform() {
        val current = OcrViewerTransform(scale = 2f, offsetX = 0f, offsetY = 0f)
        assertEquals(
            current,
            OcrViewerGeometry.revealPolygonTransform(
                polygon = box(40f, 40f, 60f, 60f),
                sourceWidth = 100,
                sourceHeight = 100,
                viewportWidth = 200f,
                viewportHeight = 200f,
                current = current,
            ),
        )
    }

    @Test
    fun offscreenPolygonKeepsZoomAndUsesMinimalPan() {
        val revealed = OcrViewerGeometry.revealPolygonTransform(
            polygon = box(10f, 10f, 20f, 20f),
            sourceWidth = 100,
            sourceHeight = 100,
            viewportWidth = 200f,
            viewportHeight = 200f,
            current = OcrViewerTransform(scale = 2f, offsetX = -80f, offsetY = 0f),
        )

        assertEquals(2f, revealed.scale)
        assertEquals(60f, revealed.offsetX)
    }

    @Test
    fun oversizedPolygonShrinksOnlyToTheOneTimesLowerBound() {
        val revealed = OcrViewerGeometry.revealPolygonTransform(
            polygon = box(0f, 0f, 100f, 100f),
            sourceWidth = 100,
            sourceHeight = 100,
            viewportWidth = 200f,
            viewportHeight = 200f,
            current = OcrViewerTransform(scale = 4f, offsetX = -50f, offsetY = 25f),
        )

        assertEquals(1f, revealed.scale)
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrPolygon(
        listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )
}
