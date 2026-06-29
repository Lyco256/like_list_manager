package com.lyco256.llm

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.PostStorageEstimate
import com.lyco256.llm.data.PostStorageLocation
import com.lyco256.llm.data.PostStorageType
import com.lyco256.llm.data.TagHierarchy
import org.junit.Rule
import org.junit.Test

class UiStateRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStateHasDeterministicTitleAndMessage() {
        composeRule.setContent {
            MaterialTheme {
                StorageProgressDialog("テスト読み込み中", "完了するまでお待ちください")
            }
        }

        composeRule.onNodeWithText("テスト読み込み中").assertIsDisplayed()
        composeRule.onNodeWithText("完了するまでお待ちください").assertIsDisplayed()
    }

    @Test
    fun storageMoveEstimateDialogCancelDismissesWithoutStartingMove() {
        val estimate = PostStorageEstimate(
            target = PostStorageLocation(
                id = "external-test",
                type = PostStorageType.EXTERNAL,
                displayName = "テストSDカード",
                path = "/storage/test/post_data",
                isAvailable = true,
                totalBytes = 10_000_000,
                freeBytes = 9_000_000,
                usedBytes = 123_456,
                isCurrent = false,
            ),
            clipCount = 3,
            fileCount = 2,
            totalBytes = 123_456,
        )
        composeRule.setContent {
            MaterialTheme {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    StorageMoveEstimateDialog(
                        estimate = estimate,
                        onConfirm = { error("cancel test must not start storage move") },
                        onDismiss = { visible = false },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("post_storage_estimate_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("移動先: テストSDカード", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("post_storage_estimate_cancel").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("post_storage_estimate_dialog").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun thousandItemListReachesTailAndReturnsToHeadWithoutMissingCards() {
        val clips = (1L..1_000L).map { id ->
            ClipWithDetails(
                clip = ClipEntity(
                    id = id,
                    xPostId = "large-$id",
                    authorName = "Author $id",
                    authorUsername = "author$id",
                    text = "Large dataset item $id",
                    postUrl = "https://x.com/author$id/status/$id",
                    xCreatedAt = "2026-01-01T00:00:00Z",
                    savedAt = id.toString().padStart(5, '0'),
                    syncedAt = "2026-01-01T00:00:00Z",
                ),
                assets = emptyList(),
                tags = emptyList(),
            )
        }
        composeRule.setContent {
            MaterialTheme {
                EnhancedClipListScreen(
                    title = "large-test",
                    clips = clips,
                    hierarchy = TagHierarchy(),
                    emptyText = "empty",
                    listState = rememberLazyListState(),
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithTag("clip_list").performScrollToIndex(999)
        composeRule.onNodeWithTag("clip_card_1000").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_list").performScrollToIndex(0)
        composeRule.onNodeWithTag("clip_card_1").assertIsDisplayed()
    }
}
