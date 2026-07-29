package com.lyco256.llm

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ApplicationProvider
import coil.memory.MemoryCache
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import kotlin.math.abs

class MediaGridResidentCanvasComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun testVisibleDrawsTwelveDistinctRgb565CellsOnOneCanvasWithoutTakingInputLayer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val memoryCache = MemoryCache.Builder(context).maxSizeBytes(16 * 1024 * 1024).build()
        val store = MediaGridRetainedImageStore(memoryCache)
        val colors = intArrayOf(
            Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA,
            Color.WHITE, Color.BLACK, Color.rgb(255, 128, 0), Color.rgb(128, 0, 255),
            Color.rgb(0, 128, 128), Color.rgb(128, 128, 0),
        )
        val bitmaps = (0 until 12).map { index ->
            Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565).also { it.eraseColor(colors[index]) }
        }
        val cells = (0 until 12).map { index ->
            val entry = MediaGridEntry(index.toLong(), 1L, index.toLong(), "media-$index", 0, "photo", null, "ready", null, "2026-01-01T00:00:00Z", null)
            MediaGridCellItem("media_grid_item_${index}", entry, index)
        }
        val frame = MediaGridFrameData(
            key = MediaGridRenderKey(MediaGridDataKey(1L, 1L, TweetFilterState(), ClassifiedSortState()), 4),
            items = cells,
            itemByKey = cells.associateBy { it.key },
            mediaCellIndices = IntArray(12) { it },
        )
        cells.forEachIndexed { index, cell ->
            val candidate = MediaGridPreparedCandidate(MediaGridImageSourceKind.Rgb565Pack, index.toLong(), "source-$index", "cache-$index", 256, 256)
            store.retain(index.toLong(), candidate, MemoryCache.Value(bitmaps[index], emptyMap()), directDrawEligible = true)
        }
        try {
            composeRule.setContent {
                val state = rememberLazyGridState()
                val adapter = remember { MediaGridResidentCanvasImageAdapter() }
                Box(Modifier.width(240.dp).height(180.dp)) {
                    LazyVerticalGrid(
                        GridCells.Fixed(4),
                        state = state,
                        modifier = Modifier.fillMaxSize().mediaGridResidentCanvas(
                            frame = frame,
                            state = state,
                            retainedImageStore = store,
                            adapter = adapter,
                            drawIndexVersion = store.drawIndexSnapshot().version,
                            mode = MediaGridResidentCanvasMode.TestVisible,
                        ),
                    ) {
                        items(cells, key = { it.key }) { Box(Modifier.size(60.dp)) }
                    }
                }
            }
            composeRule.waitForIdle()
            val pixels = composeRule.onNodeWithTag("media_grid_resident_canvas").captureToImage().toPixelMap()
            val cellWidth = pixels.width / 4
            val cellHeight = pixels.height / 3
            (0 until 12).forEach { index ->
                val color = pixels[(index % 4) * cellWidth + cellWidth / 2, (index / 4) * cellHeight + cellHeight / 2]
                val expected = bitmaps[index].getPixel(128, 128)
                assertTrue("cell=$index red expected=${Color.red(expected)} actual=${color.red}", abs(Color.red(expected) - color.red * 255f) <= 8)
                assertTrue("cell=$index green expected=${Color.green(expected)} actual=${color.green}", abs(Color.green(expected) - color.green * 255f) <= 8)
                assertTrue("cell=$index blue expected=${Color.blue(expected)} actual=${color.blue}", abs(Color.blue(expected) - color.blue * 255f) <= 8)
            }
        } finally {
            store.clear()
            bitmaps.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun residentImagesAreClippedToGridViewportBeforeDrawContent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val memoryCache = MemoryCache.Builder(context).maxSizeBytes(4 * 1024 * 1024).build()
        val store = MediaGridRetainedImageStore(memoryCache)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565).also { it.eraseColor(Color.RED) }
        val entry = MediaGridEntry(1L, 1L, 1L, "media-clip", 0, "photo", null, "ready", null, "2026-01-01T00:00:00Z", null)
        val cell = MediaGridCellItem("media_grid_item_1", entry, 0)
        val frame = MediaGridFrameData(
            key = MediaGridRenderKey(MediaGridDataKey(3L, 3L, TweetFilterState(), ClassifiedSortState()), 2),
            items = listOf(cell),
            itemByKey = mapOf(cell.key to cell),
            mediaCellIndices = intArrayOf(0),
        )
        store.retain(
            assetId = 1L,
            candidate = MediaGridPreparedCandidate(MediaGridImageSourceKind.Rgb565Pack, 1L, "source-clip", "cache-clip", 256, 256),
            value = MemoryCache.Value(bitmap, emptyMap()),
            directDrawEligible = true,
        )
        try {
            composeRule.setContent {
                val state = rememberLazyGridState()
                val adapter = remember { MediaGridResidentCanvasImageAdapter() }
                Box(
                    Modifier
                        .width(160.dp)
                        .height(160.dp)
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .testTag("resident_clip_parent"),
                ) {
                    LazyVerticalGrid(
                        GridCells.Fixed(1),
                        state = state,
                        modifier = Modifier
                            .width(120.dp)
                            .requiredHeight(100.dp)
                            .mediaGridResidentCanvas(
                                frame = frame,
                                state = state,
                                retainedImageStore = store,
                                adapter = adapter,
                                drawIndexVersion = store.drawIndexSnapshot().version,
                                mode = MediaGridResidentCanvasMode.TestVisible,
                            ),
                    ) {
                        items(listOf(cell), key = { it.key }) {
                            Box(Modifier.height(140.dp))
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            val pixels = composeRule.onNodeWithTag("resident_clip_parent").captureToImage().toPixelMap()
            val centerX = pixels.width / 2
            val insideY = pixels.height * 0.3f
            val outsideY = pixels.height * 0.95f
            assertTrue(pixels[centerX, insideY.toInt()].red > 0.9f)
            assertTrue(pixels[centerX, outsideY.toInt()].red < 0.1f)
            assertTrue(pixels[centerX, outsideY.toInt()].green < 0.1f)
            assertTrue(pixels[centerX, outsideY.toInt()].blue < 0.1f)
        } finally {
            store.clear()
            bitmap.recycle()
        }
    }

    @Test
    fun threeHundredEligibleRgb565AssetsAreDrawnAfterOneJumpFrame() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val memoryCache = MemoryCache.Builder(context).maxSizeBytes(64 * 1024 * 1024).build()
        val store = MediaGridRetainedImageStore(memoryCache)
        val bitmaps = (0 until 300).map { index ->
            Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565).also {
                it.eraseColor(Color.rgb(index % 256, (index * 3) % 256, (index * 7) % 256))
            }
        }
        val cells = bitmaps.indices.map { index ->
            val entry = MediaGridEntry(index.toLong(), index.toLong(), index.toLong(), "media-$index", 0, "photo", null, "ready", null, "2026-01-01T00:00:00Z", null)
            MediaGridCellItem("media_grid_item_${index}", entry, index)
        }
        val frame = MediaGridFrameData(
            key = MediaGridRenderKey(MediaGridDataKey(2L, 2L, TweetFilterState(), ClassifiedSortState()), 4),
            items = cells,
            itemByKey = cells.associateBy { it.key },
            mediaCellIndices = IntArray(cells.size) { it },
        )
        cells.forEachIndexed { index, _ ->
            val candidate = MediaGridPreparedCandidate(MediaGridImageSourceKind.Rgb565Pack, index.toLong(), "source-$index", "cache-$index", 256, 256)
            store.retain(index.toLong(), candidate, MemoryCache.Value(bitmaps[index], emptyMap()), directDrawEligible = true)
        }
        try {
            lateinit var gridState: androidx.compose.foundation.lazy.grid.LazyGridState
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent {
                val state = rememberLazyGridState()
                gridState = state
                val adapter = remember { MediaGridResidentCanvasImageAdapter() }
                LaunchedEffect(Unit) { state.scrollToItem(200) }
                LazyVerticalGrid(
                    GridCells.Fixed(4),
                    state = state,
                    modifier = Modifier.width(240.dp).height(240.dp).mediaGridResidentCanvas(
                        frame = frame,
                        state = state,
                        retainedImageStore = store,
                        adapter = adapter,
                        drawIndexVersion = store.drawIndexSnapshot().version,
                        mode = MediaGridResidentCanvasMode.TestVisible,
                    ),
                ) {
                    items(cells, key = { it.key }) { Box(Modifier.fillMaxSize()) }
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            var visibleAssets = emptyList<Long>()
            var commandCount = -1
            composeRule.runOnIdle {
                visibleAssets = gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                    (frame.itemByKey[info.key] as? MediaGridCellItem)?.entry?.assetId
                }
                commandCount = visibleAssets.count { store.hasEligibleDrawHandle(it) }
            }
            assertTrue(visibleAssets.isNotEmpty())
            assertTrue(visibleAssets.maxOrNull()!! >= 200L)
            assertEquals(300, store.stats().entryCount)
            assertEquals(300, store.stats().eligibleEntryCount)
            assertTrue(store.stats().estimatedBytes <= 48L * 1024L * 1024L)
            assertEquals(visibleAssets.size, commandCount)
            visibleAssets.forEach { assertTrue(store.hasEligibleDrawHandle(it)) }
            composeRule.onNodeWithTag("media_grid_resident_canvas").assertExists()
        } finally {
            store.clear()
            bitmaps.forEach(Bitmap::recycle)
        }
    }

}
