package com.lyco256.llm

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.TagHierarchy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RelatedTweetsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedTweetRemainsVisibleWhileRelatedSectionLoadsAndReadyRowsHideScore() {
        val clips = clips()
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = MediaGridTweetDialogState.Loaded(clips[0]),
                    relatedState = RelatedTweetsUiState(
                        status = RelatedTweetsStatus.LOADING,
                        referenceClipId = 1L,
                        phase = com.lyco256.llm.data.RelatedTweetsProgressPhase.RETRIEVING,
                        processed = 1,
                        total = 2,
                    ),
                    availableClips = clips,
                    hierarchy = TagHierarchy(),
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

        composeRule.onNodeWithTag("clip_card_1", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("related_tweets_section", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("related_tweets_loading", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("処理中: 1/2", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("0.80", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun relatedRowSwitchesSelectionWithoutStackingDialog() {
        val clips = clips()
        var selectedId by mutableStateOf(1L)
        var relatedState by mutableStateOf(
            RelatedTweetsUiState(
                status = RelatedTweetsStatus.READY,
                referenceClipId = 1L,
                rankedClipIds = listOf(2L),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = MediaGridTweetDialogState.Loaded(clips.first { it.clip.id == selectedId }),
                    relatedState = relatedState,
                    availableClips = clips,
                    hierarchy = TagHierarchy(),
                    onDismiss = {},
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = {},
                    onRelatedClipClick = { id ->
                        selectedId = id
                        relatedState = relatedState.copy(referenceClipId = id, rankedClipIds = emptyList())
                    },
                )
            }
        }

        composeRule.onNodeWithTag("related_tweet_2", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("clip_card_2", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("関連ツイートはありません", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun classifiedCardBackgroundOpensDetailButAuthorActionDoesNotBubble() {
        val clips = clips()
        var detailOpens = 0
        var authorClicks = 0
        composeRule.setContent {
            MaterialTheme {
                EnhancedClassifiedScreen(
                    uiState = MainUiState(
                        clips = clips,
                        filters = TweetFilterState(taggedOnly = false),
                        hasReceivedInitialClipEmission = true,
                    ),
                    mediaGridState = ClassifiedMediaGridState(),
                    listState = rememberLazyListState(),
                    displayMode = ClassifiedDisplayMode.Card,
                    mediaGridColumnCount = ClassifiedMediaGridDefaultColumnCount,
                    onMediaGridColumnCountChange = {},
                    onTweetDetailClick = { detailOpens++ },
                    onToggleDisplayMode = {},
                    onApplyFilters = {},
                    onApplySort = {},
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = { authorClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag("clip_author_1", useUnmergedTree = true).performClick()
        assertEquals(0, detailOpens)
        assertEquals(1, authorClicks)
        composeRule.onNodeWithTag("clip_card_1", useUnmergedTree = true).performClick()
        assertEquals(1, detailOpens)
    }

    private fun clips(): List<ClipWithDetails> = (1L..3L).map { id ->
        val now = "2026-09-13T00:00:00Z"
        ClipWithDetails(
            clip = ClipEntity(
                id = id,
                xPostId = "post-$id",
                authorId = "author-$id",
                authorName = "Author $id",
                authorUsername = "author$id",
                text = "Related body $id",
                postUrl = "https://x.com/author$id/status/$id",
                xCreatedAt = now,
                savedAt = now,
                syncedAt = now,
            ),
            assets = emptyList(),
            tags = emptyList(),
        )
    }
}
