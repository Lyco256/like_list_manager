package com.lyco256.llm

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lyco256.llm.data.UndoCoordinatorResult
import com.lyco256.llm.data.UndoEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val UndoNotificationDurationMillis = 5_000L
internal const val UndoNotificationDismissAnimationMillis = 180

private enum class UndoNotificationDismissDirection {
    Left,
    Right,
    Down,
}

/** App-wide durable Undo notification. Its timer runs only while this content is actually foregrounded. */
@Composable
fun UndoNotificationHost(
    pendingUndo: UndoEntity?,
    onUndo: suspend (UndoEntity) -> UndoCoordinatorResult,
    onFinalize: suspend (UndoEntity) -> UndoCoordinatorResult,
    modifier: Modifier = Modifier,
    durationMillis: Long = UndoNotificationDurationMillis,
) {
    var displayedSlot by remember { mutableStateOf(pendingUndo) }
    var visible by remember { mutableStateOf(pendingUndo != null) }
    var dismissDirection by remember { mutableStateOf<UndoNotificationDismissDirection?>(null) }
    var finalizeRequested by remember { mutableStateOf(false) }

    LaunchedEffect(pendingUndo) {
        when {
            pendingUndo != null &&
                (displayedSlot == null || displayedSlot != pendingUndo || !finalizeRequested) -> {
                displayedSlot = pendingUndo
                visible = true
                dismissDirection = null
                finalizeRequested = false
            }
            pendingUndo == null && displayedSlot != null && dismissDirection == null -> {
                displayedSlot = null
                visible = false
            }
        }
    }
    LaunchedEffect(displayedSlot, visible, dismissDirection) {
        if (displayedSlot != null && !visible && dismissDirection != null) {
            delay(UndoNotificationDismissAnimationMillis.toLong())
            displayedSlot = null
            dismissDirection = null
            finalizeRequested = false
        }
    }

    val slot = displayedSlot ?: return
    val hostView = LocalView.current
    val lifecycle = remember(hostView) {
        checkNotNull(hostView.findViewTreeLifecycleOwner()) { "Undo notification requires a LifecycleOwner" }.lifecycle
    }
    val scope = rememberCoroutineScope()
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    var undoRunning by remember(slot) { mutableStateOf(false) }
    var errorMessage by remember(slot) { mutableStateOf<String?>(null) }
    var presentationGeneration by remember(slot) { mutableStateOf(0) }

    LaunchedEffect(slot, lifecycle, durationMillis, presentationGeneration, finalizeRequested) {
        if (finalizeRequested) awaitCancellation()
        var remainingMillis = durationMillis
        var timeoutAttempted = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (timeoutAttempted) awaitCancellation()
            val startedAt = SystemClock.elapsedRealtime()
            try {
                delay(remainingMillis)
                timeoutAttempted = true
                dismissDirection = UndoNotificationDismissDirection.Down
                finalizeRequested = true
                visible = false
                if (onFinalize(slot) is UndoCoordinatorResult.Failure) {
                    dismissDirection = null
                    finalizeRequested = false
                    visible = true
                }
                awaitCancellation()
            } finally {
                remainingMillis = (remainingMillis - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            }
        }
    }

    var dragX by remember(slot) { mutableStateOf(0f) }
    var dragY by remember(slot) { mutableStateOf(0f) }
    val exit = when (dismissDirection) {
        UndoNotificationDismissDirection.Left ->
            fadeOut(tween(UndoNotificationDismissAnimationMillis)) +
                slideOutHorizontally(tween(UndoNotificationDismissAnimationMillis)) { -it }
        UndoNotificationDismissDirection.Right ->
            fadeOut(tween(UndoNotificationDismissAnimationMillis)) +
                slideOutHorizontally(tween(UndoNotificationDismissAnimationMillis)) { it }
        UndoNotificationDismissDirection.Down ->
            fadeOut(tween(UndoNotificationDismissAnimationMillis)) +
                slideOutVertically(tween(UndoNotificationDismissAnimationMillis)) { it }
        null -> ExitTransition.None
    }
    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter = EnterTransition.None,
        exit = exit,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("undo_notification")
                .pointerInput(slot, undoRunning, finalizeRequested) {
                    if (!undoRunning && !finalizeRequested) {
                        detectDragGestures(
                            onDragStart = { dragX = 0f; dragY = 0f },
                            onDragCancel = { dragX = 0f; dragY = 0f },
                            onDragEnd = {
                                val direction = when {
                                    abs(dragX) >= swipeThresholdPx && abs(dragX) >= dragY ->
                                        if (dragX < 0f) UndoNotificationDismissDirection.Left else UndoNotificationDismissDirection.Right
                                    dragY >= swipeThresholdPx -> UndoNotificationDismissDirection.Down
                                    else -> null
                                }
                                dragX = 0f
                                dragY = 0f
                                if (direction != null) {
                                    dismissDirection = direction
                                    finalizeRequested = true
                                    visible = false
                                    scope.launch {
                                        if (onFinalize(slot) is UndoCoordinatorResult.Failure) {
                                            dismissDirection = null
                                            finalizeRequested = false
                                            visible = true
                                        }
                                    }
                                }
                            },
                        ) { change, amount ->
                            change.consume()
                            dragX += amount.x
                            dragY += amount.y
                        }
                    }
                },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = errorMessage ?: slot.message,
                    modifier = Modifier.weight(1f).testTag("undo_notification_message"),
                    color = if (errorMessage == null) {
                        MaterialTheme.colorScheme.inverseOnSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                TextButton(
                    onClick = {
                        if (!undoRunning) {
                            undoRunning = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    when (onUndo(slot)) {
                                        UndoCoordinatorResult.Success,
                                        UndoCoordinatorResult.NoPendingUndo,
                                        UndoCoordinatorResult.SlotChanged -> Unit
                                        else -> {
                                            errorMessage = "操作を取り消せませんでした。もう一度お試しください"
                                            presentationGeneration++
                                        }
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Throwable) {
                                    errorMessage = "操作を取り消せませんでした。もう一度お試しください"
                                    presentationGeneration++
                                } finally {
                                    undoRunning = false
                                }
                            }
                        }
                    },
                    enabled = !undoRunning,
                    modifier = Modifier.testTag("undo_notification_cancel"),
                ) {
                    Text("キャンセル")
                }
            }
        }
    }
}
