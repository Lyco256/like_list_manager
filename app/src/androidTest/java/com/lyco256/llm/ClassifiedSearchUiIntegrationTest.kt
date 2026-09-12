package com.lyco256.llm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipTagEntity
import com.lyco256.llm.data.LocalSearchResult
import com.lyco256.llm.data.PostStorageManager
import com.lyco256.llm.data.TagEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClassifiedSearchUiIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ClassifiedSearchTestActivity>()

    private lateinit var fixture: SearchFixture

    @Before
    fun setUp() {
        ClassifiedSearchTestHarness.engine.reset()
        fixture = seedSearchFixture()
        waitForClassifiedScreen()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_tagged_only").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()

        composeRule.onNodeWithTag("sort_open").performClick()
        composeRule.onNodeWithTag("sort_base_like").performClick()
        composeRule.onNodeWithTag("sort_like_direction_high").performClick()
        composeRule.onNodeWithTag("sort_apply").performClick()
        assertEquals(ClassifiedSortBase.LikeCount, mainViewModel().uiState.value.sort.baseOrder)
    }

    @Test
    fun smartSearchShowsRankedCardsAppliesAndFilterKeepsMediaGridOrderThenClears() {
        composeRule.onNodeWithTag("search_open").performClick()
        composeRule.onNodeWithTag("search_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("search_mode_smart").assertIsSelected()
        assertTrue(composeRule.onAllNodesWithTag("search_target_text").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("search_query").performTextInput("fake smart query")
        composeRule.onNodeWithTag("search_apply").performClick()

        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("classified_search_loading").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sort_open").assertIsNotEnabled()
        composeRule.onNodeWithTag("filter_open").assertIsEnabled()
        composeRule.onNodeWithTag("classified_display_toggle").assertIsEnabled()

        val request = ClassifiedSearchTestHarness.engine.awaitRequest("fake smart query")
        ClassifiedSearchTestHarness.engine.complete(
            request,
            listOf(
                LocalSearchResult(fixture.clipC, 0.95f),
                LocalSearchResult(fixture.clipA, 0.80f),
                LocalSearchResult(fixture.clipB, 0.70f),
            ),
        )
        waitForCard(fixture.clipB)
        assertRankedCardOrder(fixture.clipC, fixture.clipA, fixture.clipB)

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_tagged_only").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("clip_card_${fixture.clipB}").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("clip_card_${fixture.clipC}").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.clipA}").assertIsDisplayed()

        composeRule.onNodeWithTag("classified_display_toggle").performClick()
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodesWithTag("classified_media_grid").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("media_grid_item_${fixture.assetC}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sort_open").assertIsNotEnabled()
        composeRule.onNodeWithTag("media_grid_item_${fixture.assetA}").assertIsDisplayed()
        assertGridOrder(fixture.assetC, fixture.assetA)
        assertTrue(composeRule.onAllNodesWithTag("media_grid_item_${fixture.assetB}").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("search_open").performClick()
        composeRule.onNodeWithTag("search_clear").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("search_dialog").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("sort_open").assertIsEnabled()
        assertEquals(ClassifiedSortBase.LikeCount, mainViewModel().uiState.value.sort.baseOrder)
    }

    private fun seedSearchFixture(): SearchFixture = runBlocking {
        val storage = storage()
        storage.withDatabase { database ->
            database.clearAllTables()
            val now = "2026-08-01T12:00:00Z"
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "SmartSearchTag", createdAt = now, updatedAt = now),
            )
            fun clip(key: String, text: String, likes: Long): ClipEntity = ClipEntity(
                xPostId = "classified-search-$key",
                authorName = "Search $key",
                authorUsername = "search_$key",
                text = text,
                postUrl = "https://x.com/search_$key/status/classified-search-$key",
                xCreatedAt = now,
                savedAt = now,
                syncedAt = now,
                likeCount = likes,
            )
            val definitions = listOf(
                "c" to clip("c", "ranked C", 1L),
                "a" to clip("a", "ranked A", 20L),
                "b" to clip("b", "ranked B", 40L),
            )
            val ids = definitions.associate { (key, clip) ->
                val clipId = database.clipDao().insertClip(clip)
                if (key != "b") database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
                val assetId = database.clipDao().insertAssets(
                    listOf(
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "smart-search-$key",
                            type = "photo",
                            remoteUrl = null,
                            previewUrl = null,
                            downloadState = "missing",
                            createdAt = now,
                        ),
                    ),
                ).single()
                key to (clipId to assetId)
            }
            SearchFixture(
                clipC = ids.getValue("c").first,
                clipA = ids.getValue("a").first,
                clipB = ids.getValue("b").first,
                assetC = ids.getValue("c").second,
                assetA = ids.getValue("a").second,
                assetB = ids.getValue("b").second,
            )
        }
    }

    private fun waitForClassifiedScreen() {
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodesWithTag("classified_screen").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("clip_card_${fixture.clipC}").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForCard(clipId: Long) {
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodesWithTag("clip_card_$clipId").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertRankedCardOrder(vararg clipIds: Long) {
        val positions = clipIds.map { clipId ->
            composeRule.onNodeWithTag("clip_card_$clipId").fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Card order was $positions", positions.zipWithNext().all { (left, right) -> left < right })
    }

    private fun assertGridOrder(firstAssetId: Long, secondAssetId: Long) {
        val first = composeRule.onNodeWithTag("media_grid_item_$firstAssetId").fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag("media_grid_item_$secondAssetId").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "MediaGrid order was first=$first second=$second",
            first.top < second.top || (first.top == second.top && first.left < second.left),
        )
    }

    private fun storage(): PostStorageManager =
        (composeRule.activity.application as LikeListManagerApp).container.postStorageManager

    private fun mainViewModel(): MainViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }
        return field.get(composeRule.activity) as MainViewModel
    }

    private data class SearchFixture(
        val clipC: Long,
        val clipA: Long,
        val clipB: Long,
        val assetC: Long,
        val assetA: Long,
        val assetB: Long,
    )
}
