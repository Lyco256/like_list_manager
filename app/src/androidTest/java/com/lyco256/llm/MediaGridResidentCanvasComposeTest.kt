package com.lyco256.llm

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
            store.retain(index.toLong(), candidate, MemoryCache.Value(bitmaps[index], emptyMap()))
        }
        try {
            composeRule.setContent {
                val state = rememberLazyGridState()
                Box(Modifier.width(240.dp).height(180.dp)) {
                    LazyVerticalGrid(GridCells.Fixed(4), state = state, modifier = Modifier.fillMaxSize()) {
                        items(cells, key = { it.key }) { Box(Modifier.size(60.dp)) }
                    }
                    MediaGridResidentCanvasLayer(frame, state, store, MediaGridResidentCanvasMode.TestVisible)
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
}
