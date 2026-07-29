package com.lyco256.llm

import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import com.lyco256.llm.data.MediaGridImagePreparer
import com.lyco256.llm.data.MediaGridPersistentPreviewStore
import com.lyco256.llm.data.MediaGridRgb565PackStore
import com.lyco256.llm.data.NoOpMediaGridRgb565RepairEnqueuer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class MediaGridAnchorPersistenceIntegrationTest {
    private lateinit var coordinator: MediaGridSessionCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageLoader = ImageLoader.Builder(context).build()
        coordinator = MediaGridSessionCoordinator(
            context = context,
            preparer = MediaGridImagePreparer(
                previewStore = MediaGridPersistentPreviewStore(context.filesDir),
                rgb565PackStore = MediaGridRgb565PackStore(context.filesDir),
                rgb565RepairEnqueuer = NoOpMediaGridRgb565RepairEnqueuer,
            ),
            imageLoader = imageLoader,
            onPersistentPreviewError = { _, _ -> },
        )
    }

    @After
    fun tearDown() {
        coordinator.dispose()
    }

    @Test
    fun explicitAnchorSaveStaysWithTargetSessionAndEqualSaveDoesNotPublish() {
        val keyA = MediaGridSessionKey(TweetFilterState(query = "A"), ClassifiedSortState())
        val keyB = MediaGridSessionKey(TweetFilterState(query = "B"), ClassifiedSortState())
        val sourceA = ClassifiedMediaGridState(
            status = MediaGridLoadStatus.Calculating,
            dataKey = MediaGridDataKey(1L, 1L, keyA.filter, keyA.sort),
        )
        val sourceB = sourceA.copy(dataKey = MediaGridDataKey(2L, 1L, keyB.filter, keyB.sort))
        val anchorA = ClassifiedMediaGridScrollAnchor("media_grid_item_1", 3, 12, 4f)
        val anchorA2 = anchorA.copy(index = 8)
        val anchorB = ClassifiedMediaGridScrollAnchor("media_grid_item_2", 5, 20, -2f)

        coordinator.update(sourceA, requestedColumns = 4)
        coordinator.saveAnchor(keyA, anchorA)
        val beforeEqualSave = coordinator.state.value
        coordinator.saveAnchor(keyA, anchorA)
        assertSame(beforeEqualSave, coordinator.state.value)

        coordinator.update(sourceB, requestedColumns = 4)
        coordinator.saveAnchor(keyA, anchorA2)
        assertNull(coordinator.state.value.anchor)
        coordinator.saveAnchor(keyB, anchorB)
        assertNull(coordinator.state.value.anchor)

        coordinator.update(sourceA, requestedColumns = 4)
        assertEquals(anchorA2, coordinator.state.value.anchor)
    }
}
