package com.lyco256.llm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.lyco256.llm.data.TagCreatedUndoPayload
import com.lyco256.llm.data.UndoCoordinatorResult
import com.lyco256.llm.data.UndoEntity
import com.lyco256.llm.data.UndoPayloadCodec
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UndoNotificationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun messageAndCancelAreShownAndDoubleTapRunsOnlyOneUndo() {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                UndoNotificationHost(
                    pendingUndo = slot("タグを変更しました", 1),
                    onUndo = {
                        calls++
                        release.await()
                        UndoCoordinatorResult.Success
                    },
                    onFinalize = { UndoCoordinatorResult.Success },
                )
            }
        }

        try {
            composeRule.onNodeWithTag("undo_notification").assertIsDisplayed()
            composeRule.onNodeWithText("タグを変更しました").assertIsDisplayed()
            composeRule.onNodeWithTag("undo_notification_cancel").performTouchInput { doubleClick() }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("undo_notification_cancel").assertIsNotEnabled()
            composeRule.runOnIdle { assertEquals(1, calls) }
        } finally {
            release.complete(Unit)
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun timeoutFinalizesExactlyTheDisplayedSlot() {
        val shown = slot("概要を保存しました", 2)
        var current by mutableStateOf<UndoEntity?>(shown)
        var finalized: UndoEntity? = null
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                UndoNotificationHost(
                    pendingUndo = current,
                    onUndo = { UndoCoordinatorResult.Success },
                    onFinalize = {
                        finalized = it
                        current = null
                        UndoCoordinatorResult.Success
                    },
                )
            }
        }

        try {
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(4_999, ignoreFrameDuration = true)
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(null, finalized) }
            composeRule.mainClock.advanceTimeBy(2, ignoreFrameDuration = true)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(shown, finalized) }
            composeRule.mainClock.advanceTimeBy(UndoNotificationDismissAnimationMillis.toLong(), ignoreFrameDuration = true)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("undo_notification").assertDoesNotExist()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun horizontalAndDownSwipeFinalizeButUpSwipeDoesNot() {
        var horizontalCalls = 0
        var verticalCalls = 0
        var shown by mutableStateOf<UndoEntity?>(slot("横", 3))
        composeRule.setContent {
            MaterialTheme {
                UndoNotificationHost(
                    pendingUndo = shown,
                    onUndo = { UndoCoordinatorResult.Success },
                    onFinalize = {
                        if (it.message == "横") horizontalCalls++ else verticalCalls++
                        shown = null
                        UndoCoordinatorResult.Success
                    },
                )
            }
        }
        composeRule.onNodeWithTag("undo_notification").performTouchInput { swipeLeft() }
        composeRule.waitUntil { horizontalCalls == 1 }

        composeRule.onNodeWithTag("undo_notification").assertDoesNotExist()
        composeRule.runOnIdle { shown = slot("縦", 4) }
        composeRule.onNodeWithTag("undo_notification").performTouchInput { swipeUp() }
        composeRule.runOnIdle { assertEquals(0, verticalCalls) }
        composeRule.onNodeWithTag("undo_notification").performTouchInput { swipeDown() }
        composeRule.waitUntil { verticalCalls == 1 }
        composeRule.onNodeWithTag("undo_notification").assertDoesNotExist()
    }

    @Test
    fun replacementRestartsTimerAndOldTimerCannotFinalizeNewSlot() {
        val old = slot("old", 5)
        val replacement = slot("new", 6)
        var current by mutableStateOf<UndoEntity?>(old)
        val finalized = mutableListOf<UndoEntity>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                UndoNotificationHost(
                    pendingUndo = current,
                    onUndo = { UndoCoordinatorResult.Success },
                    onFinalize = { finalized += it; UndoCoordinatorResult.Success },
                )
            }
        }

        try {
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(3_000, ignoreFrameDuration = true)
            composeRule.runOnIdle { current = replacement }
            repeat(3) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithText("new").assertIsDisplayed()
            composeRule.mainClock.advanceTimeBy(3_000, ignoreFrameDuration = true)
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(emptyList<UndoEntity>(), finalized) }
            composeRule.mainClock.advanceTimeBy(2_001, ignoreFrameDuration = true)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(listOf(replacement), finalized) }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun undoFailureKeepsNotificationAndAllowsRetry() {
        var calls = 0
        val shown = slot("削除しました", 7)
        composeRule.setContent {
            MaterialTheme {
                UndoNotificationHost(
                    pendingUndo = shown,
                    onUndo = {
                        calls++
                        if (calls == 1) UndoCoordinatorResult.Failure(IllegalStateException())
                        else UndoCoordinatorResult.Success
                    },
                    onFinalize = { UndoCoordinatorResult.Success },
                )
            }
        }

        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.onNodeWithText("操作を取り消せませんでした。もう一度お試しください").assertIsDisplayed()
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.runOnIdle { assertEquals(2, calls) }
    }

    @Test
    fun persistentSlotIsPresentedAgainAfterHostRecreation() {
        val persisted = slot("再提示", 8)
        var mounted by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (mounted) {
                    UndoNotificationHost(
                        pendingUndo = persisted,
                        onUndo = { UndoCoordinatorResult.Success },
                        onFinalize = { UndoCoordinatorResult.Success },
                    )
                }
            }
        }
        composeRule.onNodeWithText("再提示").assertIsDisplayed()
        composeRule.runOnIdle { mounted = false }
        composeRule.runOnIdle { mounted = true }
        composeRule.onNodeWithText("再提示").assertIsDisplayed()
    }

    private fun slot(message: String, tagId: Long) = UndoEntity(
        actionType = "tag_created",
        payloadJson = UndoPayloadCodec.encode(TagCreatedUndoPayload(tagId)),
        message = message,
        createdAt = "2026-08-12T00:00:0${tagId}Z",
    )
}
