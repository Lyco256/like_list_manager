package com.lyco256.llm.data

import org.junit.Assert.assertEquals
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
}
