package com.lyco256.llm

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.TagHierarchy
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TweetLikeDisplayUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unclassifiedCardShowsHeartFormattedLikeAfterAuthorSavedCountAndKeepsPopup() {
        val state = stateWithClip(likeCount = 1_100, fetchedAt = "2026-08-13T00:00:00Z")
        composeRule.setContent {
            MaterialTheme {
                EnhancedClipListScreen(
                    title = "未分類",
                    clips = state.unclassified,
                    hierarchy = TagHierarchy(),
                    authorSavedCountByAuthor = state.authorSavedCountByAuthor,
                    emptyText = "empty",
                    listState = rememberLazyListState(),
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithTag("clip_author_saved_count_1", useUnmergedTree = true).assertTextEquals("1件")
        composeRule.onNodeWithText("♡1,100", useUnmergedTree = true).assertExists()
        val author = composeRule.onNodeWithText("Author", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        val savedCount = composeRule.onNodeWithTag("clip_author_saved_count_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        val likeCount = composeRule.onNodeWithText("♡1,100", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue(author < savedCount)
        assertTrue(savedCount < likeCount)

        composeRule.onNodeWithTag("clip_like_count_1", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("clip_like_popup_1").assertExists()
        composeRule.onNodeWithText("いいね数: 1,100").assertExists()
        composeRule.onNodeWithText("一時的に取得した値の可能性があります").assertExists()
    }

    @Test
    fun classifiedCardUsesExistingTenThousandsFormatWithHeart() {
        val state = stateWithClip(likeCount = 10_000)
        composeRule.setContent {
            MaterialTheme {
                EnhancedClassifiedScreen(
                    uiState = state,
                    mediaGridState = ClassifiedMediaGridState(),
                    listState = rememberLazyListState(),
                    displayMode = ClassifiedDisplayMode.Card,
                    mediaGridColumnCount = ClassifiedMediaGridDefaultColumnCount,
                    onMediaGridColumnCountChange = {},
                    onToggleDisplayMode = {},
                    onApplyFilters = {},
                    onApplySort = {},
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }

        composeRule.onNodeWithText("♡1万", useUnmergedTree = true).assertExists()
    }

    @Test
    fun mediaGridPreviewShowsSameHeartFormattedLike() {
        val state = stateWithClip(likeCount = 1_100)
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = MediaGridTweetDialogState.Loaded(state.clips.single()),
                    hierarchy = TagHierarchy(),
                    authorSavedCountByAuthor = state.authorSavedCountByAuthor,
                    onDismiss = {},
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }

        composeRule.onNodeWithText("♡1,100", useUnmergedTree = true).assertExists()
    }

    @Test
    fun nullLikeCountShowsNeitherHeartNorPlaceholder() {
        val state = stateWithClip(likeCount = null)
        composeRule.setContent {
            MaterialTheme {
                EnhancedClipListScreen(
                    title = "未分類",
                    clips = state.unclassified,
                    hierarchy = TagHierarchy(),
                    authorSavedCountByAuthor = state.authorSavedCountByAuthor,
                    emptyText = "empty",
                    listState = rememberLazyListState(),
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithTag("clip_like_count_1", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onAllNodesWithText("♡", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun stateWithClip(likeCount: Long?, fetchedAt: String? = null): MainUiState {
        val createdAt = "2026-08-12T00:00:00Z"
        return MainUiState(
            clips = listOf(
                ClipWithDetails(
                    clip = ClipEntity(
                        id = 1,
                        xPostId = "post-1",
                        authorId = "author-1",
                        authorName = "Author",
                        authorUsername = "author",
                        text = "post",
                        postUrl = "https://x.com/author/status/1",
                        xCreatedAt = createdAt,
                        savedAt = createdAt,
                        syncedAt = createdAt,
                        likeCount = likeCount,
                        likeCountFetchedAt = fetchedAt,
                    ),
                    assets = emptyList(),
                    tags = emptyList(),
                ),
            ),
            hasReceivedInitialClipEmission = true,
            filters = TweetFilterState(taggedOnly = false),
        )
    }
}
