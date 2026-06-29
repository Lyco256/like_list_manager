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
import com.lyco256.llm.data.PostStorageManager
import com.lyco256.llm.data.ClipTagEntity
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
        composeRule.onNodeWithTag("main_menu").performClick()
        composeRule.onNodeWithText("X API設定").performClick()
        composeRule.onNodeWithText("OAuth 2.0 Client ID").assertIsDisplayed()
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
        composeRule.onNodeWithTag("main_menu").performClick()
        composeRule.onNodeWithText("同期/使用量").performClick()
        composeRule.onNodeWithText("月間取得数: 0 / 1800").assertIsDisplayed()
        composeRule.onNodeWithText("警告ライン: 1500").assertIsDisplayed()
        composeRule.onNodeWithText("停止ライン: 2000").assertIsDisplayed()
        composeRule.onNodeWithText("15分制限: - / -").assertIsDisplayed()
        composeRule.onNodeWithText("閉じる").performClick()

        composeRule.onNodeWithTag("main_menu").performClick()
        composeRule.onNodeWithText("X API設定").performClick()
        composeRule.onNodeWithText("隔離テスト環境ではXログインを実行できません").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction() and hasText("OAuth 2.0 Client ID")).performTextInput("test-client-id")
        composeRule.onNodeWithText("保存してXにログイン").assertIsNotEnabled()
    }

    @Test
    fun storageLocationDialogDismissDoesNotChangeDatabase() {
        waitForSeededClip()
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("main_menu").performClick()
        composeRule.onNodeWithText("投稿データの保存先").performClick()
        composeRule.onNodeWithTag("post_storage_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("post_storage_close").performClick()
        composeRule.onNodeWithTag("main_screen").assertIsDisplayed()

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
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.onNodeWithText("変更を破棄しますか？").assertIsDisplayed()
        composeRule.onNodeWithText("破棄").performClick()
        composeRule.onNodeWithText("文字列:\"FilterNeedle\"", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("back-discarded-query")
        requestDiscardConfirmationWithBack()
        composeRule.onNodeWithText("変更を破棄しますか？").assertIsDisplayed()
        composeRule.onNodeWithText("破棄").performClick()
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
    fun tagManagementCreatesSameNamedChildrenThenRenamesAndDeletes() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val firstGroup = createRootGroup("E2EグループA")
        val secondGroup = createRootGroup("E2EグループB")
        composeRule.onNodeWithTag("tag_operation_group_$secondGroup").performClick()
        composeRule.onNodeWithText("名前を変更").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("E2E変更後グループ")
        composeRule.onNodeWithText("保存").performClick()
        waitUntil { groupsNamed("E2E変更後グループ") == 1 && groupsNamed("E2EグループB") == 0 }
        assertTrue(groupExists(secondGroup))

        val firstTag = createChildTag(firstGroup, "共有タグ")
        createChildTag(secondGroup, "共有タグ")
        assertEquals(2, tagsNamed("共有タグ"))

        composeRule.onNodeWithTag("tag_expand_group_$firstGroup").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$firstTag").performClick()
        composeRule.onNodeWithText("名前を変更").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("変更後タグ")
        composeRule.onNodeWithText("保存").performClick()
        waitUntil { tagsNamed("変更後タグ") == 1 }

        composeRule.onNodeWithTag("tag_operation_tag_$firstTag").performClick()
        composeRule.onNodeWithText("削除").performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitUntil { !tagExists(firstTag) }

        composeRule.onNodeWithTag("tag_operation_group_$firstGroup").performClick()
        composeRule.onNodeWithText("削除").performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitUntil { !groupExists(firstGroup) }
        assertTrue(groupExists(secondGroup))
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
        composeRule.onNodeWithTag("main_menu").performClick()
        composeRule.onNodeWithText("同期する").performClick()
        waitForText("同期結果")
        composeRule.onNodeWithText("X API設定からXにログインしてください").assertIsDisplayed()
        composeRule.onNodeWithText("閉じる").performClick()

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
        composeRule.onNode(hasSetTextAction()).performTextInput(name)
        composeRule.onNodeWithText("追加").performClick()
        waitUntil { tagsNamed(name) == 1 }
        return runBlocking { storage().withDatabase { it.tagDao().getTags().single { tag -> tag.name == name }.id } }
    }

    private fun createRootGroup(name: String): Long {
        composeRule.onNodeWithTag("create_root_group").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(name)
        composeRule.onNodeWithText("追加").performClick()
        waitUntil { groupsNamed(name) == 1 }
        return runBlocking { storage().withDatabase { it.tagDao().getGroups().single { group -> group.name == name }.id } }
    }

    private fun createChildTag(groupId: Long, name: String): Long {
        composeRule.onNodeWithTag("tag_operation_group_$groupId").performClick()
        composeRule.onNodeWithText("子タグを追加").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(name)
        composeRule.onNodeWithText("追加").performClick()
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

    private fun clipTagIds(clipId: Long): Set<Long> = runBlocking {
        storage().withDatabase { it.clipDao().clipTagsForClipIds(listOf(clipId)).map { relation -> relation.tagId }.toSet() }
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

    private fun groupExists(id: Long): Boolean = runBlocking {
        storage().withDatabase { it.tagDao().getGroups().any { group -> group.id == id } }
    }

    private fun databaseFingerprint(): List<String> = runBlocking {
        storage().withDatabase { database ->
            buildList {
                addAll(database.clipDao().getActiveClips().map { "clip:${it.id}:${it.text}:${it.xCreatedAt}:${it.summary}:${it.isDeleted}" })
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
}
