package com.lyco256.llm

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagWithCount
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class CrossFeatureRegressionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun classifiedCardApplyRemovesCardWhenTagChangeMakesItFailTheActiveFilter() {
        val tag = tag(1, "Required")
        var details by mutableStateOf(clip(1, listOf(tag)))
        val filters = TweetFilterState(
            tagFilters = mapOf(TagNodeRef(TagNodeType.TAG, tag.id) to TagFilterState.REQUIRED),
            taggedOnly = false,
        )
        composeRule.setContent {
            MaterialTheme {
                EnhancedClassifiedScreen(
                    uiState = MainUiState(
                        clips = listOf(details),
                        hasReceivedInitialClipEmission = true,
                        tagHierarchy = hierarchy(tag),
                        filters = filters,
                    ),
                    mediaGridState = ClassifiedMediaGridState(),
                    listState = rememberLazyListState(),
                    displayMode = ClassifiedDisplayMode.Card,
                    mediaGridColumnCount = ClassifiedMediaGridDefaultColumnCount,
                    onMediaGridColumnCountChange = {},
                    onToggleDisplayMode = {},
                    onApplyFilters = {},
                    onApplySort = {},
                    onTagsChange = { _, _ -> },
                    onTagsApply = { _, ids, complete ->
                        details = details.copy(tags = details.tags.filter { it.id in ids })
                        complete(null)
                    },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("clip_card_1").assertIsDisplayed()
        composeRule.onNodeWithTag("tag_chip_1").performClick()
        composeRule.onNodeWithTag("classify_1").performClick()
        composeRule.onAllNodesWithTag("clip_card_1").assertCountEquals(0)
    }

    @Test
    fun mediaGridPreviewApplyKeepsDialogOpenForTheSameTagChange() {
        val tag = tag(2, "Preview")
        var state by mutableStateOf<MediaGridTweetDialogState>(MediaGridTweetDialogState.Loaded(clip(2, listOf(tag))))
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = state,
                    hierarchy = hierarchy(tag),
                    onDismiss = { state = MediaGridTweetDialogState.Closed },
                    onTagsChange = { _, _ -> },
                    onTagsApply = { _, ids, complete ->
                        val current = (state as MediaGridTweetDialogState.Loaded).clip
                        state = MediaGridTweetDialogState.Loaded(
                            current.copy(tags = current.tags.filter { it.id in ids }),
                        )
                        complete(null)
                    },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }

        try {
            composeRule.onNodeWithTag("tag_chip_2").performClick()
            composeRule.onNodeWithTag("classify_2").performClick()
            composeRule.runOnIdle {
                val updated = state as MediaGridTweetDialogState.Loaded
                assertEquals(2L, updated.clip.clip.id)
                assertEquals(emptyList<TagEntity>(), updated.clip.tags)
            }
            // The full card's child semantics can be absent on shorter integration viewports.
            // Loaded state above identifies the updated card; the stable dialog root proves
            // that applying the change did not dismiss its preview UI.
            composeRule.onNodeWithTag("media_grid_tweet_dialog").assertIsDisplayed()
        } finally {
            // Keep this isolated Compose test from retaining a platform Dialog after failure.
            composeRule.runOnIdle { state = MediaGridTweetDialogState.Closed }
            composeRule.waitForIdle()
        }
    }

    private fun hierarchy(tag: TagEntity) = TagHierarchy(tags = listOf(TagWithCount(tag, 1)))

    private fun tag(id: Long, name: String) = TagEntity(
        id = id,
        name = name,
        createdAt = "2026-08-13T00:00:00Z",
        updatedAt = "2026-08-13T00:00:00Z",
    )

    private fun clip(id: Long, tags: List<TagEntity>) = ClipWithDetails(
        clip = ClipEntity(
            id = id,
            xPostId = "post-$id",
            authorName = "Author",
            authorUsername = "author",
            text = "cross-feature",
            postUrl = "https://x.com/author/status/$id",
            xCreatedAt = "2026-08-13T00:00:00Z",
            savedAt = "2026-08-13T00:00:00Z",
            syncedAt = "2026-08-13T00:00:00Z",
        ),
        assets = emptyList(),
        tags = tags,
    )
}
