package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleOcrTileTest {
    @Test
    fun smallImagesUseOneWholeImageTile() {
        assertEquals(
            listOf(PaddleOcrTile(0, 0, 1200, 900)),
            buildPaddleOcrTiles(1200, 900),
        )
    }

    @Test
    fun largeImagesAreFullyCoveredByBoundedTilesWithoutDuplicateRectangles() {
        val tiles = buildPaddleOcrTiles(3200, 1800)

        assertEquals(6, tiles.size)
        assertTrue(tiles.all { it.width <= 1536 && it.height <= 1536 })
        assertEquals(tiles.size, tiles.distinct().size)
        assertEquals(listOf(0, 1280, 1664), tiles.map { it.left }.distinct())
        assertEquals(listOf(0, 264), tiles.map { it.top }.distinct())
        assertTrue(tiles.any { it.left == 1664 && it.right == 3200 })
        assertTrue(tiles.any { it.top == 264 && it.bottom == 1800 })
        assertAxisCoverage(tiles.map { it.left to it.right }, 3200)
        assertAxisCoverage(tiles.map { it.top to it.bottom }, 1800)
    }

    @Test
    fun tileScalePreservesAspectRatioAndCapsLongSide() {
        val tile = PaddleOcrTile(0, 0, 1200, 900)
        val scale = paddleOcrScaleForTile(tile)

        assertEquals(2048f / 1200f, scale, 0.0001f)
        assertEquals(2048, (tile.width * scale).toInt())
        assertEquals(1536, (tile.height * scale).toInt())
        assertEquals(1f, paddleOcrScaleForTile(PaddleOcrTile(0, 0, 2400, 800)), 0f)
    }

    @Test
    fun tilePolygonReturnsToOriginalCoordinatesWithRotationPreserved() {
        val tile = PaddleOcrTile(left = 1280, top = 512, width = 1536, height = 1024)
        val candidate = mapPaddleOcrTileCandidate(
            raw = PaddleOcrRawResult(
                text = "斜め",
                confidence = 0.8f,
                points = listOf(
                    OcrPoint(100f, 50f),
                    OcrPoint(500f, 100f),
                    OcrPoint(450f, 300f),
                    OcrPoint(50f, 250f),
                ),
            ),
            tile = tile,
            scale = 2f,
            imageWidth = 4000,
            imageHeight = 2400,
        )

        assertEquals(
            listOf(
                OcrPoint(1330f, 537f),
                OcrPoint(1530f, 562f),
                OcrPoint(1505f, 662f),
                OcrPoint(1305f, 637f),
            ),
            candidate?.polygon?.points,
        )
    }

    @Test
    fun onlyInternalTileEdgesAreMarkedAsClipped() {
        val interior = PaddleOcrTile(left = 1280, top = 0, width = 1536, height = 1200)
        val outer = PaddleOcrTile(left = 0, top = 0, width = 1536, height = 1200)
        val points = listOf(OcrPoint(10f, 100f), OcrPoint(100f, 100f), OcrPoint(100f, 200f), OcrPoint(10f, 200f))

        assertTrue(isPaddleOcrTileEdgeClipped(points, interior, 4000, 1200))
        assertFalse(isPaddleOcrTileEdgeClipped(points, outer, 4000, 1200))
    }

    @Test
    fun duplicateIntegrationIsStableAndUsesTheRequiredRepresentativePriority() {
        val whole = candidate("whole", box(100f, 100f, 200f, 140f), 0.80f, PaddleOcrCandidateSource.WHOLE_IMAGE)
        val closeTile = candidate("tile", box(101f, 101f, 201f, 141f), 0.81f, PaddleOcrCandidateSource.TILE)
        val edgeTile = candidate("edge", box(100f, 100f, 200f, 140f), 0.81f, PaddleOcrCandidateSource.TILE, edgeClipped = true)

        val forward = listOf(whole, closeTile, edgeTile).integratePaddleOcrCandidates()
        val reversed = listOf(edgeTile, closeTile, whole).integratePaddleOcrCandidates()

        assertEquals(forward, reversed)
        assertEquals(listOf("tile"), forward.map { it.text })
    }

    @Test
    fun adjacentAndLargeContainingPolygonsRemainSeparate() {
        val adjacent = listOf(
            candidate("left", box(0f, 0f, 100f, 40f), 0.8f, PaddleOcrCandidateSource.WHOLE_IMAGE),
            candidate("right", box(101f, 0f, 201f, 40f), 0.8f, PaddleOcrCandidateSource.TILE),
        )
        val containing = listOf(
            candidate("large", box(0f, 0f, 200f, 100f), 0.8f, PaddleOcrCandidateSource.WHOLE_IMAGE),
            candidate("small", box(10f, 10f, 60f, 40f), 0.8f, PaddleOcrCandidateSource.TILE),
        )

        assertEquals(listOf("left", "right"), adjacent.integratePaddleOcrCandidates().map { it.text })
        assertEquals(setOf("large", "small"), containing.integratePaddleOcrCandidates().map { it.text }.toSet())
    }

    @Test
    fun tileWithoutPolygonIsDiscardedButWholeWithoutPolygonIsKept() {
        val result = listOf(
            candidate("whole", null, 0.7f, PaddleOcrCandidateSource.WHOLE_IMAGE),
            candidate("tile", null, 0.9f, PaddleOcrCandidateSource.TILE),
        ).toPaddleOcrRecognitionResult(300, 200)

        assertEquals(listOf("whole"), result.regions.map { it.text })
        assertEquals(null, result.regions.single().polygon)
    }

    @Test
    fun enginePoolCreatesEachVariantOnceAndReleasesBoth() {
        val created = mutableListOf<PaddleOcrModelVariant>()
        val released = mutableListOf<PaddleOcrModelVariant>()
        val pool = PaddleOcrEnginePool(PaddleOcrEngineFactory { variant ->
            created += variant
            object : PaddleOcrEngine {
                override suspend fun recognize(bitmap: android.graphics.Bitmap): List<PaddleOcrRawResult> = emptyList()
                override suspend fun release() { released += variant }
            }
        })

        kotlinx.coroutines.runBlocking {
            pool.get(PaddleOcrModelVariant.SMALL)
            pool.get(PaddleOcrModelVariant.SMALL)
            pool.get(PaddleOcrModelVariant.MEDIUM)
            pool.get(PaddleOcrModelVariant.MEDIUM)
            pool.close()
        }

        assertEquals(listOf(PaddleOcrModelVariant.SMALL, PaddleOcrModelVariant.MEDIUM), created)
        assertEquals(created, released)
    }

    private fun candidate(
        text: String,
        polygon: OcrPolygon?,
        confidence: Float?,
        source: PaddleOcrCandidateSource,
        edgeClipped: Boolean = false,
    ) = PaddleOcrCandidate(text, polygon, confidence, source, edgeClipped)

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrPolygon(
        listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )

    private fun assertAxisCoverage(ranges: List<Pair<Int, Int>>, size: Int) {
        val sorted = ranges.distinct().sortedBy { it.first }
        assertEquals(0, sorted.first().first)
        assertEquals(size, sorted.last().second)
        sorted.zipWithNext().forEach { (left, right) -> assertTrue(left.second >= right.first) }
    }
}
