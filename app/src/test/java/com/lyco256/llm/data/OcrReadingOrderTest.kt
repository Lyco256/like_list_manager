package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrReadingOrderTest {
    @Test
    fun directionUsesTheTwentyPercentSideRatioAndDominantTieFallsBackToHorizontal() {
        val result = recognition(
            width = 300,
            height = 200,
            regions = listOf(
                region("h", box(10f, 10f, 50f, 20f)),
                region("v", box(70f, 10f, 80f, 50f)),
                region("ambiguous", box(100f, 10f, 130f, 35f)),
            ),
        ).withReadingOrder()

        assertEquals(OcrRegionOrientation.HORIZONTAL, result.textLayout!!.regionOrientations[0])
        assertEquals(OcrRegionOrientation.VERTICAL, result.textLayout.regionOrientations[1])
        assertEquals(OcrRegionOrientation.AMBIGUOUS, result.textLayout.regionOrientations[2])
        assertEquals(OcrDominantOrientation.HORIZONTAL_DOMINANT, result.textLayout.dominantOrientation)
    }

    @Test
    fun verticalOnlyMajorityWinsAndNoPolygonFallsBackToHorizontal() {
        val vertical = recognition(
            300,
            200,
            listOf(
                region("one", box(10f, 10f, 20f, 50f)),
                region("two", box(40f, 10f, 50f, 50f)),
                region("three", box(70f, 10f, 80f, 50f)),
                region("ambiguous"),
            ),
        ).withReadingOrder()
        val noGeometry = recognition(100, 100, listOf(region("a"), region("b"))).withReadingOrder()

        assertEquals(OcrDominantOrientation.VERTICAL_DOMINANT, vertical.textLayout!!.dominantOrientation)
        assertEquals(OcrDominantOrientation.HORIZONTAL_DOMINANT, noGeometry.textLayout!!.dominantOrientation)
        assertEquals(listOf(listOf(0), listOf(1)), noGeometry.textLayout.orderedGroups)
        assertEquals("a\n\nb", noGeometry.fullText)
    }

    @Test
    fun horizontalIndependentTwoByTwoGroupsUseTopThenLeftOrder() {
        val result = recognition(
            200,
            200,
            listOf(
                region("TL", box(10f, 10f, 40f, 20f)),
                region("BR", box(100f, 100f, 130f, 110f)),
                region("BL", box(10f, 100f, 40f, 110f)),
                region("TR", box(100f, 10f, 130f, 20f)),
            ),
        ).withReadingOrder()

        assertEquals(listOf("TL", "TR", "BL", "BR"), orderedTexts(result))
    }

    @Test
    fun verticalIndependentTwoByTwoGroupsUseTopThenRightOrder() {
        val result = recognition(
            200,
            200,
            listOf(
                region("TL", box(10f, 10f, 20f, 40f)),
                region("BR", box(100f, 100f, 110f, 130f)),
                region("BL", box(10f, 100f, 20f, 130f)),
                region("TR", box(100f, 10f, 110f, 40f)),
            ),
        ).withReadingOrder()

        assertEquals(OcrDominantOrientation.VERTICAL_DOMINANT, result.textLayout!!.dominantOrientation)
        assertEquals(listOf("TR", "TL", "BR", "BL"), orderedTexts(result))
    }

    @Test
    fun verticalGroupUsesRightToLeftColumnsAndTopToBottomWithinEachColumn() {
        val result = recognition(
            300,
            200,
            listOf(
                region("右上", box(100f, 10f, 110f, 35f)),
                region("右下", box(100f, 45f, 110f, 70f)),
                region("左上", box(80f, 10f, 90f, 35f)),
                region("左下", box(80f, 45f, 90f, 70f)),
            ),
        ).withReadingOrder()

        assertEquals(listOf(listOf(0, 1, 2, 3)), result.textLayout!!.orderedGroups)
        assertEquals("右上右下左上左下", result.fullText)
    }

    @Test
    fun sameLineAndWideAdjacentRowsStayInOneGroupWithoutRawReordering() {
        val result = recognition(
            200,
            200,
            listOf(
                region("A", box(40f, 10f, 65f, 20f)),
                region("B", box(70f, 10f, 95f, 20f)),
                region("C", box(42f, 40f, 67f, 50f)),
                region("D", box(72f, 40f, 97f, 50f)),
            ),
        ).withReadingOrder()

        assertEquals(listOf(listOf(0, 1, 2, 3)), result.textLayout!!.orderedGroups)
        assertEquals("A B C D", result.fullText)
    }

    @Test
    fun verticalWideAdjacentColumnsStayInOneGroupButFarColumnDoesNotJoin() {
        val result = recognition(
            200,
            200,
            listOf(
                region("右上", box(150f, 10f, 160f, 35f)),
                region("右下", box(150f, 55f, 160f, 80f)),
                region("左", box(30f, 10f, 40f, 35f)),
            ),
        ).withReadingOrder()

        assertEquals(OcrDominantOrientation.VERTICAL_DOMINANT, result.textLayout!!.dominantOrientation)
        assertEquals(listOf(listOf(0, 1), listOf(2)), result.textLayout.orderedGroups)
        assertEquals("右上右下\n\n左", result.fullText)
    }

    @Test
    fun separateBubblesSizeMismatchAndTenPercentGapDoNotJoin() {
        val result = recognition(
            200,
            200,
            listOf(
                region("left", box(10f, 10f, 30f, 20f)),
                region("right", box(100f, 10f, 140f, 30f)),
                region("far", box(10f, 100f, 30f, 110f)),
            ),
        ).withReadingOrder()

        assertEquals(listOf("left", "right", "far"), orderedTexts(result))
        assertTrue(result.textLayout!!.orderedGroups.all { it.size == 1 })
    }

    @Test
    fun interveningRegionVetoAndNaturalThreeRowChainAvoidWeakBridge() {
        val result = recognition(
            300,
            300,
            listOf(
                region("one", box(20f, 10f, 45f, 20f)),
                region("two", box(20f, 45f, 45f, 55f)),
                region("three", box(20f, 80f, 45f, 90f)),
                region("other", box(220f, 45f, 245f, 55f)),
            ),
        ).withReadingOrder()

        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), result.textLayout!!.orderedGroups)
        assertEquals("one two three\n\nother", result.fullText)
    }

    @Test
    fun polygonlessRegionsStayInRawNeighborhoodAndRemainInText() {
        val result = recognition(
            200,
            100,
            listOf(
                region("before", box(10f, 10f, 40f, 20f)),
                region("no polygon"),
                region("after", box(120f, 10f, 150f, 20f)),
            ),
        ).withReadingOrder()

        assertEquals(listOf(listOf(0, 1), listOf(2)), result.textLayout!!.orderedGroups)
        assertEquals("before no polygon\n\nafter", result.fullText)
        assertEquals(listOf(0, 1, 2), result.regionRanges.map(OcrRegionTextRange::regionIndex))
    }

    @Test
    fun latinWordBoundaryGetsOneSpaceButCjkAndPunctuationDoNot() {
        val result = recognition(
            200,
            100,
            listOf(
                region("hello", box(10f, 10f, 40f, 20f)),
                region("world", box(45f, 10f, 75f, 20f)),
                region("日本", box(10f, 40f, 40f, 50f)),
                region("語。", box(45f, 40f, 75f, 50f)),
            ),
        ).withReadingOrder()

        assertEquals("hello world\n\n日本語。", result.fullText)
    }

    private fun orderedTexts(result: OcrRecognitionResult): List<String> =
        result.textLayout!!.orderedGroups.flatten().map { result.regions[it].text }

    private fun recognition(width: Int, height: Int, regions: List<OcrTextRegion>) =
        OcrRecognitionResult(width, height, "raw", regions)

    private fun region(text: String, polygon: OcrPolygon? = null) =
        OcrTextRegion(text = text, polygon = polygon)

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrPolygon(
        listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )
}
