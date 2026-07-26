package com.lyco256.llm

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import coil.memory.MemoryCache
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MediaGridResidentImageComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fastResidentIsImageOnFirstObservationWithoutPlaceholder() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val candidate = MediaGridPreparedCandidate(
            kind = MediaGridImageSourceKind.Local,
            requestData = "resident",
            sourceIdentity = "resident-source",
            cacheKey = "resident-cache",
            width = 32,
            height = 32,
        )
        val resident = MediaGridResidentImage(
            assetId = 1L,
            candidate = candidate,
            value = MemoryCache.Value(bitmap, emptyMap()),
            estimatedBytes = 32L * 32L * 4L,
        )
        try {
            composeRule.setContent {
                ClassifiedMediaGridCell(
                    entry = MediaGridEntry(1L, 1L, 1L, "resident", 0, "photo", null, "downloaded", null, "2026-07-26T00:00:00Z", null),
                    sort = ClassifiedSortState(),
                    columnCount = 4,
                    selectionMode = false,
                    multiAsset = false,
                    selected = false,
                    onClick = {},
                    onToggleSelection = {},
                    imageLoader = androidx.test.core.app.ApplicationProvider.getApplicationContext<LikeListManagerApp>().container.mediaGridImageLoader,
                    loadState = MediaGridCellLoadState(),
                    residentImage = resident,
                )
            }
            composeRule.onNodeWithTag("media_grid_image_1").assertIsDisplayed()
            assertEquals(0, composeRule.onAllNodesWithTag("media_grid_placeholder_1").fetchSemanticsNodes().size)
        } finally {
            bitmap.recycle()
        }
    }
}
