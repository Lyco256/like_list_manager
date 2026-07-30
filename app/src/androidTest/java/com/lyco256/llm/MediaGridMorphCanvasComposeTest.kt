package com.lyco256.llm

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaGridMorphCanvasComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun progressReusesRenderModelImagesAndTextAndOverlayDoesNotTakeInput() {
        val red = solidBitmap(AndroidColor.RED)
        val blue = solidBitmap(AndroidColor.BLUE)
        val prepared = preparedIndex(1L, mapOf(1L to red, 2L to blue))
        val pair = pair(
            viewport = Rect(0f, 0f, 120f, 120f),
            slots = listOf(slot(Rect(0f, 40f, 120f, 120f), 1L, 2L)),
            headers = listOf(header("開始見出し", "終了見出し", Rect(0f, 0f, 120f, 40f))),
        )
        val plan = MediaGridMorphPlan(pair, null)
        val modelBuilds = AtomicInteger()
        val imageResolutions = AtomicInteger()
        val textMeasures = AtomicInteger()
        val taps = AtomicInteger()
        lateinit var progress: MutableState<Float>
        try {
            composeRule.setContent {
                progress = remember { mutableStateOf(0f) }
                val correction = remember { mutableStateOf(Offset.Zero) }
                MaterialTheme {
                    Box(
                        Modifier
                            .requiredSize(120.dp / androidx.compose.ui.platform.LocalDensity.current.density)
                            .testTag("morph_input_root"),
                    ) {
                        Button(
                            onClick = { taps.incrementAndGet() },
                            modifier = Modifier.fillMaxSize().testTag("morph_underlay_button"),
                        ) { }
                        MediaGridMorphCanvasLayer(
                            plan = plan,
                            preparedIndex = prepared,
                            progress = progress,
                            correction = correction,
                            mode = MediaGridMorphCanvasMode.TestVisible,
                            onRenderModelBuilt = { modelBuilds.incrementAndGet() },
                            onImageResolved = { imageResolutions.incrementAndGet() },
                            onTextMeasured = { textMeasures.incrementAndGet() },
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            assertEquals(1, modelBuilds.get())
            assertEquals(2, imageResolutions.get())
            assertEquals(2, textMeasures.get())
            composeRule.onAllNodesWithTag("media_grid_morph_canvas").assertCountEquals(1)

            composeRule.runOnIdle {
                repeat(40) { progress.value = it / 39f }
            }
            composeRule.waitForIdle()
            assertEquals(1, modelBuilds.get())
            assertEquals(2, imageResolutions.get())
            assertEquals(2, textMeasures.get())

            composeRule.onNodeWithTag("morph_input_root").performTouchInput { click(center) }
            composeRule.waitForIdle()
            assertEquals(1, taps.get())
        } finally {
            red.recycle()
            blue.recycle()
        }
    }

    @Test
    fun pairAndPreparedIndexVersionEachRebuildExactlyOnceWithoutRemeasuringSameTitle() {
        val red = solidBitmap(AndroidColor.RED)
        val prepared1 = preparedIndex(1L, mapOf(1L to red))
        val prepared2 = prepared1.copy(drawIndexVersion = 2L)
        val originalPair = pair(
            viewport = Rect(0f, 0f, 100f, 100f),
            slots = listOf(slot(Rect(0f, 30f, 100f, 100f), 1L, 1L)),
            headers = listOf(header("見出し", "見出し", Rect(0f, 0f, 100f, 30f))),
        )
        val changedPair = originalPair.copy(
            sourceRevision = 2L,
            slots = listOf(slot(Rect(0f, 30f, 100f, 100f), 1L, 1L).copy(
                endRect = Rect(0f, 30f, 99f, 99f),
            )),
        )
        val modelBuilds = AtomicInteger()
        val imageResolutions = AtomicInteger()
        val textMeasures = AtomicInteger()
        lateinit var planState: MutableState<MediaGridMorphPlan>
        lateinit var indexState: MutableState<MediaGridResidentCanvasPreparedIndex>
        try {
            composeRule.setContent {
                planState = remember { mutableStateOf(MediaGridMorphPlan(originalPair, null)) }
                indexState = remember { mutableStateOf(prepared1) }
                MaterialTheme {
                    MediaGridMorphCanvasLayer(
                        plan = planState.value,
                        preparedIndex = indexState.value,
                        progress = remember { mutableStateOf(0.5f) },
                        correction = remember { mutableStateOf(Offset.Zero) },
                        mode = MediaGridMorphCanvasMode.TestVisible,
                        modifier = Modifier.requiredSize(100.dp / androidx.compose.ui.platform.LocalDensity.current.density),
                        onRenderModelBuilt = { modelBuilds.incrementAndGet() },
                        onImageResolved = { imageResolutions.incrementAndGet() },
                        onTextMeasured = { textMeasures.incrementAndGet() },
                    )
                }
            }
            composeRule.waitForIdle()
            assertEquals(1, modelBuilds.get())
            assertEquals(1, imageResolutions.get())
            assertEquals(1, textMeasures.get())

            composeRule.runOnIdle { planState.value = MediaGridMorphPlan(changedPair, null) }
            composeRule.waitForIdle()
            assertEquals(2, modelBuilds.get())
            assertEquals(2, imageResolutions.get())
            assertEquals(1, textMeasures.get())

            composeRule.runOnIdle { indexState.value = prepared2 }
            composeRule.waitForIdle()
            assertEquals(3, modelBuilds.get())
            assertEquals(3, imageResolutions.get())
            assertEquals(1, textMeasures.get())
        } finally {
            red.recycle()
        }
    }

    @Test
    fun disabledCreatesNoCanvasModelImageResolutionOrTextMeasurement() {
        val pair = pair(
            viewport = Rect(0f, 0f, 100f, 100f),
            slots = listOf(slot(Rect(0f, 0f, 100f, 100f), 1L, 1L)),
            headers = listOf(header("見出し", "見出し", Rect(0f, 0f, 100f, 30f))),
        )
        val modelBuilds = AtomicInteger()
        val imageResolutions = AtomicInteger()
        val textMeasures = AtomicInteger()
        composeRule.setContent {
            MediaGridMorphCanvasLayer(
                plan = MediaGridMorphPlan(pair, null),
                preparedIndex = preparedIndex(1L, emptyMap()),
                progress = mutableStateOf(0f),
                correction = mutableStateOf(Offset.Zero),
                mode = MediaGridMorphCanvasMode.Disabled,
                onRenderModelBuilt = { modelBuilds.incrementAndGet() },
                onImageResolved = { imageResolutions.incrementAndGet() },
                onTextMeasured = { textMeasures.incrementAndGet() },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("media_grid_morph_canvas").assertDoesNotExist()
        assertEquals(0, modelBuilds.get())
        assertEquals(0, imageResolutions.get())
        assertEquals(0, textMeasures.get())
    }

    @Test
    fun differentImagesContributeLinearlyAtQuarterHalfAndThreeQuarters() {
        val red = solidBitmap(AndroidColor.RED)
        val blue = solidBitmap(AndroidColor.BLUE)
        val prepared = preparedIndex(1L, mapOf(1L to red, 2L to blue))
        val plan = MediaGridMorphPlan(
            pair(
                viewport = Rect(0f, 0f, 100f, 100f),
                slots = listOf(slot(Rect(0f, 0f, 100f, 100f), 1L, 2L)),
            ),
            null,
        )
        lateinit var progress: MutableState<Float>
        try {
            composeRule.setContent {
                progress = remember { mutableStateOf(0f) }
                Box(
                    Modifier
                        .requiredSize(100.dp / androidx.compose.ui.platform.LocalDensity.current.density)
                        .background(Color.Black)
                        .testTag("morph_blend_root"),
                ) {
                    MediaGridMorphCanvasLayer(
                        plan = plan,
                        preparedIndex = prepared,
                        progress = progress,
                        correction = remember { mutableStateOf(Offset.Zero) },
                        mode = MediaGridMorphCanvasMode.TestVisible,
                    )
                }
            }
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { value ->
                composeRule.runOnIdle { progress.value = value }
                composeRule.waitForIdle()
                val pixels = composeRule.onNodeWithTag("morph_blend_root").captureToImage().toPixelMap()
                val color = pixels[pixels.width / 2, pixels.height / 2]
                assertChannel(1f - value, color.red)
                assertChannel(0f, color.green)
                assertChannel(value, color.blue)
                assertChannel(1f, color.alpha)
            }
        } finally {
            red.recycle()
            blue.recycle()
        }
    }

    @Test
    fun sameAssetDrawsOnceSingleSidedAssetsFadeAndResidentMissIsTransparent() {
        val red = solidBitmap(AndroidColor.RED)
        val green = solidBitmap(AndroidColor.GREEN)
        val blue = solidBitmap(AndroidColor.BLUE)
        val prepared = preparedIndex(1L, mapOf(1L to red, 2L to green, 3L to blue))
        val slots = listOf(
            slot(Rect(0f, 0f, 50f, 50f), 1L, 1L),
            slot(Rect(50f, 0f, 100f, 50f), 2L, null),
            slot(Rect(100f, 0f, 150f, 50f), null, 3L),
            slot(Rect(150f, 0f, 200f, 50f), 99L, 100L),
        )
        try {
            composeRule.setContent {
                Box(
                    Modifier
                        .requiredSize(
                            200.dp / androidx.compose.ui.platform.LocalDensity.current.density,
                            50.dp / androidx.compose.ui.platform.LocalDensity.current.density,
                        )
                        .background(Color.Black)
                        .testTag("morph_layer_cases"),
                ) {
                    MediaGridMorphCanvasLayer(
                        plan = MediaGridMorphPlan(pair(Rect(0f, 0f, 200f, 50f), slots), null),
                        preparedIndex = prepared,
                        progress = mutableStateOf(0.5f),
                        correction = mutableStateOf(Offset.Zero),
                        mode = MediaGridMorphCanvasMode.TestVisible,
                    )
                }
            }
            composeRule.waitForIdle()
            val pixels = composeRule.onNodeWithTag("morph_layer_cases").captureToImage().toPixelMap()
            val centers = intArrayOf(25, 75, 125, 175)
            val same = pixels[centers[0], 25]
            val startOnly = pixels[centers[1], 25]
            val endOnly = pixels[centers[2], 25]
            val miss = pixels[centers[3], 25]
            assertChannel(1f, same.red)
            assertChannel(0.5f, startOnly.green)
            assertChannel(0.5f, endOnly.blue)
            assertTrue(miss.red < 0.03f && miss.green < 0.03f && miss.blue < 0.03f)
        } finally {
            red.recycle()
            green.recycle()
            blue.recycle()
        }
    }

    @Test
    fun headerBackgroundExpandsOpaqueAndRemainsFullWidth() {
        val addedBand = MediaGridMorphHeaderBand(
            startKey = null,
            endKey = "week",
            startTitle = null,
            endTitle = "週",
            startRect = Rect(0f, 0f, 120f, 0f),
            endRect = Rect(0f, 0f, 120f, 40f),
            startFirstMediaOrdinal = null,
            endFirstMediaOrdinal = 0,
            startFirstAssetId = null,
            endFirstAssetId = 1L,
        )
        val removedBand = MediaGridMorphHeaderBand(
            startKey = "day",
            endKey = null,
            startTitle = "日",
            endTitle = null,
            startRect = Rect(0f, 50f, 120f, 90f),
            endRect = Rect(0f, 50f, 120f, 50f),
            startFirstMediaOrdinal = 1,
            endFirstMediaOrdinal = null,
            startFirstAssetId = 2L,
            endFirstAssetId = null,
        )
        val plan = MediaGridMorphPlan(
            pair(Rect(0f, 0f, 120f, 100f), emptyList(), listOf(addedBand, removedBand)),
            null,
        )
        lateinit var progress: MutableState<Float>
        composeRule.setContent {
            progress = remember { mutableStateOf(0.5f) }
            Box(
                Modifier
                    .requiredSize(
                        120.dp / androidx.compose.ui.platform.LocalDensity.current.density,
                        100.dp / androidx.compose.ui.platform.LocalDensity.current.density,
                    )
                    .background(Color.Magenta)
                    .testTag("morph_header_root"),
            ) {
                MaterialTheme {
                    MediaGridMorphCanvasLayer(
                        plan = plan,
                        preparedIndex = preparedIndex(1L, emptyMap()),
                        progress = progress,
                        correction = remember { mutableStateOf(Offset.Zero) },
                        mode = MediaGridMorphCanvasMode.TestVisible,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        var pixels = composeRule.onNodeWithTag("morph_header_root").captureToImage().toPixelMap()
        val insideHalf = pixels[pixels.width - 2, 10]
        val outsideHalf = pixels[pixels.width - 2, 30]
        val removedInsideHalf = pixels[pixels.width - 2, 55]
        val removedOutsideHalf = pixels[pixels.width - 2, 75]
        assertChannel(1f, insideHalf.alpha)
        assertTrue(colorDistance(insideHalf, outsideHalf) > 0.2f)
        assertTrue(colorDistance(removedInsideHalf, removedOutsideHalf) > 0.2f)

        composeRule.runOnIdle { progress.value = 0f }
        composeRule.waitForIdle()
        pixels = composeRule.onNodeWithTag("morph_header_root").captureToImage().toPixelMap()
        assertTrue(colorDistance(pixels[pixels.width - 2, 10], outsideHalf) < 0.08f)
        assertTrue(colorDistance(pixels[pixels.width - 2, 75], insideHalf) < 0.08f)

        composeRule.runOnIdle { progress.value = 1f }
        composeRule.waitForIdle()
        pixels = composeRule.onNodeWithTag("morph_header_root").captureToImage().toPixelMap()
        val expanded = pixels[pixels.width - 2, 30]
        val removed = pixels[pixels.width - 2, 55]
        assertChannel(1f, expanded.alpha)
        assertTrue(colorDistance(insideHalf, expanded) < 0.08f)
        assertTrue(colorDistance(outsideHalf, removed) < 0.08f)
    }

    @Test
    fun threeHundredEntryIndexContributesOnlyBoundedSlotReferences() {
        val bitmap = solidBitmap(AndroidColor.CYAN)
        try {
            val images = (1L..300L).associateWith { bitmap }
            val prepared = preparedIndex(9L, images)
            val pair = pair(
                viewport = Rect(0f, 0f, 100f, 100f),
                slots = listOf(slot(Rect(0f, 0f, 100f, 100f), 300L, 300L)),
            )
            val resolved = AtomicInteger()
            val model = buildMediaGridMorphRenderModel(
                plan = MediaGridMorphPlan(pair, null),
                preparedIndex = prepared,
                textLayoutsByTitle = emptyMap(),
                surfaceColor = Color.White,
                textColor = Color.Black,
                horizontalTextPaddingPx = 12f,
                verticalTextPaddingPx = 8f,
                onImageResolved = { resolved.incrementAndGet() },
            )

            assertEquals(300, prepared.entryCount)
            assertEquals(1, model.slots.size)
            assertEquals(1, resolved.get())
            assertSame(prepared.preparedImageByAssetId.getValue(300L), model.slots.single().startImage)
            assertSame(model.slots.single().startImage, model.slots.single().endImage)
        } finally {
            bitmap.recycle()
        }
    }

    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.RGB_565).also { it.eraseColor(color) }

    private fun preparedIndex(
        version: Long,
        bitmaps: Map<Long, Bitmap>,
    ): MediaGridResidentCanvasPreparedIndex {
        val prepared = LinkedHashMap<Long, MediaGridResidentCanvasPreparedImage>(bitmaps.size)
        bitmaps.forEach { (assetId, bitmap) ->
            prepared[assetId] = MediaGridResidentCanvasPreparedImage(
                assetId = assetId,
                identity = MediaGridResidentImageIdentity(assetId, "cache-$assetId", "source-$assetId"),
                image = bitmap.asImageBitmap(),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
            )
        }
        return MediaGridResidentCanvasPreparedIndex(version, Collections.unmodifiableMap(prepared))
    }

    private fun slot(rect: Rect, startAssetId: Long?, endAssetId: Long?) = MediaGridMorphSlot(
        row = 0,
        column = 0,
        startRect = rect,
        endRect = rect,
        startAssetId = startAssetId,
        endAssetId = endAssetId,
        startMediaOrdinal = startAssetId?.toInt(),
        endMediaOrdinal = endAssetId?.toInt(),
    )

    private fun header(
        startTitle: String?,
        endTitle: String?,
        rect: Rect,
    ) = MediaGridMorphHeaderBand(
        startKey = startTitle,
        endKey = endTitle,
        startTitle = startTitle,
        endTitle = endTitle,
        startRect = rect,
        endRect = rect,
        startFirstMediaOrdinal = 0,
        endFirstMediaOrdinal = 0,
        startFirstAssetId = 1L,
        endFirstAssetId = 1L,
    )

    private fun pair(
        viewport: Rect,
        slots: List<MediaGridMorphSlot>,
        headers: List<MediaGridMorphHeaderBand> = emptyList(),
    ): MediaGridMorphPreparedPair {
        val frameKey = MediaGridRenderKey(
            MediaGridDataKey(1L, 1L, TweetFilterState(), ClassifiedSortState()),
            2,
        )
        val emptyLayout = MediaGridMorphLayoutSnapshot(2, viewport, emptyList(), emptyList(), 0..0)
        return MediaGridMorphPreparedPair(
            sourceRevision = 1L,
            frameKey = frameKey,
            fromColumnCount = 2,
            toColumnCount = 3,
            viewport = viewport,
            viewportSignature = MediaGridViewportSignature(
                renderKey = frameKey,
                firstVisibleItemIndex = 0,
                lastVisibleItemIndex = 0,
                firstVisibleMediaOrdinal = 0,
                lastVisibleMediaOrdinal = 0,
                viewportWidthPx = viewport.width.roundToInt(),
                viewportHeightPx = viewport.height.roundToInt(),
                cellSizePx = 50,
                columnCount = 2,
            ),
            startLayout = emptyLayout,
            targetLayout = emptyLayout.copy(columnCount = 3),
            slots = slots,
            headers = headers,
            mediaOrdinalRange = 0..0,
        )
    }

    private fun assertChannel(expected: Float, actual: Float) {
        assertTrue("expected=$expected actual=$actual", abs(expected - actual) <= 0.08f)
    }

    private fun colorDistance(first: Color, second: Color): Float =
        abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue)
}
