package com.lyco256.llm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

internal const val MediaGridPositionPillDisplayMillis = 3_000L
internal const val MediaGridPositionPillAnimationMillis = 180

internal data class MediaGridCurrentPosition(
    val frameKey: MediaGridRenderKey,
    val mediaOrdinal: Int,
    val bucketKey: String,
    val label: String,
)

/**
 * Resolves one media ordinal through the same bucket implementation used by
 * the inline grid headers. This is a direct ordinal lookup and does not
 * inspect the viewport or access repository state.
 */
internal fun mediaGridPositionForOrdinal(
    frame: MediaGridFrameData,
    mediaOrdinal: Int,
    sort: ClassifiedSortState,
    columnCount: Int,
): MediaGridCurrentPosition? {
    if (sort.baseOrder == ClassifiedSortBase.Default || mediaOrdinal < 0) return null
    val itemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal.getOrNull(mediaOrdinal) ?: return null
    val cell = frame.items.getOrNull(itemIndex) as? MediaGridCellItem ?: return null
    val bucket = mediaGridMorphBucketSpec(
        xCreatedAt = cell.entry.xCreatedAt,
        likeCount = cell.entry.likeCount,
        baseOrder = sort.baseOrder,
        columnCount = columnCount,
    ) ?: return null
    return MediaGridCurrentPosition(
        frameKey = frame.key,
        mediaOrdinal = mediaOrdinal,
        bucketKey = bucket.key,
        label = bucket.label,
    )
}

internal data class MediaGridPositionPillObservation(
    val anchor: MediaGridViewportAnchorSignature?,
    val scrolling: Boolean,
    val morphing: Boolean,
    val suppressed: Boolean,
    val scrollbarDragSnapshot: MediaGridScrollbarDragSnapshot = MediaGridScrollbarDragSnapshot(),
)

/**
 * Resolves the first media cell at the top side of the viewport.
 *
 * The anchor already contains the visible media ordinal range produced by the
 * grid's lightweight viewport signature. The ordinal is mapped back to one
 * frame item directly; this deliberately does not scan all frame items.
 */
internal fun currentMediaGridPosition(
    frame: MediaGridFrameData,
    anchor: MediaGridViewportAnchorSignature?,
    sort: ClassifiedSortState,
    columnCount: Int,
): MediaGridCurrentPosition? {
    if (anchor == null || anchor.renderKey != frame.key) return null
    val mediaOrdinal = anchor.firstVisibleMediaOrdinal
    return mediaGridPositionForOrdinal(frame, mediaOrdinal, sort, columnCount)
}

internal enum class MediaGridPositionPillPhase {
    Hidden,
    Visible,
    Hiding,
}

internal data class MediaGridPositionPillState(
    val phase: MediaGridPositionPillPhase = MediaGridPositionPillPhase.Hidden,
    val frameKey: MediaGridRenderKey? = null,
    val label: String? = null,
    val generation: Long = 0L,
    val hideScheduled: Boolean = false,
    val hideRequestedDuringMorph: Boolean = false,
    val morphActive: Boolean = false,
)

internal sealed interface MediaGridPositionPillEvent {
    data class FrameChanged(
        val frameKey: MediaGridRenderKey,
        val hasBuckets: Boolean,
        val morphing: Boolean,
    ) : MediaGridPositionPillEvent

    data object Suppressed : MediaGridPositionPillEvent

    data class ScrollStarted(val position: MediaGridCurrentPosition?) : MediaGridPositionPillEvent

    data class ScrollbarDragFinished(val position: MediaGridCurrentPosition?) : MediaGridPositionPillEvent

    data class PositionChanged(val position: MediaGridCurrentPosition?) : MediaGridPositionPillEvent

    data object ScrollStopped : MediaGridPositionPillEvent

    data class HideTimeout(val generation: Long) : MediaGridPositionPillEvent

    data class ExitAnimationFinished(val generation: Long) : MediaGridPositionPillEvent

    data object MorphStarted : MediaGridPositionPillEvent

    data class MorphFinished(
        val frameKey: MediaGridRenderKey,
        val position: MediaGridCurrentPosition?,
    ) : MediaGridPositionPillEvent
}

internal fun reduceMediaGridPositionPillState(
    state: MediaGridPositionPillState,
    event: MediaGridPositionPillEvent,
): MediaGridPositionPillState = when (event) {
    is MediaGridPositionPillEvent.FrameChanged -> {
        if (!event.hasBuckets) {
            hiddenPositionPill(state, event.frameKey, morphActive = event.morphing)
        } else if (event.morphing && state.phase != MediaGridPositionPillPhase.Hidden && state.label != null) {
            state.copy(morphActive = true)
        } else {
            hiddenPositionPill(state, event.frameKey, morphActive = event.morphing)
        }
    }

    MediaGridPositionPillEvent.Suppressed -> hiddenPositionPill(state, state.frameKey, morphActive = false)

    is MediaGridPositionPillEvent.ScrollStarted -> {
        val position = event.position
        if (state.morphActive || position == null) {
            state
        } else {
            state.copy(
                phase = MediaGridPositionPillPhase.Visible,
                frameKey = position.frameKey,
                label = position.label,
                generation = state.generation + 1L,
                hideScheduled = false,
                hideRequestedDuringMorph = false,
            )
        }
    }

    is MediaGridPositionPillEvent.ScrollbarDragFinished -> {
        val position = event.position
        if (state.morphActive || position == null) {
            if (position == null) hiddenPositionPill(state, state.frameKey, morphActive = false) else state
        } else {
            state.copy(
                phase = MediaGridPositionPillPhase.Visible,
                frameKey = position.frameKey,
                label = position.label,
                generation = state.generation + 1L,
                hideScheduled = true,
                hideRequestedDuringMorph = false,
            )
        }
    }

    is MediaGridPositionPillEvent.PositionChanged -> {
        val position = event.position
        if (state.morphActive || state.phase == MediaGridPositionPillPhase.Hidden || position == null) {
            state
        } else if (state.frameKey == position.frameKey) {
            state.copy(label = position.label, hideRequestedDuringMorph = false)
        } else {
            hiddenPositionPill(state, position.frameKey, morphActive = false)
        }
    }

    MediaGridPositionPillEvent.ScrollStopped -> {
        if (state.phase == MediaGridPositionPillPhase.Visible) {
            state.copy(hideScheduled = true)
        } else {
            state
        }
    }

    is MediaGridPositionPillEvent.HideTimeout -> {
        if (event.generation != state.generation || state.phase != MediaGridPositionPillPhase.Visible || !state.hideScheduled) {
            state
        } else if (state.morphActive) {
            state.copy(hideRequestedDuringMorph = true)
        } else {
            state.copy(
                phase = MediaGridPositionPillPhase.Hiding,
                hideScheduled = false,
                hideRequestedDuringMorph = false,
            )
        }
    }

    is MediaGridPositionPillEvent.ExitAnimationFinished -> {
        if (event.generation != state.generation || state.phase != MediaGridPositionPillPhase.Hiding) {
            state
        } else {
            hiddenPositionPill(state, state.frameKey, morphActive = false)
        }
    }

    MediaGridPositionPillEvent.MorphStarted -> state.copy(morphActive = true)

    is MediaGridPositionPillEvent.MorphFinished -> {
        val position = event.position
        if (state.phase == MediaGridPositionPillPhase.Hidden || position == null) {
            hiddenPositionPill(state, event.frameKey, morphActive = false)
        } else {
            val shouldHide = state.hideRequestedDuringMorph
            state.copy(
                phase = if (shouldHide) MediaGridPositionPillPhase.Hiding else state.phase,
                frameKey = event.frameKey,
                label = position.label,
                hideScheduled = false,
                hideRequestedDuringMorph = false,
                morphActive = false,
            )
        }
    }
}

private fun hiddenPositionPill(
    state: MediaGridPositionPillState,
    frameKey: MediaGridRenderKey?,
    morphActive: Boolean,
): MediaGridPositionPillState = state.copy(
    phase = MediaGridPositionPillPhase.Hidden,
    frameKey = frameKey,
    label = null,
    generation = state.generation + 1L,
    hideScheduled = false,
    hideRequestedDuringMorph = false,
    morphActive = morphActive,
)

@Composable
internal fun MediaGridPositionPill(
    state: MediaGridPositionPillState,
    onEvent: (MediaGridPositionPillEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.phase, state.generation, state.hideScheduled) {
        when {
            state.phase == MediaGridPositionPillPhase.Visible && state.hideScheduled -> {
                delay(MediaGridPositionPillDisplayMillis)
                onEvent(MediaGridPositionPillEvent.HideTimeout(state.generation))
            }

            state.phase == MediaGridPositionPillPhase.Hiding -> {
                delay(MediaGridPositionPillAnimationMillis.toLong())
                onEvent(MediaGridPositionPillEvent.ExitAnimationFinished(state.generation))
            }
        }
    }
    AnimatedVisibility(
        visible = state.phase == MediaGridPositionPillPhase.Visible,
        enter = slideInVertically(
            animationSpec = tween(MediaGridPositionPillAnimationMillis),
            initialOffsetY = { -it },
        ) + fadeIn(animationSpec = tween(MediaGridPositionPillAnimationMillis)),
        exit = slideOutVertically(
            animationSpec = tween(MediaGridPositionPillAnimationMillis),
            targetOffsetY = { -it },
        ) + fadeOut(animationSpec = tween(MediaGridPositionPillAnimationMillis)),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .padding(top = 8.dp)
                .zIndex(2f)
                .testTag("media_grid_position_pill"),
        ) {
            Text(
                text = state.label.orEmpty(),
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .testTag("media_grid_position_pill_text"),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}
