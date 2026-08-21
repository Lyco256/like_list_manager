package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextRecognizerTest {
    @Test
    fun formatOcrTextOrdersBlocksAndLinesByImageOrientation() {
        val blocks = listOf(
            OcrTextBlockCandidate(
                lines = listOf(
                    OcrTextLineCandidate(
                        text = "vertical-second",
                        bounds = OcrTextBounds(left = 10, top = 30, right = 60, bottom = 50),
                        order = 1,
                    ),
                    OcrTextLineCandidate(
                        text = "vertical-first",
                        bounds = OcrTextBounds(left = 10, top = 10, right = 60, bottom = 30),
                        order = 0,
                    ),
                ),
                bounds = OcrTextBounds(left = 0, top = 0, right = 40, bottom = 120),
                order = 2,
            ),
            OcrTextBlockCandidate(
                lines = listOf(
                    OcrTextLineCandidate(
                        text = "middle",
                        bounds = null,
                        order = 0,
                    ),
                ),
                bounds = null,
                order = 1,
            ),
            OcrTextBlockCandidate(
                lines = listOf(
                    OcrTextLineCandidate(
                        text = "horizontal-right",
                        bounds = OcrTextBounds(left = 90, top = 10, right = 120, bottom = 30),
                        order = 1,
                    ),
                    OcrTextLineCandidate(
                        text = "horizontal-left",
                        bounds = OcrTextBounds(left = 10, top = 10, right = 40, bottom = 30),
                        order = 0,
                    ),
                ),
                bounds = OcrTextBounds(left = 0, top = 0, right = 160, bottom = 40),
                order = 0,
            ),
        )

        assertEquals(
            "vertical-first\nvertical-second\n\nmiddle\n\nhorizontal-left\nhorizontal-right",
            formatOcrText(blocks, isVerticalImage = true),
        )
    }

    @Test
    fun structuredRegionsKeepFormattedOrderSeparatorsAndMetadata() {
        val polygon = OcrPolygon(
            listOf(
                OcrPoint(10f, 10f),
                OcrPoint(40f, 12f),
                OcrPoint(38f, 30f),
                OcrPoint(8f, 28f),
            ),
        )
        val blocks = listOf(
            OcrTextBlockCandidate(
                lines = listOf(
                    OcrTextLineCandidate("second", OcrTextBounds(10, 30, 60, 50), 1),
                    OcrTextLineCandidate("first", OcrTextBounds(10, 10, 60, 30), 0, polygon, 0.91f),
                    OcrTextLineCandidate("   ", null, 2, confidence = 0.1f),
                ),
                bounds = OcrTextBounds(0, 0, 40, 120),
                order = 0,
            ),
            OcrTextBlockCandidate(
                lines = listOf(OcrTextLineCandidate("third", null, 0, confidence = null)),
                bounds = null,
                order = 1,
            ),
        )

        val regions = formatOcrRegions(blocks, isVerticalImage = true)

        assertEquals(listOf("first", "second", "third"), regions.map { it.text })
        assertEquals(listOf("", "\n", "\n\n"), regions.map { it.precedingSeparator })
        assertEquals(polygon, regions[0].polygon)
        assertEquals(0.91f, regions[0].confidence)
        assertNull(regions[2].polygon)
        assertEquals("first\nsecond\n\nthird", regions.joinToString("") { it.precedingSeparator + it.text })
    }

    @Test
    fun cornerPointsArePreservedAndNormalizedToClockwiseTopLeftOrder() {
        val polygon = normalizeOcrPolygon(
            listOf(
                OcrPoint(42f, 70f),
                OcrPoint(30f, 90f),
                OcrPoint(80f, 20f),
                OcrPoint(12f, 10f),
            ),
        )

        assertEquals(
            listOf(
                OcrPoint(12f, 10f),
                OcrPoint(80f, 20f),
                OcrPoint(42f, 70f),
                OcrPoint(30f, 90f),
            ),
            polygon?.points,
        )
        assertTrue(polygon?.points?.toSet() == setOf(
            OcrPoint(42f, 70f),
            OcrPoint(30f, 90f),
            OcrPoint(80f, 20f),
            OcrPoint(12f, 10f),
        ))
    }

    @Test
    fun boundingBoxCreatesFourPointFallbackAndPositionlessTextIsKept() {
        assertEquals(
            listOf(
                OcrPoint(1f, 2f),
                OcrPoint(11f, 2f),
                OcrPoint(11f, 22f),
                OcrPoint(1f, 22f),
            ),
            OcrPolygon.fromBounds(OcrTextBounds(1, 2, 11, 22)).points,
        )

        val result = buildOcrRecognitionResult(
            imageWidth = 320,
            imageHeight = 240,
            fallbackText = "fallback text",
            blocks = listOf(
                OcrTextBlockCandidate(
                    lines = listOf(
                        OcrTextLineCandidate("  ", null, 0),
                        OcrTextLineCandidate("no position", null, 1, confidence = 0.5f),
                    ),
                    bounds = null,
                    order = 0,
                ),
            ),
            isVerticalImage = false,
        )

        assertEquals(320, result.imageWidth)
        assertEquals(240, result.imageHeight)
        assertEquals("no position", result.fullText)
        assertEquals(listOf("no position"), result.regions.map { it.text })
        assertNull(result.regions.single().polygon)
        assertEquals(0.5f, result.regions.single().confidence)
    }

    @Test
    fun textFallbackStillProducesFullTextWhenNoStructuredBlocksExist() {
        val result = buildOcrRecognitionResult(
            imageWidth = 100,
            imageHeight = 80,
            fallbackText = "line one\nline two",
            blocks = emptyList(),
            isVerticalImage = false,
        )

        assertEquals("line one\nline two", result.fullText)
        assertEquals(listOf(OcrTextRegion("line one\nline two")), result.regions)
    }
}
