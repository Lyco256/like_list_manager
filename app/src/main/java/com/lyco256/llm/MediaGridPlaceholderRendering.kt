package com.lyco256.llm

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lyco256.llm.data.MediaGridThumbnailSource
import com.lyco256.llm.data.MediaGridThumbnailState

internal enum class MediaGridCellVisualState {
    Placeholder,
    Image,
    Error,
}

/** Identifies the image model that the cell has acknowledged through AsyncImage.onSuccess. */
internal data class MediaGridImageModelKey(
    val source: MediaGridThumbnailSource,
    val filePath: String,
)

internal fun mediaGridImageModelKey(
    source: MediaGridThumbnailSource,
    filePath: String,
): MediaGridImageModelKey = MediaGridImageModelKey(source, filePath)

internal fun mediaGridCellVisualState(
    thumbnailState: MediaGridThumbnailState,
    hasUsableSource: Boolean,
    downloadFailed: Boolean,
    currentImageModel: MediaGridImageModelKey?,
    displayedImageModel: MediaGridImageModelKey?,
): MediaGridCellVisualState {
    if (thumbnailState is MediaGridThumbnailState.Failed || !hasUsableSource || downloadFailed) {
        return MediaGridCellVisualState.Error
    }
    return if (
        thumbnailState is MediaGridThumbnailState.Ready &&
        currentImageModel != null &&
        currentImageModel == displayedImageModel
    ) {
        MediaGridCellVisualState.Image
    } else {
        MediaGridCellVisualState.Placeholder
    }
}

/**
 * Draws only a cell-local, static placeholder. The brush and its size-dependent offsets are
 * created by Compose's cache and are not recreated for every draw frame.
 */
internal fun Modifier.mediaGridPlaceholder(
    visualState: MediaGridCellVisualState,
    startColor: Color,
    endColor: Color,
): Modifier {
    if (visualState != MediaGridCellVisualState.Placeholder) return this
    return drawWithCache {
        val brush = Brush.linearGradient(
            colors = listOf(startColor, endColor),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawBehind { drawRect(brush) }
    }
}
