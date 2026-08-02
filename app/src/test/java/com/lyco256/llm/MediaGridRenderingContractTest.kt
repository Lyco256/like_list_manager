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
        assertEquals(3, Regex("zIndex\\(ClassifiedMediaGridToolbarZIndex\\)").findAll(source).count())
        val filterToolbar = source.substringAfter("private fun TagFilterSummaryRow").substringBefore("internal fun filterConditionSummary")
        val selectionToolbar = source.substringAfter("private fun MediaGridSelectionToolbar").substringBefore("internal enum class BulkTagAggregate")
        assertTrue(filterToolbar.contains("Row("))
        assertTrue(selectionToolbar.contains("Row("))
        assertTrue(!filterToolbar.contains("Surface(") && !selectionToolbar.contains("Surface("))
        assertTrue(source.contains("background(MaterialTheme.colorScheme.surface)"))
        assertTrue(source.contains("padding(top = 12.dp, start = 12.dp, end = 12.dp)"))
        assertTrue(source.contains("Spacer(Modifier.height(10.dp))"))
        assertTrue(source.contains("padding(start = 12.dp, end = 12.dp, bottom = 12.dp)"))
        assertTrue(source.contains("testTag(\"media_grid_selection_toolbar\")"))
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
    fun preparedIndexFeedsTheSingleSurfaceBeforeDraw() {
        val source = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val prepared = source.indexOf("val residentPreparedIndex")
        val draw = source.indexOf("Modifier.mediaGridSingleSurface")
        assertTrue(prepared >= 0)
        assertTrue(draw > prepared)
        assertTrue(source.substring(prepared, draw).contains("remember(frame.key, retainedImageStore, residentCanvasAdapter, residentDrawIndexVersion)"))
        assertTrue(source.substring(prepared, draw).contains("drawIndexSnapshot()"))
        assertTrue(source.substring(prepared, draw).contains("residentCanvasMode != MediaGridResidentCanvasMode.Disabled"))
        assertTrue(source.substring(draw).contains("morphModel = morphRowRenderModel"))
        assertTrue(source.substring(draw).contains("mode = singleSurfaceMode"))
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
        val claimPreparation = uiSource.substringAfter("prepareClaimBundle =")
            .substringBefore("latestPairs =")
        assertTrue(!claimPreparation.contains("state.isScrollInProgress"))
        assertTrue(uiSource.contains("visibleItemGeometry"))
        assertTrue(pointer.contains("mediaGridMorphCanonicalReleaseDecision"))
        assertTrue(pointer.contains("MediaGridMorphGestureMode"))
        assertTrue(pointer.contains("never claim a stale bundle"))
        assertTrue(pointer.contains("mediaGridMorphClaimBundleMatchesIdentity(claimBundle, latestIdentity)"))
        assertTrue(!pointer.contains("mode == MediaGridMorphGestureMode.Production ||"))
        assertTrue(!pointer.contains("centroidMovement"))
        assertTrue(!pointer.contains("currentCentroid * 0.5f"))
        assertTrue(morphSource.contains("buildMediaGridMorphRowPreparedPairs"))
        assertTrue(pointer.contains("buildMediaGridMorphRowPreparedPairs"))
        assertTrue(!uiSource.contains("mediaGridPinchToResize"))
        assertTrue(uiSource.contains("withContext(Dispatchers.Default)"))
    }

    @Test
    fun morphCanvasHasOneProductionSurfaceAndFrozenDrawModel() {
        val uiSource = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val rendererSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphRowRenderer.kt").readText()
        val rowSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphRowReflow.kt").readText()
        val surfaceSource = locateSource("src/main/java/com/lyco256/llm/MediaGridResidentCanvas.kt").readText()

        assertTrue(rendererSource.contains("buildMediaGridMorphClaimBundle"))
        assertTrue(rendererSource.contains("buildMediaGridMorphRowRenderModel"))
        assertTrue(rendererSource.contains("MediaGridMorphTextResourceIndex"))
        assertTrue(!rendererSource.contains("rememberMediaGridMorphRowRenderModel"))
        assertTrue(rendererSource.contains("protectedAssetIds"))
        assertTrue(rendererSource.contains("requiredSourceImageCount"))
        assertTrue(rendererSource.contains("requiredTargetImageCount"))
        assertTrue(rendererSource.contains("unresolvedRequiredAssetId"))
        assertTrue(rendererSource.contains("val p = progress.coerceIn(0f, 1f)"))
        assertTrue(!rendererSource.contains("progress.value"))
        assertTrue(surfaceSource.contains("MediaGridSingleSurfaceMode.Normal"))
        assertTrue(surfaceSource.contains("MediaGridSingleSurfaceMode.Morph"))
        assertTrue(surfaceSource.contains("MediaGridSingleSurfaceMode.RevealCurrent"))
        assertTrue(surfaceSource.contains("MediaGridSingleSurfaceMode.RevealTarget"))
        assertTrue(surfaceSource.contains("drawMediaGridMorphRow(model, progress.value)"))
        assertTrue(surfaceSource.contains("val snapshot = morphSnapshot?.value"))
        assertTrue(uiSource.contains("morphSnapshot = morphController?.snapshotState"))
        assertTrue(rowSource.contains("sourceCellSize"))
        assertTrue(rowSource.contains("targetCellSize"))
        assertTrue(rowSource.contains("fixedFocalCenterY"))
        assertTrue(rowSource.contains("relativeRowRange"))
        assertTrue(rowSource.contains("startContent"))
        assertTrue(rowSource.contains("endContent"))
        assertTrue(uiSource.contains("morphEnabled = !selectionMode && !showProgress"))
        assertTrue(uiSource.contains("MediaGridMorphProductionHandoffEffects("))
        assertTrue(uiSource.contains("mediaGridSingleSurface"))
        assertTrue(!uiSource.contains("testMorphEnabled"))
        assertTrue(!uiSource.contains("mediaGridMorphRowReflowCanvas"))
        assertTrue(!uiSource.contains("mediaGridLegacyPinchToResize"))
        assertTrue(!uiSource.contains("handoffVisualTranslation"))
    }

    @Test
    fun productionRowRendererUsesUniformLatticeInsteadOfEndpointRectLerp() {
        val rowSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphRowReflow.kt").readText()
        val rendererSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphRowRenderer.kt").readText()
        assertTrue(rowSource.contains("mediaGridMorphCurrentCellSize"))
        assertTrue(rowSource.contains("gridLeft + cell.column * currentCellSize"))
        assertTrue(rowSource.contains("fixedFocalCenterY - focalV * cellSize"))
        assertTrue(!rendererSource.contains("cell.plan.startRect"))
        assertTrue(!rendererSource.contains("cell.plan.endRect"))
        assertTrue(!rendererSource.contains("sourceTop"))
        assertTrue(!rendererSource.contains("targetTop"))
        assertTrue(rendererSource.contains("cell.plan.column * currentCellSize"))
        assertTrue(rendererSource.contains("cell.plan.relativeRow * currentCellSize"))
        assertTrue(rendererSource.contains("fixedFocalCenterY - model.focalV * currentCellSize"))
        assertTrue(rendererSource.contains("clipRect(0f, 0f, viewportWidth, viewportHeight)"))
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
        assertTrue(uiSource.contains("mode = MediaGridMorphGestureMode.Production"))
        assertTrue(uiSource.contains("prepareClaimBundle"))
        assertTrue(!uiSource.contains("captureOnClaim"))
        assertTrue(uiSource.contains("buildMediaGridMorphRowPreparedPairs"))
        assertTrue(uiSource.contains("onFallbackPinchFinished = { _, nextColumnCount ->"))
        assertTrue(uiSource.contains("MediaGridMorphProductionHandoffEffects("))
        assertTrue(uiSource.contains("mediaGridSingleSurface"))
        assertTrue(uiSource.contains("interactionEnabled = !morphCheckpointSuppressed"))
        assertTrue(uiSource.contains("metadataOverlaysVisible = !morphInteractionLocked"))
        assertTrue(uiSource.contains("enabled = interactionEnabled && filters.hasActiveFilters"))
        assertTrue(!uiSource.contains("mediaGridLegacyPinchToResize"))
        assertTrue(!uiSource.contains("mediaGridMorphRowReflowCanvas"))
    }

    @Test
    fun productionMorphProtectsFullRowEndpointsAndKeepsDrawHotPathPure() {
        val uiSource = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val rendererSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphRowRenderer.kt").readText()
        val surfaceSource = locateSource("src/main/java/com/lyco256/llm/MediaGridResidentCanvas.kt").readText()
        val hostSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphProductionHost.kt").readText()
        assertTrue(hostSource.contains("setProtectionCallbacks"))
        val interactionSource = locateSource("src/main/java/com/lyco256/llm/MediaGridMorphInteraction.kt").readText()
        assertTrue(interactionSource.contains("onProtectAssets"))
        assertTrue(interactionSource.contains("onReleaseAssets"))
        assertTrue(rendererSource.contains("cell.plan.startContent.assetIdOrNull()"))
        assertTrue(rendererSource.contains("cell.plan.endContent.assetIdOrNull()"))
        assertTrue(surfaceSource.contains("val commands = ArrayList<MediaGridResidentDrawCommand>"))
        val singleSurfaceDraw = surfaceSource
            .substringAfter("internal fun Modifier.mediaGridSingleSurface")
            .substringAfter("onDrawWithContent")
        assertTrue(singleSurfaceDraw.contains("while (index < commands.size)"))
        assertTrue(!singleSurfaceDraw.contains("layout.visibleItemsInfo"))
        assertTrue(!singleSurfaceDraw.contains("preparedImageByAssetId["))
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

    @Test
    fun productionUsesOnlyUnifiedSurfaceAndTestHostHasNoProductionCanvasMode() {
        val uiSource = locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
        val hostSource = locateSource(
            "src/main/java/com/lyco256/llm/MediaGridMorphProductionHost.kt",
        ).readText()
        val canvasSource = locateSource(
            "src/main/java/com/lyco256/llm/MediaGridMorphCanvas.kt",
        ).readText()
        assertTrue(uiSource.contains("MediaGridMorphProductionHandoffEffects("))
        assertTrue(!uiSource.contains("MediaGridMorphCanvasLayer"))
        assertTrue(hostSource.contains("MediaGridMorphTestHandoffHost"))
        assertTrue(hostSource.contains("check(BuildConfig.TEST_HARNESS)"))
        assertTrue(!hostSource.contains("MediaGridMorphCanvasMode.ProductionVisible"))
        assertTrue(!hostSource.contains("activePlan!!.slots"))
        assertTrue(!canvasSource.contains("ProductionVisible"))
    }

    private fun locateSource(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("app", relativePath.removePrefix("src/")),
    ).firstOrNull(File::isFile)
        ?: error("Source file not found: $relativePath")
}
