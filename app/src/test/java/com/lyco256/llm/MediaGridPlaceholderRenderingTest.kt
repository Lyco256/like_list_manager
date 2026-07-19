package com.lyco256.llm

import com.lyco256.llm.data.MediaGridThumbnailSource
import com.lyco256.llm.data.MediaGridThumbnailState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MediaGridPlaceholderRenderingTest {
    private val source = MediaGridThumbnailSource(
        assetId = 1L,
        mediaKey = "media-1",
        localPath = "/data/user/0/com.lyco256.llm/files/image.webp",
        previewUrl = null,
        remoteUrl = null,
    )
    private val file = File("/data/user/0/com.lyco256.llm/cache/thumb.jpg")
    private val model = mediaGridImageModelKey(source, file.absolutePath)

    @Test
    fun waitingAndGeneratingArePlaceholders() {
        assertEquals(MediaGridCellVisualState.Placeholder, state(MediaGridThumbnailState.Waiting))
        assertEquals(MediaGridCellVisualState.Placeholder, state(MediaGridThumbnailState.Generating))
    }

    @Test
    fun readyUntilCurrentModelSucceedsIsPlaceholder() {
        assertEquals(MediaGridCellVisualState.Placeholder, state(MediaGridThumbnailState.Ready(file)))
        assertEquals(MediaGridCellVisualState.Image, state(MediaGridThumbnailState.Ready(file), displayed = model))
    }

    @Test
    fun modelChangeDoesNotReusePreviousSuccess() {
        val nextSource = source.copy(mediaKey = "media-2")
        val nextModel = mediaGridImageModelKey(nextSource, file.absolutePath)
        assertEquals(
            MediaGridCellVisualState.Placeholder,
            mediaGridCellVisualState(
                thumbnailState = MediaGridThumbnailState.Ready(file),
                hasUsableSource = true,
                downloadFailed = false,
                currentImageModel = nextModel,
                displayedImageModel = model,
            ),
        )
    }

    @Test
    fun errorsDoNotDrawPlaceholder() {
        assertEquals(MediaGridCellVisualState.Error, state(MediaGridThumbnailState.Failed))
        assertEquals(MediaGridCellVisualState.Error, state(MediaGridThumbnailState.Waiting, hasUsableSource = false))
        assertEquals(MediaGridCellVisualState.Error, state(MediaGridThumbnailState.Waiting, downloadFailed = true))
    }

    @Test
    fun anotherAssetCannotReuseDisplayedModel() {
        val otherSource = source.copy(assetId = 2L)
        val otherModel = mediaGridImageModelKey(otherSource, file.absolutePath)
        assertEquals(
            MediaGridCellVisualState.Placeholder,
            mediaGridCellVisualState(
                thumbnailState = MediaGridThumbnailState.Ready(file),
                hasUsableSource = true,
                downloadFailed = false,
                currentImageModel = otherModel,
                displayedImageModel = model,
            ),
        )
    }

    private fun state(
        thumbnailState: MediaGridThumbnailState,
        displayed: MediaGridImageModelKey? = null,
        hasUsableSource: Boolean = true,
        downloadFailed: Boolean = false,
    ) = mediaGridCellVisualState(
        thumbnailState = thumbnailState,
        hasUsableSource = hasUsableSource,
        downloadFailed = downloadFailed,
        currentImageModel = if (thumbnailState is MediaGridThumbnailState.Ready) model else null,
        displayedImageModel = displayed,
    )
}
