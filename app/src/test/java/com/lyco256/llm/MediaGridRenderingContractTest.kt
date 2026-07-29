package com.lyco256.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridRenderingContractTest {
    @Test
    fun viewportAndActiveWindowUseOrdinalBoundariesWithoutLegacyCollections() {
        val uiSource = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val controllerSource = locateSource("src/main/java/com/lyco256/llm/MediaGridSteadyLoadController.kt").readText()
        val viewport = uiSource.substringAfter("internal fun buildMediaGridViewportSignature").substringBefore("internal fun MediaGridViewportSignature.toAnchor")
        val active = controllerSource.substringAfter("internal fun buildMediaGridActiveWindowSnapshot").substringBefore("internal fun mediaGridEstimatedBitmapBytes")
        assertTrue(viewport.contains("for (info in layout.visibleItemsInfo)"))
        assertTrue(!viewport.contains("mapNotNull") && !viewport.contains("toIntArray") && !viewport.contains("itemByKey") && !viewport.contains("MediaGridCellItem"))
        assertTrue(!controllerSource.contains("buildMediaGridOrdinalIndex"))
        assertTrue(!controllerSource.contains("visibleMediaItemIndices"))
        assertTrue(!active.contains("asSequence") && !active.contains("toList()") && !active.contains("activeAssetIds.toSet()"))
        assertTrue(!active.contains("frame.items") && !active.contains("MediaGridCellItem"))
        assertTrue(!controllerSource.contains("activeAssetMembership"))
    }

    @Test
    fun schedulerAndPublicationContractsRemainUntouched() {
        val source = locateSource("src/main/java/com/lyco256/llm/MediaGridSteadyLoadController.kt").readText()
        assertTrue(source.contains("MEDIA_GRID_METADATA_MAX_CONCURRENCY = 4"))
        assertTrue(source.contains("MEDIA_GRID_BACKGROUND_MAX_CONCURRENCY = 2"))
        assertTrue(source.contains("MEDIA_GRID_ACTIVE_PREFETCH_ROWS = 3"))
        assertTrue(source.contains("publishOneReadyImageForFrame"))
        assertTrue(!source.contains("delay(") && !source.contains("Thread.sleep"))
    }

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
