package com.lyco256.llm.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridPersistentPreviewStoreTest {
    @Test
    fun sampleSizeKeepsTheCropAxisAtOrAboveTargetWhenPossible() {
        assertEquals(8, mediaGridPreviewInSampleSize(4000, 3000))
        assertEquals(4, mediaGridPreviewInSampleSize(1024, 1024))
        assertEquals(1, mediaGridPreviewInSampleSize(255, 4000))
    }

    @Test
    fun sourceRectUsesTheCenteredSquareCrop() {
        assertEquals(MediaGridPreviewSourceRect(100, 0, 300, 200), mediaGridPreviewSourceRect(400, 200))
        assertEquals(MediaGridPreviewSourceRect(0, 100, 200, 300), mediaGridPreviewSourceRect(200, 400))
        assertEquals(MediaGridPreviewSourceRect(0, 0, 200, 200), mediaGridPreviewSourceRect(200, 200))
    }

    @Test
    fun outputPathContainsOnlyTheAssetIdUnderTheVersionedDirectory() {
        val root = File("build/tmp/preview-store-test")
        val store = MediaGridPersistentPreviewStore(root)
        val file = store.previewFile(42L)

        assertEquals(File(root, "media_grid_previews/v1/42.jpg").canonicalFile, file.canonicalFile)
        assertTrue(file.canonicalPath.startsWith(File(root, "media_grid_previews/v1").canonicalPath))
    }
}
