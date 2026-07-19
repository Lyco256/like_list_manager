package com.lyco256.llm

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal enum class MediaGridCellVisualState {
    Placeholder,
    Image,
    Error,
}

internal fun mediaGridCellVisualState(
    candidateCount: Int,
    failedCandidateIndex: Int,
    displayedCandidateIdentity: String?,
    currentCandidateIdentity: String?,
): MediaGridCellVisualState {
    if (candidateCount == 0 || failedCandidateIndex >= candidateCount) {
        return MediaGridCellVisualState.Error
    }
    return if (currentCandidateIdentity != null && currentCandidateIdentity == displayedCandidateIdentity) {
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
