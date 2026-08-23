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

    @Test
    fun onlyFitScaleHorizontalDragCanSwitchPages() {
        assertEquals(1, OcrViewerGeometry.pageSwipeDirection(2, 1f, false, -100f, 400f))
        assertEquals(-1, OcrViewerGeometry.pageSwipeDirection(2, 1f, false, 100f, 400f))
        assertNull(OcrViewerGeometry.pageSwipeDirection(2, 1.5f, false, -200f, 400f))
        assertNull(OcrViewerGeometry.pageSwipeDirection(2, 1f, true, -200f, 400f))
    }

    @Test
    fun onlyAStationarySinglePointerGestureCanSelectAPolygon() {
        assertTrue(OcrViewerGeometry.isPolygonTapGesture(false, false, false))
        assertTrue(!OcrViewerGeometry.isPolygonTapGesture(false, false, true))
        assertTrue(!OcrViewerGeometry.isPolygonTapGesture(false, true, false))
        assertTrue(!OcrViewerGeometry.isPolygonTapGesture(true, false, false))
    }

    @Test
    fun fitAndLetterboxHitTestingUsesTheDisplayedPolygon() {
        val regions = listOf(OcrTextRegion("target", box(20f, 10f, 60f, 30f)))

        assertEquals(
            0,
            OcrViewerGeometry.hitTestRegionIndex(
                regions = regions,
                sourceWidth = 100,
                sourceHeight = 50,
                viewportWidth = 100f,
                viewportHeight = 200f,
                transform = OcrViewerTransform(),
                point = OcrPoint(40f, 95f),
                edgeTolerance = 0f,
            ),
        )
        assertEquals(
            0,
            OcrViewerGeometry.hitTestRegionIndex(
                regions = listOf(OcrTextRegion("target", box(10f, 20f, 30f, 60f))),
                sourceWidth = 50,
                sourceHeight = 100,
                viewportWidth = 200f,
                viewportHeight = 100f,
                transform = OcrViewerTransform(),
                point = OcrPoint(95f, 40f),
                edgeTolerance = 0f,
            ),
        )
    }

    @Test
    fun zoomAndPanHitTestingMatchesTheOverlayTransform() {
        val transform = OcrViewerTransform(scale = 2f, offsetX = 12f, offsetY = -8f)
        val polygon = box(35f, 35f, 55f, 55f)
        val displayed = OcrViewerGeometry.mapPolygonToViewport(
            polygon,
            100,
            100,
            200f,
            200f,
            transform,
        )!!
        val center = OcrPoint(
            displayed.map { it.x }.average().toFloat(),
            displayed.map { it.y }.average().toFloat(),
        )

        assertEquals(
            0,
            OcrViewerGeometry.hitTestRegionIndex(
                regions = listOf(OcrTextRegion("target", polygon)),
                sourceWidth = 100,
                sourceHeight = 100,
                viewportWidth = 200f,
                viewportHeight = 200f,
                transform = transform,
                point = center,
                edgeTolerance = 0f,
            ),
        )
    }

    @Test
    fun overlappingPolygonsPreferTheSmallestAndEdgeFallbackUsesDistance() {
        val regions = listOf(
            OcrTextRegion("large", box(20f, 20f, 80f, 80f)),
            OcrTextRegion("small", box(40f, 40f, 60f, 60f)),
        )
        assertEquals(
            1,
            OcrViewerGeometry.hitTestRegionIndex(
                regions,
                100,
                100,
                100f,
                100f,
                OcrViewerTransform(),
                OcrPoint(50f, 50f),
                12f,
            ),
        )
        assertEquals(
            0,
            OcrViewerGeometry.hitTestRegionIndex(
                listOf(regions[1]),
                100,
                100,
                100f,
                100f,
                OcrViewerTransform(),
                OcrPoint(61f, 50f),
                12f,
            ),
        )
        assertNull(
            OcrViewerGeometry.hitTestRegionIndex(
                listOf(regions[1]),
                100,
                100,
                100f,
                100f,
                OcrViewerTransform(),
                OcrPoint(73f, 50f),
                12f,
            ),
        )
    }

    @Test
    fun clippedAndInvalidPolygonsUseOnlyTheirVisibleValidShapes() {
        val clipped = OcrPolygon(
            listOf(
                OcrPoint(-20f, 20f),
                OcrPoint(40f, 20f),
                OcrPoint(40f, 40f),
                OcrPoint(-20f, 40f),
            ),
        )
        val invalid = OcrPolygon(
            listOf(
                OcrPoint(Float.NaN, 0f),
                OcrPoint(10f, 0f),
                OcrPoint(10f, 10f),
                OcrPoint(0f, 10f),
            ),
        )

        assertEquals(
            0,
            OcrViewerGeometry.hitTestRegionIndex(
                listOf(OcrTextRegion("clipped", clipped), OcrTextRegion("invalid", invalid)),
                100,
                100,
                100f,
                100f,
                OcrViewerTransform(),
                OcrPoint(10f, 30f),
                0f,
            ),
        )
        assertNull(
            OcrViewerGeometry.hitTestRegionIndex(
                listOf(OcrTextRegion("clipped", clipped), OcrTextRegion("invalid", invalid)),
                100,
                100,
                100f,
                100f,
                OcrViewerTransform(),
                OcrPoint(90f, 90f),
                0f,
            ),
        )
    }

    @Test
    fun pageCorrectionKeepsPartialInitialResultOnItsCurrentPreviewPage() {
        assertEquals(
            11L,
            correctedOcrPageAssetId(
                currentAssetId = 11L,
                previewAssetIds = listOf(11L, 22L),
                previousStructuredAssetIds = null,
                newStructuredAssetIds = listOf(22L),
            ),
        )
    }

    @Test
    fun pageCorrectionMovesAwayWhenRedetectRemovesCurrentAsset() {
        assertEquals(
            22L,
            correctedOcrPageAssetId(
                currentAssetId = 11L,
                previewAssetIds = listOf(11L, 22L),
                previousStructuredAssetIds = listOf(11L, 22L),
                newStructuredAssetIds = listOf(22L),
            ),
        )
    }

    @Test
    fun pageCorrectionFallsBackToFirstPreviewWhenNewResultHasNoKnownAsset() {
        assertEquals(
            11L,
            correctedOcrPageAssetId(
                currentAssetId = 22L,
                previewAssetIds = listOf(11L, 22L),
                previousStructuredAssetIds = listOf(22L),
                newStructuredAssetIds = emptyList(),
            ),
        )
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
