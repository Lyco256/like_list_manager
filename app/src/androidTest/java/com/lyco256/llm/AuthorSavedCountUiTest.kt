package com.lyco256.llm

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.TagHierarchy
import org.junit.Rule
import org.junit.Test

class AuthorSavedCountUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unclassifiedCardShowsAllSavedCount() {
        val state = stateWithFiveSavedClips()
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

        composeRule.onNodeWithTag("clip_author_saved_count_1", useUnmergedTree = true).assertTextEquals("5件")
    }

    @Test
    fun classifiedFilteredCardStillShowsAllSavedCount() {
        val state = stateWithFiveSavedClips().copy(
            filters = TweetFilterState(taggedOnly = false),
        )
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

        composeRule.onNodeWithTag("clip_author_saved_count_1", useUnmergedTree = true).assertTextEquals("5件")
    }

    @Test
    fun mediaGridPreviewShowsSameAllSavedCount() {
        val state = stateWithFiveSavedClips()
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = MediaGridTweetDialogState.Loaded(state.clips.first()),
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

        composeRule.onNodeWithTag("clip_author_saved_count_1", useUnmergedTree = true).assertTextEquals("5件")
    }

    private fun stateWithFiveSavedClips(): MainUiState = MainUiState(
        clips = (1L..5L).map { id ->
            val now = "2026-08-12T00:00:00Z"
            ClipWithDetails(
                clip = ClipEntity(
                    id = id,
                    xPostId = "post-$id",
                    authorId = "author-1",
                    authorName = "Author",
                    authorUsername = "author",
                    text = if (id <= 2) "visible post" else "hidden post",
                    postUrl = "https://x.com/author/status/$id",
                    xCreatedAt = now,
                    savedAt = now,
                    syncedAt = now,
                ),
                assets = emptyList(),
                tags = emptyList(),
            )
        },
        hasReceivedInitialClipEmission = true,
    )
}
