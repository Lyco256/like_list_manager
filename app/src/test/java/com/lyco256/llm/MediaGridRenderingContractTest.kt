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

    @Test
    fun morphPreparationIsBoundedIdleOnlyAndDoesNotEnableRendering() {
        val uiSource = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val morphSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorph.kt").readText()
        val capture = morphSource.substringAfter("internal fun captureMediaGridMorphInput")
            .substringBefore("internal fun mediaGridMorphOrdinalRange")
        val pointer = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphInteraction.kt").readText()

        assertTrue(capture.contains("itemIndexByMediaOrdinal.getOrNull(ordinal)"))
        assertTrue(capture.contains("for (ordinal in startOrdinal..endOrdinal)"))
        assertTrue(!capture.contains("frame.items.forEach") && !capture.contains("frame.itemByKey"))
        assertTrue(uiSource.contains("withContext(Dispatchers.Default)"))
        assertTrue(uiSource.contains("if (state.isScrollInProgress)"))
        assertTrue(pointer.contains("mediaGridColumnCountAfterPinchRelease"))
        assertTrue(pointer.contains("MediaGridMorphGestureMode"))
        assertTrue(morphSource.contains("buildMediaGridMorphRowPreparedPairs"))
        assertTrue(pointer.contains("buildMediaGridMorphRowPreparedPairs"))
        assertTrue(!uiSource.contains("mediaGridPinchToResize"))
        assertTrue(uiSource.contains("withContext(Dispatchers.Default)"))
    }

    @Test
    fun morphCanvasHasExplicitTestAndProductionModes() {
        val uiSource = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val rendererSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphRowRenderer.kt").readText()
        val rowSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphRowReflow.kt").readText()

        assertTrue(rendererSource.contains("drawWithCache"))
        assertTrue(rendererSource.contains("onDrawWithContent"))
        assertTrue(rendererSource.contains("val p = progress.value.coerceIn(0f, 1f)"))
        assertTrue(rowSource.contains("startNormalizedLeft"))
        assertTrue(rowSource.contains("startContent"))
        assertTrue(rowSource.contains("endContent"))
        assertTrue(uiSource.contains("testMorphEnabled = BuildConfig.TEST_HARNESS"))
        assertTrue(uiSource.contains("mediaGridMorphRowReflowCanvas"))
        assertTrue(!uiSource.contains("MediaGridMorphProductionHost("))
        assertTrue(!uiSource.contains("handoffVisualTranslation"))
    }

    @Test
    fun morphInteractionSharesOneModifierAcrossTestAndProduction() {
        val uiSource = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val interactionSource = locateSource(
            "src/main/java/com/lyco256/llm/MediaGridMorphInteraction.kt",
        ).readText()

        assertTrue(interactionSource.contains("MediaGridMorphGestureMode.Test"))
        assertTrue(interactionSource.contains("withFrameNanos(controller::advanceSettleFrame)"))
        assertTrue(uiSource.contains("mediaGridMorphGestureInput"))
        assertTrue(uiSource.contains("val testMorphEnabled = BuildConfig.TEST_HARNESS"))
        assertTrue(uiSource.contains("captureOnClaim"))
        assertTrue(uiSource.contains("buildMediaGridMorphRowPreparedPairs"))
        assertTrue(uiSource.contains("onFallbackPinchFinished = { _, nextColumnCount ->"))
        assertTrue(uiSource.contains("mediaGridLegacyPinchToResize"))
        assertTrue(uiSource.contains("mediaGridMorphRowReflowCanvas"))
        assertTrue(uiSource.contains("interactionEnabled = !morphCheckpointSuppressed"))
        assertTrue(uiSource.contains("metadataOverlaysVisible = !morphInteractionLocked"))
        assertTrue(uiSource.contains("enabled = interactionEnabled && filters.hasActiveFilters"))
        assertTrue(!uiSource.contains("MediaGridMorphProductionHost("))
        assertTrue(!uiSource.contains("graphicsLayer {\n                            translationX"))
    }

    @Test
    fun realLazyGridHandoffHostIsTestOnlySingleGridAndEventDriven() {
        val uiSource = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val hostSource = locateSource(
            "src/main/java/com/lyco256/llm/MediaGridMorphLazyGridHandoffTestHost.kt",
        ).readText()
        val coordinatorSource = locateSource(
            "src/main/java/com/lyco256/llm/MediaGridMorphHandoff.kt",
        ).readText()

        assertTrue(hostSource.contains("check(BuildConfig.TEST_HARNESS)"))
        assertEquals(1, Regex("\\bLazyVerticalGrid\\(").findAll(hostSource).count())
        assertTrue(hostSource.contains("GridItemSpan(maxLineSpan)"))
        assertTrue(hostSource.contains("val userScrollEnabled = !handoffSnapshot.suppressesUserScroll"))
        assertTrue(hostSource.contains("userScrollEnabled = userScrollEnabled"))
        assertTrue(!hostSource.contains("delay(") && !hostSource.contains("Thread.sleep"))
        assertTrue(!coordinatorSource.contains("delay(") && !coordinatorSource.contains("Thread.sleep"))
        assertTrue(!coordinatorSource.contains("Bitmap"))
        assertTrue(!coordinatorSource.contains("ImageBitmap"))
        assertTrue(!coordinatorSource.contains("ImageRequest"))
        assertTrue(!coordinatorSource.contains("MediaGridResident"))
        assertTrue(!uiSource.contains("MediaGridMorphLazyGridHandoffTestHost"))
        assertTrue(!uiSource.contains("MediaGridMorphGridHandoffCoordinator"))
    }

    private fun locateSource(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("app", relativePath.removePrefix("src/")),
    ).firstOrNull(File::isFile)
        ?: error("Source file not found: $relativePath")
}
