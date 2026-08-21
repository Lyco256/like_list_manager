package com.lyco256.llm

import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrPoint
import com.lyco256.llm.data.OcrPolygon
import com.lyco256.llm.data.OcrPostRecognitionResult
import com.lyco256.llm.data.OcrRecognitionResult
import com.lyco256.llm.data.OcrTextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrViewerGeometryTest {
    @Test
    fun sameAspectRatioMapsImageCornersWithoutOffset() {
        val rect = OcrViewerGeometry.fitImageRect(100, 50, 200f, 100f)

        assertEquals(0f, rect!!.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(200f, rect.right, 0.001f)
        assertEquals(100f, rect.bottom, 0.001f)
        assertEquals(
            listOf(OcrPoint(0f, 0f), OcrPoint(200f, 0f), OcrPoint(200f, 100f), OcrPoint(0f, 100f)),
            OcrViewerGeometry.mapPolygonToViewport(
                box(0f, 0f, 100f, 50f),
                100,
                50,
                200f,
                100f,
            ),
        )
    }

    @Test
    fun fitAddsVerticalLetterboxForLandscapeImage() {
        val points = OcrViewerGeometry.mapPolygonToViewport(
            box(0f, 0f, 100f, 50f),
            100,
            50,
            100f,
            200f,
        )!!

        assertEquals(0f, points.first().x, 0.001f)
        assertEquals(75f, points.first().y, 0.001f)
        assertEquals(100f, points[2].x, 0.001f)
        assertEquals(125f, points[2].y, 0.001f)
    }

    @Test
    fun fitAddsHorizontalLetterboxForPortraitImage() {
        val points = OcrViewerGeometry.mapPolygonToViewport(
            box(0f, 0f, 50f, 100f),
            50,
            100,
            200f,
            100f,
        )!!

        assertEquals(75f, points.first().x, 0.001f)
        assertEquals(0f, points.first().y, 0.001f)
        assertEquals(125f, points[2].x, 0.001f)
        assertEquals(100f, points[2].y, 0.001f)
    }

    @Test
    fun zoomAndPanTransformTheImageAndPolygonInTheSameCoordinateSpace() {
        val transform = OcrViewerTransform(scale = 2f, offsetX = 13f, offsetY = -9f)
        val imagePoint = OcrPoint(40f, 30f)
        val polygonPoint = OcrViewerGeometry.transformPoint(imagePoint, 200f, 100f, transform)

        assertEquals(-7f, polygonPoint.x, 0.001f)
        assertEquals(1f, polygonPoint.y, 0.001f)
        assertEquals(
            polygonPoint,
            OcrViewerGeometry.transformPoint(imagePoint, 200f, 100f, transform),
        )
    }

    @Test
    fun viewportResizeRecomputesFitInsteadOfReusingOldScale() {
        val first = OcrViewerGeometry.fitImageRect(100, 100, 200f, 100f)!!
        val second = OcrViewerGeometry.fitImageRect(100, 100, 100f, 200f)!!

        assertEquals(100f, first.width, 0.001f)
        assertEquals(100f, second.width, 0.001f)
        assertEquals(0f, first.top, 0.001f)
        assertEquals(50f, second.top, 0.001f)
    }

    @Test
    fun outOfBoundsPolygonIsClippedWithoutDestroyingItsShape() {
        val points = OcrViewerGeometry.mapPolygonToViewport(
            OcrPolygon(
                listOf(
                    OcrPoint(-10f, 10f),
                    OcrPoint(60f, -5f),
                    OcrPoint(110f, 80f),
                    OcrPoint(20f, 120f),
                ),
            ),
            100,
            100,
            100f,
            100f,
        )!!

        assertTrue(points.size > 4)
        assertTrue(points.all { it.x in 0f..100f && it.y in 0f..100f })
        assertTrue(points.distinct().size > 4)
    }

    @Test
    fun invalidPolygonIsIgnoredWithoutAffectingOtherRegions() {
        val invalid = OcrPolygon(
            listOf(
                OcrPoint(Float.NaN, 0f),
                OcrPoint(10f, 0f),
                OcrPoint(10f, 10f),
                OcrPoint(0f, 10f),
            ),
        )
        val valid = box(10f, 10f, 30f, 20f)

        assertNull(OcrViewerGeometry.mapPolygonToViewport(invalid, 100, 100, 100f, 100f))
        assertNotNull(OcrViewerGeometry.mapPolygonToViewport(valid, 100, 100, 100f, 100f))
    }

    @Test
    fun assetIdSelectsOnlyTheMatchingStructuredResult() {
        val first = OcrAssetRecognitionResult(11L, "/first.webp", recognition(11))
        val second = OcrAssetRecognitionResult(22L, "/second.webp", recognition(22))
        val result = OcrPostRecognitionResult(7L, listOf(first, second), "first\nsecond")

        assertEquals(first, result.assetFor(11L))
        assertEquals(second, result.assetFor(22L))
        assertNull(result.assetFor(33L))
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrPolygon(
        listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )

    private fun recognition(id: Long) = OcrRecognitionResult(
        imageWidth = 100,
        imageHeight = 100,
        fullText = id.toString(),
        regions = listOf(OcrTextRegion(id.toString(), box(0f, 0f, 10f, 10f))),
    )
}
