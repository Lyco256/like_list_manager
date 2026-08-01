package com.lyco256.llm

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private const val LegacyPinchScaleStep = 1.12f

internal fun mediaGridLegacyColumnCountForScale(currentColumnCount: Int, scale: Float): Int {
    if (!scale.isFinite() || scale <= 0f) return currentColumnCount
    return when {
        scale >= LegacyPinchScaleStep -> (currentColumnCount + 1).coerceAtMost(ClassifiedMediaGridMaxColumnCount)
        scale <= 1f / LegacyPinchScaleStep -> (currentColumnCount - 1).coerceAtLeast(ClassifiedMediaGridMinColumnCount)
        else -> currentColumnCount
    }
}

/**
 * Legacy release-time one-step resize used while the production Morph is
 * intentionally disabled. The initial distance is fixed for the whole gesture.
 */
@Composable
internal fun Modifier.mediaGridLegacyPinchToResize(
    currentColumnCount: Int,
    onColumnCountChange: (Int) -> Unit,
): Modifier = mediaGridMorphGestureInput(
    mode = MediaGridMorphGestureMode.Production,
    controller = null,
    identity = null,
    preparedPairsSnapshot = { emptyMap() },
    onFallbackPinchFinished = { _, nextColumnCount -> onColumnCountChange(nextColumnCount) },
    fallbackColumnCount = { currentColumnCount },
)
