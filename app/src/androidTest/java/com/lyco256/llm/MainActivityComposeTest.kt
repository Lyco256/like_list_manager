package com.lyco256.llm

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipTagEntity
import com.lyco256.llm.data.PostStorageManager
import com.lyco256.llm.data.TagEntity
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class MainActivityComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetTestData() = runBlocking {
        storage().withDatabase { it.clearAllTables() }
        (composeRule.activity.application as LikeListManagerApp).container.repository.ensureSeedData()
        composeRule.waitForIdle()
    }

    @Test
    fun primaryTabsAndSettingsDialogAreMachineVerifiable() {
        assertEquals("com.lyco256.llm.test", BuildConfig.APPLICATION_ID)
        composeRule.onNodeWithTag("main_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_unclassified").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("classified_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_tags").performClick()
        composeRule.onNodeWithTag("tags_screen").assertIsDisplayed()
        openSettingsScreen()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithText("設定").assertIsDisplayed()
    }

    @Test
    fun settingsScreenHidesTabsShowsSectionsAndAndroidBackRestoresPreviousTab() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        composeRule.onNodeWithTag("tags_screen").assertIsDisplayed()

        openSettingsScreen()

        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("tab_unclassified").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("tab_classified").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("tab_tags").fetchSemanticsNodes().isEmpty())
        assertSettingsSectionVisible("settings_x_api_section")
        assertSettingsSectionVisible("settings_sync_section")
        assertSettingsSectionVisible("settings_usage_section")
        assertSettingsSectionVisible("settings_data_management_section")

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("tags_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_tags").assertIsDisplayed()
    }

    @Test
    fun selectedTabSurvivesActivityRecreation() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        composeRule.onNodeWithTag("tags_screen").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("tags_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_tags").assertIsDisplayed()
    }

    @Test
    fun classifiedFilterSurvivesActivityRecreation() {
        val clipId = waitForSeededClip()
        val now = Instant.now().toString()
        runBlocking {
            storage().withDatabase { database ->
                val clip = database.clipDao().getActiveClips().single { it.id == clipId }
                database.clipDao().updateClip(clip.copy(text = "RecreateFilterNeedle"))
                val tagId = database.tagDao().insertTag(TagEntity(name = "RecreateFilterTag", createdAt = now, updatedAt = now))
                database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
            }
        }
        waitUntil { clipTagIds(clipId).isNotEmpty() }

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("RecreateFilterNeedle")
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.onNodeWithText("文字列:\"RecreateFilterNeedle\"", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("classified_screen").assertIsDisplayed()
        composeRule.onNodeWithText("文字列:\"RecreateFilterNeedle\"", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
    }

    @Test
    fun usageAndSettingsSafetyControlsReflectTheIsolatedEnvironment() {
        openSettingsScreen()
        composeRule.onNodeWithTag("settings_usage_section").assertIsDisplayed()
        composeRule.onNodeWithText("今月のAPI使用量: 0 / 2000").assertIsDisplayed()
        composeRule.onNodeWithText("15分rate limit: 未取得").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("警告ライン").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("停止ライン").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("settings_login_logout").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction() and hasText("OAuth 2.0 Client ID")).performTextInput("test-client-id")
        composeRule.onNodeWithText("保存してXにログイン").assertIsNotEnabled()
    }

    @Test
    fun settingsScreenOmitsHiddenLabelsAndShowsDataManagementSummary() {
        openSettingsScreen()

        assertTrue(composeRule.onAllNodesWithText("Callback URI", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Scope", substring = true).fetchSemanticsNodes().isEmpty())

        assertSettingsSectionVisible("settings_data_management_section")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings_storage_usage_progress").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("保存件数: 3").assertIsDisplayed()
        composeRule.onNodeWithText("画像枚数: 0").assertIsDisplayed()
        composeRule.onNodeWithText("保存先の使用状況").assertIsDisplayed()
        composeRule.onNodeWithText("他のデータ").assertIsDisplayed()
        composeRule.onNodeWithText("アプリデータ").assertIsDisplayed()
        composeRule.onNodeWithText("空き容量").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("ツイートデータ容量", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("現在の保存場所", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("保存場所候補", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("移動可能:", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun apiSettingsSaveAndClearRoundTripThroughTheUi() {
        openSettingsScreen()
        composeRule.onNodeWithTag("settings_client_id_clear").performClick()
        waitUntil { apiClientId() == "" }

        composeRule.onNodeWithTag("settings_client_id_input").performTextReplacement("  ui-client-id  ")
        composeRule.onNodeWithTag("settings_client_id_save").performClick()
        waitUntil { apiClientId() == "ui-client-id" }

        composeRule.onNodeWithTag("settings_back").performClick()
        openSettingsScreen()
        composeRule.onNodeWithText("ui-client-id").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_client_id_clear").performClick()
        waitUntil { apiClientId() == "" }
        composeRule.onNodeWithTag("settings_back").performClick()

        openSettingsScreen()
        assertTrue(composeRule.onAllNodesWithText("ui-client-id").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("settings_back").performClick()
    }

    @Test
    fun storageLocationDialogDismissDoesNotChangeDatabase() {
        waitForSeededClip()
        val before = databaseFingerprint()

        openSettingsScreen()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_back").performClick()
        composeRule.onNodeWithTag("main_screen").assertIsDisplayed()

        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun likeRefreshEstimateCancelDoesNotChangeDatabase() {
        waitForSeededClip()
        val now = Instant.now().toString()
        runBlocking {
            storage().withDatabase { database ->
                database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "1234567890",
                        authorName = "like refresh target",
                        authorUsername = "like_refresh_target",
                        text = "Like refresh estimate cancel target",
                        postUrl = "https://x.com/like_refresh_target/status/1234567890",
                        xCreatedAt = now,
                        savedAt = now,
                        syncedAt = now,
                    ),
                )
            }
        }
        waitUntil { totalClipCount() == 4 }
        val before = databaseFingerprint()

        openSettingsScreen()
        composeRule.onNodeWithTag("settings_like_refresh").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("いいね数を更新しますか？").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_like_refresh_cancel").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun filterApplyDiscardAndClearKeepDatabaseUnchanged() {
        val clipId = waitForSeededClip()
        val now = Instant.now().toString()
        runBlocking {
            storage().withDatabase { database ->
                val clip = database.clipDao().getActiveClips().single { it.id == clipId }
                database.clipDao().updateClip(clip.copy(text = "FilterNeedle"))
                val tagId = database.tagDao().insertTag(TagEntity(name = "FilterTag", createdAt = now, updatedAt = now))
                database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
            }
        }
        waitUntil { clipTagIds(clipId).size == 1 }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_classified").performClick()
        waitForText("一致件数:1件")
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("FilterNeedle")
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.onNodeWithText("文字列:\"FilterNeedle\"", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("discarded-query")
        composeRule.onNodeWithTag("filter_cancel").performClick()
        composeRule.onNodeWithText("変更を破棄しますか？").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_discard_confirm").performClick()
        composeRule.onNodeWithText("文字列:\"FilterNeedle\"", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("back-discarded-query")
        requestDiscardConfirmationWithBack()
        composeRule.onNodeWithText("変更を破棄しますか？").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_discard_confirm").performClick()
        composeRule.onNodeWithText("文字列:\"FilterNeedle\"", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_clear_all_open").performClick()
        composeRule.onNodeWithTag("filter_clear_all_cancel").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.onNodeWithText("文字列:\"FilterNeedle\"", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("filter_clear").performClick()
        composeRule.onNodeWithText("すべての条件をクリアしますか？").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_clear_confirm").performClick()
        composeRule.onNodeWithText("対象:タグ付きのみ、条件なし").assertIsDisplayed()
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun filterDialogAppliesAuthorAndTagConditionsTogetherWithoutChangingDatabase() {
        waitForSeededClip()
        val now = Instant.now().toString()
        val fixture = runBlocking {
            storage().withDatabase { database ->
                val clips = database.clipDao().getActiveClips().sortedBy { it.id }
                val matching = clips[0]
                val wrongAuthor = clips[1]
                val untagged = clips[2]
                database.clipDao().updateClip(
                    matching.copy(
                        text = "AuthorTagNeedle",
                        authorId = "author-filter-a",
                        authorName = "Author Alpha",
                        authorUsername = "alpha",
                    ),
                )
                database.clipDao().updateClip(
                    wrongAuthor.copy(
                        text = "AuthorTagNeedle",
                        authorId = "author-filter-b",
                        authorName = "Author Beta",
                        authorUsername = "beta",
                    ),
                )
                database.clipDao().updateClip(
                    untagged.copy(
                        text = "AuthorTagNeedle",
                        authorId = "author-filter-a",
                        authorName = "Author Alpha",
                        authorUsername = "alpha",
                    ),
                )
                val wantedTag = database.tagDao().insertTag(TagEntity(name = "FilterUiWanted", createdAt = now, updatedAt = now))
                val otherTag = database.tagDao().insertTag(TagEntity(name = "FilterUiOther", createdAt = now, updatedAt = now))
                database.clipDao().insertClipTag(ClipTagEntity(matching.id, wantedTag, now))
                database.clipDao().insertClipTag(ClipTagEntity(wrongAuthor.id, otherTag, now))
                FilterFixture(matching.id, wrongAuthor.id, untagged.id, wantedTag)
            }
        }
        waitUntil {
            clipTagIds(fixture.matchingClipId) == setOf(fixture.wantedTagId) &&
                clipTagIds(fixture.wrongAuthorClipId).isNotEmpty()
        }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("clip_card_${fixture.matchingClipId}").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.wrongAuthorClipId}").assertIsDisplayed()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_author_open"))
        composeRule.onNodeWithTag("filter_author_open").performClick()
        composeRule.onNodeWithTag("filter_author_option_author-filter-a_alpha").performClick()
        composeRule.onNodeWithTag("filter_author_confirm").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_tag_condition_tag_${fixture.wantedTagId}"))
        composeRule.onNodeWithTag("filter_tag_condition_tag_${fixture.wantedTagId}").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()

        composeRule.onNodeWithTag("clip_card_${fixture.matchingClipId}").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("clip_card_${fixture.wrongAuthorClipId}").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag("clip_card_${fixture.untaggedClipId}").fetchSemanticsNodes().isEmpty())
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun filterDialogClearsOnlyTagConditionsWithoutChangingDatabase() {
        waitForSeededClip()
        val now = Instant.now().toString()
        val fixture = runBlocking {
            storage().withDatabase { database ->
                val clips = database.clipDao().getActiveClips().sortedBy { it.id }
                val matching = clips[0]
                val otherTagged = clips[1]
                val untagged = clips[2]
                database.clipDao().updateClip(matching.copy(text = "TagClearNeedle"))
                database.clipDao().updateClip(otherTagged.copy(text = "TagClearNeedle"))
                database.clipDao().updateClip(untagged.copy(text = "TagClearNeedle"))
                val wantedTag = database.tagDao().insertTag(TagEntity(name = "TagClearWanted", createdAt = now, updatedAt = now))
                val otherTag = database.tagDao().insertTag(TagEntity(name = "TagClearOther", createdAt = now, updatedAt = now))
                database.clipDao().insertClipTag(ClipTagEntity(matching.id, wantedTag, now))
                database.clipDao().insertClipTag(ClipTagEntity(otherTagged.id, otherTag, now))
                FilterFixture(matching.id, otherTagged.id, untagged.id, wantedTag)
            }
        }
        waitUntil {
            clipTagIds(fixture.matchingClipId) == setOf(fixture.wantedTagId) &&
                clipTagIds(fixture.wrongAuthorClipId).isNotEmpty()
        }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_tag_condition_tag_${fixture.wantedTagId}"))
        composeRule.onNodeWithTag("filter_tag_condition_tag_${fixture.wantedTagId}").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()

        composeRule.onNodeWithText("タグ:含む[TagClearWanted]", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.matchingClipId}").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("clip_card_${fixture.wrongAuthorClipId}").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_tag_clear"))
        composeRule.onNodeWithTag("filter_tag_clear").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()

        composeRule.onNodeWithText("対象:タグ付きのみ、条件なし").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.matchingClipId}").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.wrongAuthorClipId}").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("clip_card_${fixture.untaggedClipId}").fetchSemanticsNodes().isEmpty())
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun filterAuthorDialogClearKeepsDatabaseUnchanged() {
        waitForSeededClip()
        val now = Instant.now().toString()
        val fixture = runBlocking {
            storage().withDatabase { database ->
                val clips = database.clipDao().getActiveClips().sortedBy { it.id }
                val first = clips[0]
                val second = clips[1]
                val untagged = clips[2]
                database.clipDao().updateClip(
                    first.copy(
                        text = "AuthorClearNeedle",
                        authorId = "author-clear-a",
                        authorName = "Author Clear A",
                        authorUsername = "cleara",
                    ),
                )
                database.clipDao().updateClip(
                    second.copy(
                        text = "AuthorClearNeedle",
                        authorId = "author-clear-b",
                        authorName = "Author Clear B",
                        authorUsername = "clearb",
                    ),
                )
                database.clipDao().updateClip(untagged.copy(text = "AuthorClearNeedle"))
                val tagId = database.tagDao().insertTag(TagEntity(name = "AuthorClearTag", createdAt = now, updatedAt = now))
                database.clipDao().insertClipTag(ClipTagEntity(first.id, tagId, now))
                database.clipDao().insertClipTag(ClipTagEntity(second.id, tagId, now))
                Triple(first.id, second.id, untagged.id)
            }
        }
        waitUntil {
            clipTagIds(fixture.first).isNotEmpty() &&
                clipTagIds(fixture.second).isNotEmpty()
        }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_author_open"))
        composeRule.onNodeWithTag("filter_author_open").performClick()
        composeRule.onNodeWithTag("filter_author_option_author-clear-a_cleara").performClick()
        composeRule.onNodeWithTag("filter_author_confirm").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()

        composeRule.onNodeWithTag("clip_card_${fixture.first}").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("clip_card_${fixture.second}").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_author_open"))
        composeRule.onNodeWithTag("filter_author_open").performClick()
        composeRule.onNodeWithTag("filter_author_clear").performClick()
        composeRule.onNodeWithTag("filter_author_confirm").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()

        composeRule.onNodeWithTag("clip_card_${fixture.first}").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.second}").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("clip_card_${fixture.third}").fetchSemanticsNodes().isEmpty())
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun authorClickMovesToClassifiedAuthorFilterWithoutChangingDatabase() {
        waitForSeededClip()
        val fixture = runBlocking {
            storage().withDatabase { database ->
                val clips = database.clipDao().getActiveClips().sortedBy { it.id }
                val first = clips[0]
                val second = clips[1]
                val other = clips[2]
                database.clipDao().updateClip(
                    first.copy(
                        text = "AuthorQuickFilterNeedle first",
                        authorId = "author-quick-filter",
                        authorName = "Quick Filter",
                        authorUsername = "quickfilter",
                    ),
                )
                database.clipDao().updateClip(
                    second.copy(
                        text = "AuthorQuickFilterNeedle second",
                        authorId = "author-quick-filter",
                        authorName = "Quick Filter",
                        authorUsername = "quickfilter",
                    ),
                )
                database.clipDao().updateClip(
                    other.copy(
                        text = "AuthorQuickFilterNeedle other",
                        authorId = "author-other-filter",
                        authorName = "Other Filter",
                        authorUsername = "otherfilter",
                    ),
                )
                AuthorClickFixture(first.id, second.id, other.id)
            }
        }
        waitUntil {
            runBlocking {
                storage().withDatabase { database ->
                    database.clipDao().getActiveClips().all { it.text.startsWith("AuthorQuickFilterNeedle") }
                }
            }
        }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("clip_author_${fixture.clickedClipId}").performClick()

        composeRule.onNodeWithTag("classified_screen").assertIsDisplayed()
        composeRule.onNodeWithText("対象:全ツイート", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("ユーザー:@quickfilter", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.clickedClipId}").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.sameAuthorClipId}").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("clip_card_${fixture.otherAuthorClipId}").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun likeCountPopupShowsDetailsAndDoesNotChangeDatabase() {
        val clipId = waitForSeededClip()
        val createdAt = Instant.now().minusSeconds(24 * 60 * 60).toString()
        val fetchedAt = Instant.now().toString()
        runBlocking {
            storage().withDatabase { database ->
                val clip = database.clipDao().getActiveClips().single { it.id == clipId }
                database.clipDao().updateClip(
                    clip.copy(
                        xCreatedAt = createdAt,
                        likeCount = 12_345,
                        likeCountFetchedAt = fetchedAt,
                    ),
                )
            }
        }
        waitUntil {
            runBlocking {
                storage().withDatabase { database ->
                    database.clipDao().getActiveClips().single { it.id == clipId }.likeCount == 12_345L
                }
            }
        }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("clip_like_count_$clipId").performClick()

        composeRule.onNodeWithText("いいね数: 12,345").assertIsDisplayed()
        composeRule.onNodeWithText("取得日時:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("投稿から一週間以内に取得した値です").assertIsDisplayed()
        composeRule.onNodeWithText("いいね数: 12,345").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("いいね数: 12,345").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun filterDialogAppliesAndClearsDateRangeWithoutChangingDatabase() {
        waitForSeededClip()
        val now = Instant.now().toString()
        val startDate = LocalDate.now()
        val olderDate = startDate.minusDays(1)
        val fixture = runBlocking {
            storage().withDatabase { database ->
                val clips = database.clipDao().getActiveClips().sortedBy { it.id }
                val matching = clips[0]
                val older = clips[1]
                val tagId = database.tagDao().insertTag(TagEntity(name = "FilterDateTag", createdAt = now, updatedAt = now))
                database.clipDao().updateClip(
                    matching.copy(
                        text = "DateFilterNeedle",
                        xCreatedAt = startDate.toClipInstantString(),
                    ),
                )
                database.clipDao().updateClip(
                    older.copy(
                        text = "DateFilterNeedle",
                        xCreatedAt = olderDate.toClipInstantString(),
                    ),
                )
                database.clipDao().insertClipTag(ClipTagEntity(matching.id, tagId, now))
                database.clipDao().insertClipTag(ClipTagEntity(older.id, tagId, now))
                DateFilterFixture(matching.id, older.id)
            }
        }
        waitUntil {
            clipTagIds(fixture.matchingClipId).isNotEmpty() &&
                clipTagIds(fixture.olderClipId).isNotEmpty()
        }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("clip_card_${fixture.matchingClipId}").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.olderClipId}").assertIsDisplayed()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_start_date"))
        composeRule.onNodeWithTag("filter_start_date").performClick()
        composeRule.onNodeWithTag("filter_date_picker_apply").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()

        composeRule.onNodeWithText("期間:${startDate.year}/${startDate.monthValue}/${startDate.dayOfMonth}~", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${fixture.matchingClipId}").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("clip_card_${fixture.olderClipId}").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_start_date"))
        composeRule.onNodeWithTag("filter_start_date").performClick()
        composeRule.onNodeWithTag("filter_date_picker_clear").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.onNodeWithTag("clip_card_${fixture.olderClipId}").assertIsDisplayed()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_start_date"))
        composeRule.onNodeWithTag("filter_start_date").performClick()
        composeRule.onNodeWithTag("filter_date_picker_apply").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("clip_card_${fixture.olderClipId}").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_options_list")
            .performScrollToNode(hasTestTag("filter_date_clear"))
        composeRule.onNodeWithTag("filter_date_clear").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.onNodeWithTag("clip_card_${fixture.olderClipId}").assertIsDisplayed()
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun classifyingAndUnclassifyingUpdatesUiAndRoomTogether() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val tagId = createRootTag("E2E分類タグ")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNodeWithTag("clip_list").performScrollToNode(hasTestTag("clip_card_$clipId"))
        composeRule.onNode(
            hasTestTag("tag_chip_$tagId") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("classify_$clipId").performClick()
        waitUntil { clipTagIds(clipId) == setOf(tagId) }
        waitForText("未分類 (2)")

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("tag_chip_$tagId") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        waitUntil { clipTagIds(clipId).isEmpty() }

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        waitForText("未分類 (3)")
        composeRule.onNodeWithTag("clip_list").performScrollToIndex(0)
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
    }

    @Test
    fun classifiedFilterConditionSurvivesListScrollAndReturnToTop() {
        waitForSeededClip()
        val now = Instant.now().toString()
        val tagId = runBlocking {
            storage().withDatabase { database ->
                database.tagDao().insertTag(TagEntity(name = "ScrollFilterTag", createdAt = now, updatedAt = now))
            }
        }
        val clipIds = insertScrollClips(count = 24, textPrefix = "ScrollFilterNeedle", tagId = tagId)
        waitUntil { activeClipIds().contains(clipIds.last()) }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("ScrollFilterNeedle")
        composeRule.onNodeWithTag("filter_apply").performClick()
        waitForText("一致件数:24件")
        composeRule.onNodeWithText("文字列:\"ScrollFilterNeedle\"", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("clip_list").performScrollToIndex(10)
        composeRule.onNodeWithTag("scroll_to_top").performClick()
        waitUntil {
            composeRule.onAllNodesWithTag("clip_card_${clipIds.last()}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("文字列:\"ScrollFilterNeedle\"", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${clipIds.last()}").assertIsDisplayed()

        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun pendingTagSelectionSurvivesListScrollUntilClassification() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val tagId = createRootTag("Scroll保持タグ")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNode(
            hasTestTag("tag_chip_$tagId") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        insertScrollClips(count = 24, textPrefix = "ScrollSelectionFiller")
        waitUntil { totalClipCount() == 27 }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("clip_list").performScrollToIndex(10)
        composeRule.onNodeWithTag("clip_list").performScrollToNode(hasTestTag("clip_card_$clipId"))
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        composeRule.onNodeWithTag("classify_$clipId").performClick()

        waitUntil { clipTagIds(clipId) == setOf(tagId) }
        val after = databaseFingerprint()
        assertTrue(after.containsAll(before))
        assertTrue(after.contains("relation:$clipId:$tagId"))
    }

    @Test
    fun classifyingWithTwoSameNamedChildTagsPersistsBothTagRelations() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val firstGroup = createRootGroup("複合付与グループA")
        val secondGroup = createRootGroup("複合付与グループB")
        val firstTag = createChildTag(firstGroup, "同名の子タグ")
        val secondTag = createChildTag(secondGroup, "同名の子タグ")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNode(
            hasTestTag("tag_group_chip_$firstGroup") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("tag_chip_$firstTag", useUnmergedTree = true).performClick()
        dismissBackHandledDialog()
        composeRule.onNode(
            hasTestTag("tag_group_chip_$secondGroup") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("tag_chip_$secondTag", useUnmergedTree = true).performClick()
        dismissBackHandledDialog()
        composeRule.onNodeWithTag("classify_$clipId").performClick()

        waitUntil { clipTagIds(clipId) == setOf(firstTag, secondTag) }
        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        assertEquals(setOf(firstTag, secondTag), clipTagIds(clipId))
    }

    @Test
    fun localDeleteDialogSoftDeletesClipAndRemovesItFromLists() {
        val clipId = waitForSeededClip()
        val totalBefore = totalClipCount()

        composeRule.onNodeWithTag("clip_local_delete_open_$clipId").performClick()
        composeRule.onNodeWithTag("clip_local_delete_dialog_$clipId").assertIsDisplayed()
        composeRule.onNodeWithText("このツイートをアプリ内の一覧から削除します", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clip_local_delete_cancel_$clipId").performClick()
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        assertTrue(activeClipIds().contains(clipId))

        composeRule.onNodeWithTag("clip_local_delete_open_$clipId").performClick()
        composeRule.onNodeWithTag("clip_local_delete_dialog_$clipId").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_local_delete_confirm_$clipId").performClick()
        waitUntil { !activeClipIds().contains(clipId) }
        assertEquals(totalBefore, totalClipCount())
        assertTrue(composeRule.onAllNodesWithTag("clip_card_$clipId").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun summaryEditPersistsToDatabaseAndSurvivesActivityRecreation() {
        val clipId = waitForSeededClip()
        val summary = "E2E永続概要"

        composeRule.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performTextReplacement(summary)
        waitUntil { summaryForClip(clipId) == summary }

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        composeRule.onNode(
            hasSetTextAction() and hasText(summary) and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertEquals(summary, summaryForClip(clipId))
    }

    @Test
    fun imageViewerBackDismissesWithoutChangingDatabase() {
        val clipId = waitForSeededClip()
        val now = Instant.now().toString()
        val assetId = runBlocking {
            storage().withDatabase { database ->
                database.clipDao().insertAssets(
                    listOf(
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "viewer-back-photo",
                            type = "photo",
                            remoteUrl = "https://example.test/viewer-back.jpg",
                            previewUrl = null,
                            localPath = "/tmp/viewer-back-photo.webp",
                            width = 1200,
                            height = 800,
                            sizeBytes = 1234,
                            downloadState = "downloaded",
                            createdAt = now,
                        ),
                    ),
                )
                database.clipDao().assetsForClipIds(listOf(clipId)).single { it.mediaKey == "viewer-back-photo" }.id
            }
        }
        waitUntil {
            runBlocking {
                storage().withDatabase { database ->
                    database.clipDao().assetsForClipIds(listOf(clipId)).any { it.id == assetId }
                }
            }
        }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("media_asset_$assetId").performClick()
        composeRule.onNodeWithTag("image_viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("image_viewer_position").assertIsDisplayed()
        dismissBackHandledDialog()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("image_viewer").fetchSemanticsNodes().isEmpty()
        }

        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun imageViewerSwipeMovesBetweenSavedPhotosWithoutChangingDatabase() {
        val clipId = waitForSeededClip()
        val now = Instant.now().toString()
        val assetIds = runBlocking {
            storage().withDatabase { database ->
                database.clipDao().insertAssets(
                    listOf(
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "viewer-swipe-photo-1",
                            type = "photo",
                            remoteUrl = "https://example.test/viewer-swipe-1.jpg",
                            previewUrl = null,
                            localPath = "/tmp/viewer-swipe-photo-1.webp",
                            width = 1200,
                            height = 800,
                            sizeBytes = 1111,
                            downloadState = "downloaded",
                            createdAt = now,
                        ),
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "viewer-swipe-photo-2",
                            type = "photo",
                            remoteUrl = "https://example.test/viewer-swipe-2.jpg",
                            previewUrl = null,
                            localPath = "/tmp/viewer-swipe-photo-2.webp",
                            width = 800,
                            height = 1200,
                            sizeBytes = 2222,
                            downloadState = "downloaded",
                            createdAt = now,
                        ),
                    ),
                )
                database.clipDao().assetsForClipIds(listOf(clipId))
                    .filter { it.mediaKey.startsWith("viewer-swipe-photo-") }
                    .sortedBy { it.mediaKey }
                    .map { it.id }
            }
        }
        waitUntil {
            runBlocking {
                storage().withDatabase { database ->
                    database.clipDao().assetsForClipIds(listOf(clipId))
                        .count { it.mediaKey.startsWith("viewer-swipe-photo-") } == 2
                }
            }
        }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("media_asset_${assetIds.first()}").performClick()
        composeRule.onNodeWithTag("image_viewer").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
        swipeLeftOnScreen()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("image_viewer_photo_1").assertIsDisplayed()
        composeRule.onNodeWithTag("image_viewer_close").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("image_viewer").fetchSemanticsNodes().isEmpty()
        }

        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun tagManagementCreatesSameNamedChildrenThenRenamesAndDeletes() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val firstGroup = createRootGroup("E2EグループA")
        val secondGroup = createRootGroup("E2EグループB")
        composeRule.onNodeWithTag("tag_operation_group_$secondGroup").performClick()
        composeRule.onNodeWithTag("tag_rename_open_group_$secondGroup").performClick()
        composeRule.onNodeWithTag("rename_node_name").performTextReplacement("E2E変更後グループ")
        composeRule.onNodeWithTag("rename_node_save").performClick()
        waitUntil { groupsNamed("E2E変更後グループ") == 1 && groupsNamed("E2EグループB") == 0 }
        assertTrue(groupExists(secondGroup))

        val firstTag = createChildTag(firstGroup, "共有タグ")
        createChildTag(secondGroup, "共有タグ")
        assertEquals(2, tagsNamed("共有タグ"))

        composeRule.onNodeWithTag("tag_expand_group_$firstGroup").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$firstTag").performClick()
        composeRule.onNodeWithTag("tag_rename_open_tag_$firstTag").performClick()
        composeRule.onNodeWithTag("rename_node_name").performTextReplacement("変更後タグ")
        composeRule.onNodeWithTag("rename_node_save").performClick()
        waitUntil { tagsNamed("変更後タグ") == 1 }

        composeRule.onNodeWithTag("tag_operation_tag_$firstTag").performClick()
        composeRule.onNodeWithTag("tag_delete_open_tag_$firstTag").performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitUntil { !tagExists(firstTag) }

        composeRule.onNodeWithTag("tag_operation_group_$firstGroup").performClick()
        composeRule.onNodeWithTag("tag_delete_open_group_$firstGroup").performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitUntil { !groupExists(firstGroup) }
        assertTrue(groupExists(secondGroup))
    }

    @Test
    fun createTagDialogCancelDoesNotCreateTag() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("create_root_tag").performClick()
        composeRule.onNodeWithTag("create_node_name").performTextInput("キャンセル作成タグ")
        composeRule.onNodeWithTag("create_node_cancel").performClick()

        waitUntil { tagsNamed("キャンセル作成タグ") == 0 }
        assertEquals(0, tagsNamed("キャンセル作成タグ"))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun createGroupDialogCancelDoesNotCreateGroup() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("create_root_group").performClick()
        composeRule.onNodeWithTag("create_node_name").performTextInput("キャンセル作成グループ")
        composeRule.onNodeWithTag("create_node_cancel").performClick()

        waitUntil { groupsNamed("キャンセル作成グループ") == 0 }
        assertEquals(0, groupsNamed("キャンセル作成グループ"))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun renameDialogCancelKeepsTagAndGroupNames() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val groupId = createRootGroup("名称キャンセルグループ")
        val tagId = createChildTag(groupId, "名称キャンセルタグ")
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tag_operation_group_$groupId").performClick()
        composeRule.onNodeWithTag("tag_rename_open_group_$groupId").performClick()
        composeRule.onNodeWithTag("rename_node_name").performTextReplacement("変更されないグループ")
        composeRule.onNodeWithTag("rename_node_cancel").performClick()

        composeRule.onNodeWithTag("tag_expand_group_$groupId").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$tagId").performClick()
        composeRule.onNodeWithTag("tag_rename_open_tag_$tagId").performClick()
        composeRule.onNodeWithTag("rename_node_name").performTextReplacement("変更されないタグ")
        composeRule.onNodeWithTag("rename_node_cancel").performClick()

        assertEquals(1, groupsNamed("名称キャンセルグループ"))
        assertEquals(0, groupsNamed("変更されないグループ"))
        assertEquals(1, tagsNamed("名称キャンセルタグ"))
        assertEquals(0, tagsNamed("変更されないタグ"))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun tagDeleteDialogCancelKeepsTagAndRelations() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val tagId = createRootTag("削除キャンセルタグ")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNode(
            hasTestTag("tag_chip_$tagId") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("classify_$clipId").performClick()
        waitUntil { clipTagIds(clipId) == setOf(tagId) }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_tags").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$tagId").performClick()
        composeRule.onNodeWithTag("tag_delete_open_tag_$tagId").performClick()
        composeRule.onNodeWithText("「削除キャンセルタグ」の割り当ても外れます。").assertIsDisplayed()
        composeRule.onNodeWithTag("tag_delete_cancel_tag_$tagId").performClick()

        composeRule.onNodeWithTag("tag_row_tag_$tagId").assertIsDisplayed()
        assertTrue(tagExists(tagId))
        assertEquals(setOf(tagId), clipTagIds(clipId))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun groupDeleteDialogCancelKeepsGroup() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val groupId = createRootGroup("削除キャンセルグループ")
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tag_operation_group_$groupId").performClick()
        composeRule.onNodeWithTag("tag_delete_open_group_$groupId").performClick()
        composeRule.onNodeWithTag("tag_delete_cancel_group_$groupId").performClick()

        composeRule.onNodeWithTag("tag_row_group_$groupId").assertIsDisplayed()
        assertTrue(groupExists(groupId))
        assertEquals(1, groupsNamed("削除キャンセルグループ"))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun tagManagementMovesTagToAnotherGroupThroughDialog() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceGroup = createRootGroup("E2E移動元グループ")
        val targetGroup = createRootGroup("E2E移動先グループ")
        val movedTag = createChildTag(sourceGroup, "E2E移動タグ")
        waitUntil { tagParentGroupId(movedTag) == sourceGroup }

        composeRule.onNodeWithTag("tag_expand_group_$sourceGroup").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$movedTag").performClick()
        composeRule.onNodeWithTag("tag_move_open_tag_$movedTag").performClick()
        composeRule.onNodeWithTag("move_node_target_group_$targetGroup").performClick()

        waitUntil { tagParentGroupId(movedTag) == targetGroup }
        assertEquals(targetGroup, tagParentGroupId(movedTag))
    }

    @Test
    fun tagMoveDialogCancelKeepsParentGroup() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceGroup = createRootGroup("移動キャンセル元")
        createRootGroup("移動キャンセル先")
        val tagId = createChildTag(sourceGroup, "移動キャンセルタグ")
        waitUntil { tagParentGroupId(tagId) == sourceGroup }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tag_expand_group_$sourceGroup").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$tagId").performClick()
        composeRule.onNodeWithTag("tag_move_open_tag_$tagId").performClick()
        composeRule.onNodeWithText("「移動キャンセルタグ」を移動").assertIsDisplayed()
        composeRule.onNodeWithTag("move_node_cancel").performClick()

        composeRule.onNodeWithTag("tag_row_tag_$tagId").assertIsDisplayed()
        assertEquals(sourceGroup, tagParentGroupId(tagId))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun groupMoveDialogCancelKeepsParentGroup() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceGroup = createRootGroup("グループ移動キャンセル元")
        createRootGroup("グループ移動キャンセル先")
        waitUntil { groupParentGroupId(sourceGroup) == null }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tag_operation_group_$sourceGroup").performClick()
        composeRule.onNodeWithTag("tag_move_open_group_$sourceGroup").performClick()
        composeRule.onNodeWithTag("move_node_cancel").performClick()

        composeRule.onNodeWithTag("tag_row_group_$sourceGroup").assertIsDisplayed()
        assertEquals(null, groupParentGroupId(sourceGroup))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun addAllTagsDialogAddsTargetTagToSourceTaggedClips() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceTag = createRootTag("一括追加元タグ")
        val targetTag = createRootTag("一括追加先タグ")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNode(
            hasTestTag("tag_chip_$sourceTag") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("classify_$clipId").performClick()
        waitUntil { clipTagIds(clipId) == setOf(sourceTag) }

        composeRule.onNodeWithTag("tab_tags").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$sourceTag").performClick()
        composeRule.onNodeWithTag("tag_add_all_open_$sourceTag").performClick()
        composeRule.onNodeWithText("「一括追加元タグ」の全ツイートに追加するタグを選びます。元のタグは残ります。").assertIsDisplayed()
        composeRule.onNodeWithTag("add_all_target_tag_$targetTag").performClick()

        waitUntil { clipTagIds(clipId) == setOf(sourceTag, targetTag) }
        assertEquals(setOf(sourceTag, targetTag), clipTagIds(clipId))
    }

    @Test
    fun addAllTagsDialogCancelKeepsRelations() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceTag = createRootTag("一括追加キャンセル元")
        createRootTag("一括追加キャンセル先")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNode(
            hasTestTag("tag_chip_$sourceTag") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("classify_$clipId").performClick()
        waitUntil { clipTagIds(clipId) == setOf(sourceTag) }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_tags").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$sourceTag").performClick()
        composeRule.onNodeWithTag("tag_add_all_open_$sourceTag").performClick()
        composeRule.onNodeWithTag("add_all_cancel").performClick()

        assertEquals(setOf(sourceTag), clipTagIds(clipId))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun dismissingTagPopupOutsideDoesNotModifyOrPropagateToTheClip() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val groupId = createRootGroup("ポップアップ検証")
        createChildTag(groupId, "触れないタグ")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNode(
            hasTestTag("tag_group_chip_$groupId") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithText("タグを選択").assertIsDisplayed()
        tapOutsidePopup()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("タグを選択").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(clipTagIds(clipId).isEmpty())
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
    }

    @Test
    fun emptyAndSyncErrorStatesAreAssertedWithoutScreenshots() {
        waitForSeededClip()
        val beforeSyncError = databaseFingerprint()

        openSettingsScreen()
        composeRule.onNodeWithTag("settings_sync_now").performClick()
        waitForText("同期")
        composeRule.onNodeWithText("X API設定からXにログインしてください").assertIsDisplayed()
        composeRule.onNodeWithText("閉じる").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_back").performClick()
        composeRule.onNodeWithTag("main_screen").assertIsDisplayed()

        assertEquals(beforeSyncError, databaseFingerprint())

        runBlocking {
            storage().withDatabase { database ->
                database.clipDao().getActiveClips().forEach { clip -> database.clipDao().updateClip(clip.copy(isDeleted = true)) }
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("タグなしのツイートはありません").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("タグなしのツイートはありません").assertIsDisplayed()
    }

    private fun createRootTag(name: String): Long {
        composeRule.onNodeWithTag("create_root_tag").performClick()
        composeRule.onNodeWithTag("create_node_name").performTextInput(name)
        composeRule.onNodeWithTag("create_node_confirm").performClick()
        waitUntil { tagsNamed(name) == 1 }
        return runBlocking { storage().withDatabase { it.tagDao().getTags().single { tag -> tag.name == name }.id } }
    }

    private fun createRootGroup(name: String): Long {
        composeRule.onNodeWithTag("create_root_group").performClick()
        composeRule.onNodeWithTag("create_node_name").performTextInput(name)
        composeRule.onNodeWithTag("create_node_confirm").performClick()
        waitUntil { groupsNamed(name) == 1 }
        return runBlocking { storage().withDatabase { it.tagDao().getGroups().single { group -> group.name == name }.id } }
    }

    private fun createChildTag(groupId: Long, name: String): Long {
        composeRule.onNodeWithTag("tag_operation_group_$groupId").performClick()
        composeRule.onNodeWithText("子タグを追加").performClick()
        composeRule.onNodeWithTag("create_node_name").performTextInput(name)
        composeRule.onNodeWithTag("create_node_confirm").performClick()
        waitUntil {
            runBlocking {
                storage().withDatabase { database ->
                    database.tagDao().getTags().any { it.name == name && it.parentGroupId == groupId }
                }
            }
        }
        return runBlocking {
            storage().withDatabase { database ->
                database.tagDao().getTags().single { it.name == name && it.parentGroupId == groupId }.id
            }
        }
    }

    private fun waitForSeededClip(): Long {
        waitUntil { runBlocking { storage().withDatabase { it.clipDao().countClips() == 3 } } }
        return runBlocking { storage().withDatabase { it.clipDao().getActiveClips().first().id } }
    }

    private fun storage(): PostStorageManager =
        (composeRule.activity.application as LikeListManagerApp).container.postStorageManager

    private fun openSettingsScreen() {
        composeRule.onNodeWithTag("top_settings_button").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertSettingsSectionVisible(testTag: String) {
        composeRule.onNodeWithTag("settings_content").performScrollToNode(hasTestTag(testTag))
        composeRule.onNodeWithTag(testTag).assertIsDisplayed()
    }

    private fun clipTagIds(clipId: Long): Set<Long> = runBlocking {
        storage().withDatabase { it.clipDao().clipTagsForClipIds(listOf(clipId)).map { relation -> relation.tagId }.toSet() }
    }

    private fun activeClipIds(): Set<Long> = runBlocking {
        storage().withDatabase { it.clipDao().getActiveClips().map { clip -> clip.id }.toSet() }
    }

    private fun totalClipCount(): Int = runBlocking {
        storage().withDatabase { it.clipDao().countClips() }
    }

    private fun insertScrollClips(count: Int, textPrefix: String, tagId: Long? = null): List<Long> = runBlocking {
        storage().withDatabase { database ->
            val base = Instant.now()
            (0 until count).map { index ->
                val now = base.plusSeconds(index.toLong()).toString()
                val clipId = database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "$textPrefix-$index",
                        authorName = "$textPrefix author",
                        authorUsername = "${textPrefix.lowercase()}_$index",
                        text = "$textPrefix item $index",
                        postUrl = "https://x.com/${textPrefix.lowercase()}/status/$index",
                        xCreatedAt = now,
                        savedAt = now,
                        syncedAt = now,
                    ),
                )
                if (tagId != null) {
                    database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
                }
                clipId
            }
        }
    }

    private fun summaryForClip(clipId: Long): String = runBlocking {
        storage().withDatabase { database -> database.clipDao().getActiveClips().single { it.id == clipId }.summary }
    }

    private fun apiClientId(): String = runBlocking {
        (composeRule.activity.application as LikeListManagerApp).container.repository.loadApiSettings().clientId
    }

    private data class FilterFixture(
        val matchingClipId: Long,
        val wrongAuthorClipId: Long,
        val untaggedClipId: Long,
        val wantedTagId: Long,
    )

    private data class DateFilterFixture(
        val matchingClipId: Long,
        val olderClipId: Long,
    )

    private data class AuthorClickFixture(
        val clickedClipId: Long,
        val sameAuthorClipId: Long,
        val otherAuthorClipId: Long,
    )

    private fun LocalDate.toClipInstantString(): String =
        atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toString()

    private fun dismissBackHandledDialog() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
    }

    private fun requestDiscardConfirmationWithBack() {
        dismissBackHandledDialog()
        val confirmationText = "変更を破棄しますか？"
        val shown = try {
            composeRule.waitUntil(1_000) {
                composeRule.onAllNodesWithText(confirmationText).fetchSemanticsNodes().isNotEmpty()
            }
            true
        } catch (_: Throwable) {
            false
        }
        if (!shown) {
            dismissBackHandledDialog()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(confirmationText).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun tagsNamed(name: String): Int = runBlocking {
        storage().withDatabase { it.tagDao().getTags().count { tag -> tag.name == name } }
    }

    private fun groupsNamed(name: String): Int = runBlocking {
        storage().withDatabase { it.tagDao().getGroups().count { group -> group.name == name } }
    }

    private fun tagExists(id: Long): Boolean = runBlocking {
        storage().withDatabase { it.tagDao().getTags().any { tag -> tag.id == id } }
    }

    private fun tagParentGroupId(id: Long): Long? = runBlocking {
        storage().withDatabase { it.tagDao().getTags().single { tag -> tag.id == id }.parentGroupId }
    }

    private fun groupParentGroupId(id: Long): Long? = runBlocking {
        storage().withDatabase { it.tagDao().getGroups().single { group -> group.id == id }.parentGroupId }
    }

    private fun groupExists(id: Long): Boolean = runBlocking {
        storage().withDatabase { it.tagDao().getGroups().any { group -> group.id == id } }
    }

    private fun databaseFingerprint(): List<String> = runBlocking {
        storage().withDatabase { database ->
            buildList {
                addAll(
                    database.clipDao().getActiveClips().map {
                        "clip:${it.id}:${it.authorId}:${it.authorName}:${it.authorUsername}:${it.text}:${it.xCreatedAt}:${it.summary}:${it.isDeleted}:${it.likeCount}:${it.likeCountFetchedAt}:${it.likeCountFetchFailedAt}:${it.likeCountFetchError}"
                    },
                )
                addAll(
                    database.clipDao().getAllAssets().map {
                        "asset:${it.id}:${it.clipId}:${it.mediaKey}:${it.type}:${it.remoteUrl}:${it.previewUrl}:${it.localPath}:${it.width}:${it.height}:${it.sizeBytes}:${it.downloadState}"
                    },
                )
                addAll(database.tagDao().getTags().map { "tag:${it.id}:${it.name}:${it.parentGroupId}:${it.sortOrder}" })
                addAll(database.clipDao().observeClipTags().first().map { "relation:${it.clipId}:${it.tagId}" })
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitUntil(condition: () -> Boolean) {
        composeRule.waitUntil(10_000, condition)
        composeRule.waitForIdle()
    }

    private fun tapOutsidePopup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val x = composeRule.activity.resources.displayMetrics.widthPixels - 2f
        val y = 100f
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun swipeLeftOnScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = composeRule.activity.resources.displayMetrics
        val downTime = SystemClock.uptimeMillis()
        val y = metrics.heightPixels * 0.55f
        val startX = metrics.widthPixels * 0.82f
        val endX = metrics.widthPixels * 0.18f
        val steps = 12
        val events = buildList {
            add(MotionEvent.ACTION_DOWN to startX)
            for (step in 1 until steps) {
                val fraction = step.toFloat() / steps
                add(MotionEvent.ACTION_MOVE to (startX + (endX - startX) * fraction))
            }
            add(MotionEvent.ACTION_UP to endX)
        }
        events.forEachIndexed { index, (action, x) ->
            val event = MotionEvent.obtain(downTime, downTime + index * 16L, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
        composeRule.waitForIdle()
    }
}
