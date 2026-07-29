package com.lyco256.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridRenderingContractTest {
    @Test
    fun productionGridOwnsTheOnlyOverscrollOptOutAndToolbarsSharePositiveLayer() {
        val source = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        assertEquals(1, Regex("LocalOverscrollConfiguration\\s+provides\\s+null").findAll(source).count())
        assertEquals(2, Regex("zIndex\\(ClassifiedMediaGridToolbarZIndex\\)").findAll(source).count())
        assertTrue(source.indexOf("TagFilterSummaryRow") < source.indexOf("ClassifiedMediaGridContent"))
    }

    @Test
    fun residentDrawIsClippedBeforeDrawContentWithoutCommands() {
        val source = locateSource("src/main/java/com/lyco256/llm/MediaGridResidentCanvas.kt").readText()
        val clip = source.indexOf("clipRect(")
        val drawContent = source.indexOf("drawContent()")
        assertTrue(clip >= 0)
        assertTrue(drawContent > clip)
        assertTrue(source.substring(clip, drawContent).contains("visibleItemsInfo.forEach"))
        assertTrue(source.substring(clip, drawContent).contains("preparedImageByAssetId"))
        assertTrue(source.substring(clip, drawContent).contains("drawImage"))
        assertTrue(!source.substring(clip, drawContent).contains("drawIndexSnapshot"))
        assertTrue(!source.substring(clip, drawContent).contains("adapter."))
        assertTrue(!source.substring(clip, drawContent).contains("mediaGridCropSourceRect"))
    }

    @Test
    fun preparedIndexIsKeyedByFrameStoreAdapterAndDrawIndexVersion() {
        val source = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val prepared = source.indexOf("val residentPreparedIndex")
        val draw = source.indexOf("Modifier.mediaGridResidentCanvas")
        assertTrue(prepared >= 0)
        assertTrue(draw > prepared)
        assertTrue(source.substring(prepared, draw).contains("remember(frame.key, retainedImageStore, residentCanvasAdapter, residentDrawIndexVersion)"))
        assertTrue(source.substring(prepared, draw).contains("drawIndexSnapshot()"))
        assertTrue(source.substring(prepared, draw).contains("residentCanvasMode != MediaGridResidentCanvasMode.Disabled"))
    }

    private fun locateSource(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("app", relativePath.removePrefix("src/")),
    ).firstOrNull(File::isFile)
        ?: error("Source file not found: $relativePath")
}
