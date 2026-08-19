package com.lyco256.llm

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.PostStorageEstimate
import com.lyco256.llm.data.PostStorageLocation
import com.lyco256.llm.data.PostStorageState
import com.lyco256.llm.data.PostStorageType
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagWithCount
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class UiStateRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addAllTreeExcludesSourceAndClosesAfterOneRootTagSelection() {
        val now = "2026-08-13T00:00:00Z"
        val source = TagEntity(1, "Source", sortOrder = 0, createdAt = now, updatedAt = now)
        val target = TagEntity(2, "Root target", sortOrder = 1, createdAt = now, updatedAt = now)
        val hierarchy = TagHierarchy(tags = listOf(source, target).map { TagWithCount(it, 0) })
        var open by mutableStateOf(true)
        val selected = mutableListOf<Long>()

        composeRule.setContent {
            MaterialTheme {
                if (open) {
                    AddAllTagsDialog(
                        source = source,
                        hierarchy = hierarchy,
                        onDismiss = { open = false },
                        onAddAll = {
                            selected += it.id
                            open = false
                        },
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("add_all_target_tag_${source.id}").assertCountEquals(0)
        composeRule.onNodeWithTag("add_all_target_tag_${target.id}").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(listOf(target.id), selected) }
        composeRule.onAllNodesWithTag("add_all_dialog").assertCountEquals(0)
    }

    @Test
    fun addAllTreeGroupClickOnlyExpandsAndDeepTagCanBeSelected() {
        val now = "2026-08-13T00:00:00Z"
        val rootGroup = TagGroupEntity(10, "Root group", sortOrder = 0, createdAt = now, updatedAt = now)
        val deepGroup = TagGroupEntity(11, "Deep group", parentGroupId = rootGroup.id, sortOrder = 0, createdAt = now, updatedAt = now)
        val source = TagEntity(1, "Source", sortOrder = 1, createdAt = now, updatedAt = now)
        val deepTarget = TagEntity(2, "Same name", parentGroupId = deepGroup.id, createdAt = now, updatedAt = now)
        val selected = mutableListOf<Long>()

        composeRule.setContent {
            MaterialTheme {
                AddAllTagsDialog(
                    source = source,
                    hierarchy = TagHierarchy(
                        groups = listOf(rootGroup, deepGroup),
                        tags = listOf(source, deepTarget).map { TagWithCount(it, 0) },
                    ),
                    onDismiss = {},
                    onAddAll = { selected += it.id },
                )
            }
        }

        composeRule.onNodeWithTag("add_all_group_${rootGroup.id}").performClick()
        composeRule.runOnIdle { assertTrue(selected.isEmpty()) }
        composeRule.onNodeWithTag("add_all_group_${deepGroup.id}").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(selected.isEmpty()) }
        composeRule.onNodeWithTag("add_all_target_tag_${deepTarget.id}").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf(deepTarget.id), selected) }
    }

    @Test
    fun addAllTreeScrollsToAndSelectsLastTag() {
        val now = "2026-08-13T00:00:00Z"
        val source = TagEntity(1, "Source", sortOrder = 0, createdAt = now, updatedAt = now)
        val targets = (1..60).map { index ->
            TagEntity(
                id = 100L + index,
                name = "Target ${index.toString().padStart(2, '0')}",
                sortOrder = index,
                createdAt = now,
                updatedAt = now,
            )
        }
        val selected = mutableListOf<Long>()
        var open by mutableStateOf(true)

        composeRule.setContent {
            MaterialTheme {
                if (open) {
                    AddAllTagsDialog(
                        source = source,
                        hierarchy = TagHierarchy(tags = (listOf(source) + targets).map { TagWithCount(it, 0) }),
                        onDismiss = { open = false },
                        onAddAll = {
                            selected += it.id
                            open = false
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("add_all_tree_list").performScrollToIndex(targets.lastIndex)
        composeRule.onNodeWithTag("add_all_target_tag_${targets.last().id}").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(listOf(targets.last().id), selected) }
        composeRule.onAllNodesWithTag("add_all_dialog").assertCountEquals(0)
    }

    @Test
    fun addAllTreeCancelDoesNotSelectAnything() {
        val now = "2026-08-13T00:00:00Z"
        val source = TagEntity(1, "Source", createdAt = now, updatedAt = now)
        val target = TagEntity(2, "Target", createdAt = now, updatedAt = now)
        val selected = mutableListOf<Long>()
        var open by mutableStateOf(true)

        composeRule.setContent {
            MaterialTheme {
                if (open) {
                    AddAllTagsDialog(
                        source = source,
                        hierarchy = TagHierarchy(tags = listOf(source, target).map { TagWithCount(it, 0) }),
                        onDismiss = { open = false },
                        onAddAll = { selected += it.id },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("add_all_cancel").performClick()
        composeRule.runOnIdle { assertTrue(selected.isEmpty()) }
        composeRule.onAllNodesWithTag("add_all_dialog").assertCountEquals(0)
    }

    @Test
    fun filterStateLegendUsesMappedDotsAndWrapsWithoutLosingLabels() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(84.dp)) {
                    FilterTagStateLegend()
                }
            }
        }

        val states = listOf(
            TagFilterState.INCLUDED to "含む",
            TagFilterState.REQUIRED to "必須",
            TagFilterState.EXCLUDED to "排除",
        )
        val legendBounds = composeRule.onNodeWithTag("filter_tag_state_legend")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        states.forEach { (state, label) ->
            val suffix = state.name.lowercase()
            val labelBounds = composeRule.onNodeWithTag("filter_tag_state_label_$suffix")
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(labelBounds.left >= legendBounds.left)
            assertTrue(labelBounds.right <= legendBounds.right)
            assertTrue(labelBounds.top >= legendBounds.top)
            assertTrue(labelBounds.bottom <= legendBounds.bottom)
            composeRule.onNodeWithText(label).assertIsDisplayed()

            val pixels = composeRule.onNodeWithTag("filter_tag_state_dot_$suffix")
                .assertIsDisplayed()
                .captureToImage()
                .toPixelMap()
            val actual = pixels[pixels.width / 2, pixels.height / 2]
            val expected = tagFilterColors(state).container
            assertTrue(kotlin.math.abs(expected.red - actual.red) < 0.02f)
            assertTrue(kotlin.math.abs(expected.green - actual.green) < 0.02f)
            assertTrue(kotlin.math.abs(expected.blue - actual.blue) < 0.02f)
        }

        val firstLabelTop = composeRule.onNodeWithTag("filter_tag_state_label_included")
            .fetchSemanticsNode().boundsInRoot.top
        val lastLabelTop = composeRule.onNodeWithTag("filter_tag_state_label_excluded")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(lastLabelTop > firstLabelTop)
        composeRule.onAllNodesWithText("含: 緑 / 必: 青 / 除: オレンジ。グループは含む・排除のみです。")
            .assertCountEquals(0)
    }

    @Test
    fun classifiedToolbarHighlightsOnlyAppliedFilterAndSortStates() {
        var uiState by mutableStateOf(MainUiState())
        var displayMode by mutableStateOf(ClassifiedDisplayMode.Card)
        composeRule.setContent {
            MaterialTheme {
                TagFilterSummaryRow(
                    uiState = uiState,
                    hierarchy = TagHierarchy(),
                    displayMode = displayMode,
                    matchingClipCount = 0,
                    onOpen = {},
                    onOpenSort = {},
                    onToggleDisplayMode = {
                        displayMode = when (displayMode) {
                            ClassifiedDisplayMode.Card -> ClassifiedDisplayMode.MediaGrid
                            ClassifiedDisplayMode.MediaGrid -> ClassifiedDisplayMode.Card
                        }
                    },
                    interactionEnabled = true,
                )
            }
        }

        fun stateDescription(tag: String): String = composeRule.onNodeWithTag(tag)
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        fun containerSample(tag: String): Color {
            val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            return pixels[pixels.width / 2, (pixels.height - 5).coerceAtLeast(0)]
        }
        fun colorDistance(first: Color, second: Color): Float =
            kotlin.math.abs(first.red - second.red) +
                kotlin.math.abs(first.green - second.green) +
                kotlin.math.abs(first.blue - second.blue)

        val defaultFilterColor = containerSample("filter_open")
        val defaultSortColor = containerSample("sort_open")
        val defaultDisplayColor = containerSample("classified_display_toggle")
        assertEquals("未適用", stateDescription("filter_open"))
        assertEquals("未適用", stateDescription("sort_open"))
        assertTrue(colorDistance(defaultFilterColor, defaultSortColor) < 0.03f)
        assertTrue(colorDistance(defaultFilterColor, defaultDisplayColor) < 0.03f)
        composeRule.onAllNodesWithTag("filter_clear").assertCountEquals(0)

        composeRule.runOnIdle {
            uiState = uiState.copy(filters = TweetFilterState(query = "active"))
        }
        assertEquals("適用中", stateDescription("filter_open"))
        assertEquals("未適用", stateDescription("sort_open"))
        assertTrue(colorDistance(defaultFilterColor, containerSample("filter_open")) > 0.1f)
        assertTrue(colorDistance(defaultSortColor, containerSample("sort_open")) < 0.03f)

        composeRule.runOnIdle {
            uiState = uiState.copy(
                filters = TweetFilterState(),
                sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
            )
        }
        assertEquals("未適用", stateDescription("filter_open"))
        assertEquals("適用中", stateDescription("sort_open"))
        assertTrue(colorDistance(defaultFilterColor, containerSample("filter_open")) < 0.03f)
        assertTrue(colorDistance(defaultSortColor, containerSample("sort_open")) > 0.1f)

        composeRule.runOnIdle { uiState = uiState.copy(sort = ClassifiedSortState()) }
        assertEquals("未適用", stateDescription("sort_open"))
        assertTrue(colorDistance(defaultSortColor, containerSample("sort_open")) < 0.03f)
        composeRule.onNodeWithTag("classified_display_toggle").performClick()
        composeRule.runOnIdle { assertEquals(ClassifiedDisplayMode.MediaGrid, displayMode) }
        assertTrue(colorDistance(defaultDisplayColor, containerSample("classified_display_toggle")) < 0.03f)
    }

    @Test
    fun selectedFilterConditionsStayHorizontalCycleWithoutNoneAndRemoveSeparately() {
        val now = "2026-08-12T00:00:00Z"
        val firstGroup = TagGroupEntity(1, "Root A", createdAt = now, updatedAt = now)
        val secondGroup = TagGroupEntity(2, "Root B", createdAt = now, updatedAt = now)
        val tags = buildList {
            add(TagEntity(10, "Same", parentGroupId = firstGroup.id, createdAt = now, updatedAt = now))
            add(TagEntity(11, "Same", parentGroupId = secondGroup.id, createdAt = now, updatedAt = now))
            repeat(12) { index ->
                add(TagEntity(20L + index, "Condition $index", createdAt = now, updatedAt = now))
            }
        }
        val tagRefs = tags.map { TagNodeRef(TagNodeType.TAG, it.id) }
        val groupRef = TagNodeRef(TagNodeType.GROUP, firstGroup.id)
        var filters by mutableStateOf(
            buildMap {
                tagRefs.forEach { put(it, TagFilterState.INCLUDED) }
                put(groupRef, TagFilterState.INCLUDED)
            },
        )

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(320.dp)) {
                    SelectedTagConditionsRow(
                        hierarchy = TagHierarchy(
                            groups = listOf(firstGroup, secondGroup),
                            tags = tags.map { TagWithCount(it, 0) },
                        ),
                        filters = filters,
                        onCycle = { ref ->
                            filters = filters + (ref to nextSelectedFilterTagState(ref, filters.getValue(ref)))
                        },
                        onRemove = { ref -> filters = filters - ref },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Root A / Same").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_selected_tag_conditions").performScrollToIndex(1)
        composeRule.onNodeWithText("Root B / Same").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_selected_tag_conditions").performScrollToIndex(0)
        composeRule.onNodeWithTag("filter_selected_condition_tag_10").performClick()
        composeRule.onNodeWithText("必須").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_selected_condition_tag_10").performClick()
        composeRule.onNodeWithText("排除").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_selected_condition_tag_10").performClick()
        composeRule.onAllNodesWithTag("filter_selected_condition_tag_10").assertCountEquals(1)

        composeRule.onNodeWithTag("filter_selected_tag_conditions").performScrollToIndex(tagRefs.size)
        composeRule.onNodeWithTag("filter_selected_condition_group_1").performClick()
        composeRule.onAllNodesWithTag("filter_selected_condition_group_1").assertCountEquals(1)
        composeRule.onNodeWithTag("filter_selected_condition_group_1").performClick()
        composeRule.onAllNodesWithTag("filter_selected_condition_group_1").assertCountEquals(1)

        composeRule.onNodeWithTag("filter_selected_tag_conditions").performScrollToIndex(tagRefs.lastIndex)
        composeRule.onNodeWithTag("filter_selected_condition_tag_${tags.last().id}").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_selected_tag_conditions").performScrollToIndex(0)
        composeRule.onNodeWithTag("filter_selected_remove_tag_10").performClick()
        composeRule.onAllNodesWithTag("filter_selected_condition_tag_10").assertCountEquals(0)
    }

    @Test
    fun heavyWorkTopBarIndicatorIsSingleAndTracksVisibility() {
        var visible by mutableStateOf(true)

        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Row {
                    HeavyWorkTopBarIndicator(visible = visible)
                }
            }
        }

        composeRule.onAllNodesWithTag("top_heavy_work_indicator").assertCountEquals(1)

        composeRule.runOnIdle { visible = false }
        composeRule.onAllNodesWithTag("top_heavy_work_indicator").assertCountEquals(0)
    }

    @Test
    fun heavyWorkIndicatorRequiresTrackedWorkAndYieldsToDedicatedProgress() {
        assertFalse(
            shouldShowHeavyWorkIndicator(
                heavyLocalWorkActive = false,
                dedicatedProgressVisible = false,
            ),
        )
        assertTrue(
            shouldShowHeavyWorkIndicator(
                heavyLocalWorkActive = true,
                dedicatedProgressVisible = false,
            ),
        )
        assertFalse(
            shouldShowHeavyWorkIndicator(
                heavyLocalWorkActive = true,
                dedicatedProgressVisible = true,
            ),
        )
    }

    @Test
    fun unclassifiedInitialLoadingIsDistinctFromLoadedEmptyAndLaterEmpty() {
        val now = "2026-08-12T00:00:00Z"
        val clip = ClipWithDetails(
            clip = ClipEntity(
                id = 1,
                xPostId = "initial-load",
                authorName = "Initial",
                authorUsername = "initial",
                text = "Loaded post",
                postUrl = "https://x.com/initial/status/1",
                xCreatedAt = now,
                savedAt = now,
                syncedAt = now,
            ),
            assets = emptyList(),
            tags = emptyList(),
        )
        var state by mutableStateOf(MainUiState())

        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    UnclassifiedTopBarTitle(state)
                    EnhancedClipListScreen(
                        title = "未分類",
                        clips = state.unclassified,
                        hierarchy = state.tagHierarchy,
                        emptyText = "タグなしのツイートはありません",
                        listState = rememberLazyListState(),
                        isInitialLoading = state.isInitialClipLoading,
                        onTagsChange = { _, _ -> },
                        onSummaryChange = { _, _ -> },
                        onOcrSave = { _, _ -> },
                        onOcrDetect = { _, _, _ -> },
                        onDelete = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("未分類").assertIsDisplayed()
        composeRule.onAllNodesWithText("未分類 0件").assertCountEquals(0)
        composeRule.onNodeWithTag("unclassified_initial_loading").assertIsDisplayed()
        composeRule.onAllNodesWithText("タグなしのツイートはありません").assertCountEquals(0)

        composeRule.runOnIdle {
            state = MainUiState(hasReceivedInitialClipEmission = true)
        }
        composeRule.onNodeWithText("未分類 0件").assertIsDisplayed()
        composeRule.onAllNodesWithTag("unclassified_initial_loading").assertCountEquals(0)
        composeRule.onNodeWithText("タグなしのツイートはありません").assertIsDisplayed()

        composeRule.runOnIdle {
            state = MainUiState(
                clips = listOf(clip),
                hasReceivedInitialClipEmission = true,
            )
        }
        composeRule.onNodeWithText("未分類 1件").assertIsDisplayed()
        composeRule.onAllNodesWithTag("unclassified_initial_loading").assertCountEquals(0)

        composeRule.runOnIdle {
            state = MainUiState(hasReceivedInitialClipEmission = true)
        }
        composeRule.onNodeWithText("未分類 0件").assertIsDisplayed()
        composeRule.onAllNodesWithTag("unclassified_initial_loading").assertCountEquals(0)
    }

    @Test
    fun explicitStorageStatesSuppressInitialClipLoading() {
        val unavailableState = MainUiState(
            storageState = PostStorageState(isAvailable = false),
        )
        val migratingState = MainUiState(
            storageState = PostStorageState(isMigrating = true),
        )

        assertTrue(!unavailableState.isInitialClipLoading)
        assertTrue(!migratingState.isInitialClipLoading)
    }

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
    fun syncResultDialogShowsPermissionShortageMessageAndDismisses() {
        composeRule.setContent {
            MaterialTheme {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    SyncResultDialog(
                        message = "X APIの権限が不足しています。Developer Consoleの権限とスコープを確認してください",
                        onDismiss = { visible = false },
                    )
                }
            }
        }

        composeRule.onNodeWithText("同期結果").assertIsDisplayed()
        composeRule.onNodeWithText("X APIの権限が不足しています", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("閉じる").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("同期結果").fetchSemanticsNodes().isEmpty()
        }
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
    fun savedPhotoOpensFullScreenViewerAndCloseDismissesIt() {
        val assets = listOf(
            AssetEntity(
                id = 101,
                clipId = 1,
                mediaKey = "saved-photo",
                type = "photo",
                remoteUrl = "https://example.test/photo.jpg",
                previewUrl = null,
                localPath = "/tmp/saved-photo.webp",
                width = 1200,
                height = 800,
                createdAt = "2026-01-01T00:00:00Z",
            ),
            AssetEntity(
                id = 102,
                clipId = 1,
                mediaKey = "video-thumb",
                type = "video_thumbnail",
                remoteUrl = null,
                previewUrl = "https://example.test/video.jpg",
                localPath = null,
                width = 1200,
                height = 800,
                createdAt = "2026-01-01T00:00:00Z",
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                EnhancedMediaGrid(assets)
            }
        }

        composeRule.onNodeWithTag("media_asset_101").assertIsDisplayed()
        composeRule.onNodeWithTag("media_asset_101").performClick()
        composeRule.onNodeWithTag("image_viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("image_viewer_position").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
        composeRule.onNodeWithTag("image_viewer_close").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("image_viewer").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun mediaGridUsesExpectedLayout() {
        val baseAssets = listOf(
            asset(201, "one"),
            asset(202, "two"),
            asset(203, "three"),
            asset(204, "four"),
        )

        assertMediaGridGeometry(tagPrefix = "media_grid_cell", assets = baseAssets.map { it.remoteUrl!! }, useEnhanced = false)
    }

    @Test
    fun enhancedMediaGridUsesExpectedLayout() {
        val baseAssets = listOf(
            asset(201, "one"),
            asset(202, "two"),
            asset(203, "three"),
            asset(204, "four"),
        )

        assertMediaGridGeometry(tagPrefix = "media_asset", assets = baseAssets, useEnhanced = true)
    }

    @Test
    fun cellPlaceholderRenderingStaysIndependentInLightAndDarkThemes() {
        var darkTheme by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                Row {
                    Box(
                        Modifier
                            .size(80.dp)
                            .mediaGridPlaceholder(
                                visualState = MediaGridCellVisualState.Placeholder,
                                startColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 1f),
                                endColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
                            )
                            .testTag("placeholder_cell_a"),
                    )
                    Box(
                        Modifier
                            .size(80.dp)
                            .mediaGridPlaceholder(
                                visualState = MediaGridCellVisualState.Placeholder,
                                startColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 1f),
                                endColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
                            )
                            .testTag("placeholder_cell_b"),
                    )
                    Box(
                        Modifier
                            .size(80.dp)
                            .mediaGridPlaceholder(
                                visualState = MediaGridCellVisualState.Image,
                                startColor = Color.Red,
                                endColor = Color.Blue,
                            )
                            .testTag("image_cell"),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("placeholder_cell_a").assertIsDisplayed()
        composeRule.onNodeWithTag("placeholder_cell_b").assertIsDisplayed()
        composeRule.runOnUiThread { darkTheme = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("placeholder_cell_a").assertIsDisplayed()
        composeRule.onNodeWithTag("placeholder_cell_b").assertIsDisplayed()
        composeRule.onNodeWithTag("image_cell").assertIsDisplayed()
    }

    @Test
    fun classifiedMediaGridShowsDateHeadersVideoBadgeAndErrorCells() {
        val clips = listOf(
            ClipWithDetails(
                clip = ClipEntity(
                    id = 1,
                    xPostId = "grid-empty",
                    authorName = "Empty",
                    authorUsername = "empty",
                    text = "No media clip",
                    postUrl = "https://x.com/empty/status/1",
                    xCreatedAt = "2026-01-01T00:00:00Z",
                    savedAt = "2026-01-01T00:00:00Z",
                    syncedAt = "2026-01-01T00:00:00Z",
                ),
                assets = emptyList(),
                tags = emptyList(),
            ),
            ClipWithDetails(
                clip = ClipEntity(
                    id = 2,
                    xPostId = "grid-media",
                    authorName = "Media",
                    authorUsername = "media",
                    text = "Media clip",
                    postUrl = "https://x.com/media/status/2",
                    xCreatedAt = "2026-01-01T00:00:00Z",
                    savedAt = "2026-01-01T00:00:00Z",
                    syncedAt = "2026-01-01T00:00:00Z",
                    likeCount = 1_234,
                ),
                assets = listOf(
                    AssetEntity(
                        id = 21,
                        clipId = 2,
                        mediaKey = "grid-photo",
                        type = "photo",
                        remoteUrl = "https://example.test/grid-photo.jpg",
                        previewUrl = null,
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                    AssetEntity(
                        id = 22,
                        clipId = 2,
                        mediaKey = "grid-video",
                        type = "video_thumbnail",
                        remoteUrl = null,
                        previewUrl = "https://example.test/grid-video.jpg",
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                    AssetEntity(
                        id = 23,
                        clipId = 2,
                        mediaKey = "grid-error",
                        type = "photo",
                        remoteUrl = null,
                        previewUrl = null,
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        downloadState = "failed",
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                    AssetEntity(
                        id = 24,
                        clipId = 2,
                        mediaKey = "grid-missing",
                        type = "photo",
                        remoteUrl = "https://example.test/grid-missing.jpg",
                        previewUrl = null,
                        localPath = "/tmp/missing-grid.webp",
                        width = 1200,
                        height = 1200,
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                    AssetEntity(
                        id = 25,
                        clipId = 2,
                        mediaKey = "grid-photo-2",
                        type = "photo",
                        remoteUrl = "https://example.test/grid-photo-2.jpg",
                        previewUrl = null,
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                ),
                tags = listOf(
                    TagEntity(
                        id = 10,
                        name = "GridTag",
                        createdAt = "2026-01-01T00:00:00Z",
                        updatedAt = "2026-01-01T00:00:00Z",
                    ),
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                var columnCount by remember { mutableStateOf(ClassifiedMediaGridDefaultColumnCount) }
                Box(Modifier.requiredWidth(400.dp)) {
                    EnhancedClassifiedScreen(
                        uiState = com.lyco256.llm.MainUiState(
                            clips = clips,
                            sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
                        ),
                        mediaGridState = ClassifiedMediaGridState(
                            entries = listOf(
                                MediaGridEntry(21, 2, 21, "grid-photo", 0, "photo", "https://example.test/grid-photo.jpg", "downloaded", null, "2026-01-01T00:00:00Z", 1_234),
                                MediaGridEntry(22, 2, 22, "grid-video", 1, "video_thumbnail", "https://example.test/grid-video.jpg", "downloaded", null, "2026-01-01T00:00:00Z", 1_234),
                                MediaGridEntry(23, 2, 23, "grid-error", 2, "photo", null, "failed", null, "2026-01-01T00:00:00Z", 1_234),
                                MediaGridEntry(24, 2, 24, "grid-missing", 3, "photo", "https://example.test/grid-missing.jpg", "downloaded", "/tmp/missing-grid.webp", "2026-01-01T00:00:00Z", 1_234),
                                MediaGridEntry(25, 2, 25, "grid-photo-2", 4, "photo", "https://example.test/grid-photo-2.jpg", "downloaded", null, "2026-01-01T00:00:00Z", 1_234),
                            ),
                            matchingClipCount = 1,
                            matchingMediaCount = 5,
                            isEmptyByFilter = false,
                            hasMatchingClipButNoMedia = false,
                        ),
                        listState = rememberLazyListState(),
                        displayMode = ClassifiedDisplayMode.MediaGrid,
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
        }

        composeRule.onNodeWithTag("classified_media_grid").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_header_post_time_day_2026-01-01").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_header_text_post_time_day_2026-01-01").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_item_21").fetchSemanticsNode()
        composeRule.onNodeWithTag("media_grid_item_22").fetchSemanticsNode()
        composeRule.onNodeWithTag("media_grid_item_23").fetchSemanticsNode()
        composeRule.onNodeWithTag("media_grid_item_24").fetchSemanticsNode()
        composeRule.onNodeWithTag("media_grid_item_25").fetchSemanticsNode()
        composeRule.onNodeWithTag("media_grid_video_badge_22").fetchSemanticsNode()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("media_grid_error_23").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("media_grid_error_24").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("media_grid_error_23").fetchSemanticsNode()
        composeRule.onNodeWithTag("media_grid_error_24").fetchSemanticsNode()

        fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val first = bounds("media_grid_item_21")
        val second = bounds("media_grid_item_22")
        val third = bounds("media_grid_item_23")
        val fourth = bounds("media_grid_item_24")
        val fifth = bounds("media_grid_item_25")

        assertTrue("grid should keep first four cells aligned", first.top == second.top && second.top == third.top && third.top == fourth.top)
        assertTrue("grid should wrap the fifth cell", fifth.top > first.top)
        assertTrue("grid cells should stay square", kotlin.math.abs(first.width - first.height) < 1f)
        assertTrue("grid cells should not have horizontal spacing", kotlin.math.abs(second.left - first.right) < 1f)
    }

    @Test
    fun classifiedMediaGridShowsLikeHeadersAndLikeOverlaysWithoutBreakingBadgesOrErrors() {
        val clips = listOf(
            ClipWithDetails(
                clip = ClipEntity(
                    id = 1,
                    xPostId = "grid-like",
                    authorName = "Like",
                    authorUsername = "like",
                    text = "Like clip",
                    postUrl = "https://x.com/like/status/1",
                    xCreatedAt = "2026-01-01T00:00:00Z",
                    savedAt = "2026-01-01T00:00:00Z",
                    syncedAt = "2026-01-01T00:00:00Z",
                    likeCount = 12_345,
                ),
                assets = listOf(
                    AssetEntity(
                        id = 31,
                        clipId = 1,
                        mediaKey = "like-photo",
                        type = "photo",
                        remoteUrl = "https://example.test/like-photo.jpg",
                        previewUrl = null,
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                    AssetEntity(
                        id = 32,
                        clipId = 1,
                        mediaKey = "like-video",
                        type = "video_thumbnail",
                        remoteUrl = null,
                        previewUrl = "https://example.test/like-video.jpg",
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                    AssetEntity(
                        id = 33,
                        clipId = 1,
                        mediaKey = "like-error",
                        type = "photo",
                        remoteUrl = null,
                        previewUrl = null,
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        downloadState = "failed",
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                ),
                tags = listOf(
                    TagEntity(
                        id = 11,
                        name = "LikeTag",
                        createdAt = "2026-01-01T00:00:00Z",
                        updatedAt = "2026-01-01T00:00:00Z",
                    ),
                ),
            ),
            ClipWithDetails(
                clip = ClipEntity(
                    id = 2,
                    xPostId = "grid-like-null",
                    authorName = "Null",
                    authorUsername = "null",
                    text = "Null clip",
                    postUrl = "https://x.com/null/status/2",
                    xCreatedAt = "2026-01-01T00:00:00Z",
                    savedAt = "2026-01-01T00:00:00Z",
                    syncedAt = "2026-01-01T00:00:00Z",
                ),
                assets = listOf(
                    AssetEntity(
                        id = 41,
                        clipId = 2,
                        mediaKey = "null-photo",
                        type = "photo",
                        remoteUrl = "https://example.test/null-photo.jpg",
                        previewUrl = null,
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        createdAt = "2026-01-01T00:00:00Z",
                    ),
                ),
                tags = listOf(
                    TagEntity(
                        id = 12,
                        name = "NullTag",
                        createdAt = "2026-01-01T00:00:00Z",
                        updatedAt = "2026-01-01T00:00:00Z",
                    ),
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(400.dp)) {
                    EnhancedClassifiedScreen(
                        uiState = com.lyco256.llm.MainUiState(
                            clips = clips,
                            sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount),
                        ),
                        mediaGridState = ClassifiedMediaGridState(
                            entries = listOf(
                                MediaGridEntry(31, 1, 31, "like-photo", 0, "photo", "https://example.test/like-photo.jpg", "downloaded", null, "2026-01-01T00:00:00Z", 12_345),
                                MediaGridEntry(32, 1, 32, "like-video", 1, "video_thumbnail", "https://example.test/like-video.jpg", "downloaded", null, "2026-01-01T00:00:00Z", 12_345),
                                MediaGridEntry(33, 1, 33, "like-error", 2, "photo", null, "failed", null, "2026-01-01T00:00:00Z", 12_345),
                                MediaGridEntry(41, 2, 41, "null-photo", 0, "photo", "https://example.test/null-photo.jpg", "downloaded", null, "2026-01-01T00:00:00Z", null),
                            ),
                            matchingClipCount = 2,
                            matchingMediaCount = 4,
                            isEmptyByFilter = false,
                            hasMatchingClipButNoMedia = false,
                        ),
                        listState = rememberLazyListState(),
                        displayMode = ClassifiedDisplayMode.MediaGrid,
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
        }

        composeRule.onNodeWithTag("media_grid_header_like_count_200_12200").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_header_text_like_count_200_12200").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_like_count_31").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_like_count_32").assertIsDisplayed()
        composeRule.onAllNodesWithTag("media_grid_like_count_41").assertCountEquals(0)
        composeRule.onNodeWithTag("media_grid_video_badge_32").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_error_33").assertIsDisplayed()
    }

    @Test
    fun classifiedMediaGridHandlesLargeDatasetsWithoutCrashing() {
        val clips = (1L..80L).map { clipId ->
            ClipWithDetails(
                clip = ClipEntity(
                    id = clipId,
                    xPostId = "large-grid-$clipId",
                    authorName = "Author $clipId",
                    authorUsername = "author$clipId",
                    text = "Large grid clip $clipId",
                    postUrl = "https://x.com/author$clipId/status/$clipId",
                    xCreatedAt = "2026-01-${((clipId - 1) % 20 + 1).toString().padStart(2, '0')}T00:00:00Z",
                    savedAt = "2026-01-01T00:00:00Z",
                    syncedAt = "2026-01-01T00:00:00Z",
                    likeCount = if (clipId % 3L == 0L) 120_000 else 1_000L * clipId,
                ),
                assets = (0L until 4L).map { index ->
                    AssetEntity(
                        id = clipId * 10 + index,
                        clipId = clipId,
                        mediaKey = "large-$clipId-$index",
                        type = if (index == 1L) "video_thumbnail" else "photo",
                        remoteUrl = if (index == 2L && clipId % 10L == 0L) null else "https://example.test/large-$clipId-$index.jpg",
                        previewUrl = null,
                        localPath = null,
                        width = 1200,
                        height = 1200,
                        downloadState = if (index == 2L && clipId % 10L == 0L) "failed" else "downloaded",
                        createdAt = "2026-01-01T00:00:00Z",
                    )
                },
                tags = emptyList(),
            )
        }

        val entries = clips.flatMap { clip ->
            clip.assets.map { asset ->
                MediaGridEntry(
                    entryId = asset.id,
                    clipId = clip.clip.id,
                    assetId = asset.id,
                    mediaKey = asset.mediaKey,
                    mediaIndex = (asset.id % 4).toInt(),
                    type = asset.type,
                    displayUrl = asset.remoteUrl,
                    downloadState = asset.downloadState,
                    localPath = asset.localPath,
                    xCreatedAt = clip.clip.xCreatedAt,
                    likeCount = clip.clip.likeCount,
                )
            }
        }
        val targetIndex = buildClassifiedMediaGridItems(
            entries,
            ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
            ClassifiedMediaGridDefaultColumnCount,
        ).indexOfFirst { it.key == "media_grid_item_400" }

        var columnCount by mutableStateOf(ClassifiedMediaGridDefaultColumnCount)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(400.dp)) {
                    EnhancedClassifiedScreen(
                        uiState = com.lyco256.llm.MainUiState(
                            clips = clips,
                            sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
                        ),
                        mediaGridState = ClassifiedMediaGridState(
                            entries = entries,
                            matchingClipCount = clips.size,
                            matchingMediaCount = entries.size,
                            isEmptyByFilter = false,
                            hasMatchingClipButNoMedia = false,
                        ),
                        listState = rememberLazyListState(),
                        displayMode = ClassifiedDisplayMode.MediaGrid,
                        mediaGridColumnCount = columnCount,
                        onMediaGridColumnCountChange = { columnCount = it },
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
        }

        composeRule.waitUntil(30_000) {
            runCatching {
                composeRule.onNodeWithTag("classified_media_grid").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("media_grid_item_10").assertIsDisplayed()
        composeRule.onNodeWithTag("classified_media_grid").performScrollToIndex(targetIndex)
        composeRule.waitUntil(30_000) {
            runCatching {
                composeRule.onNodeWithTag("media_grid_item_400").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.runOnIdle {
            columnCount = 2
        }
        composeRule.waitUntil(30_000) {
            runCatching {
                composeRule.onNodeWithTag("classified_media_grid").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("classified_media_grid").performScrollToIndex(targetIndex - 1)
        composeRule.onNodeWithTag("media_grid_header_post_time_day_2026-01-20").assertIsDisplayed()
        val gridBounds = composeRule.onNodeWithTag("classified_media_grid").fetchSemanticsNode().boundsInRoot
        val dayHeaderBounds = composeRule.onNodeWithTag("media_grid_header_post_time_day_2026-01-20").fetchSemanticsNode().boundsInRoot
        assertTrue(dayHeaderBounds.left <= gridBounds.left + 1f)
        assertTrue(dayHeaderBounds.right >= gridBounds.right - 1f)
        composeRule.runOnIdle {
            columnCount = 9
        }
        composeRule.waitUntil(30_000) {
            runCatching {
                composeRule.onNodeWithTag("classified_media_grid").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("classified_media_grid").performScrollToIndex(0)
        composeRule.onNodeWithTag("media_grid_header_post_time_month_2026-01").assertIsDisplayed()
        val monthHeaderBounds = composeRule.onNodeWithTag("media_grid_header_post_time_month_2026-01").fetchSemanticsNode().boundsInRoot
        assertTrue(monthHeaderBounds.left <= gridBounds.left + 1f)
        assertTrue(monthHeaderBounds.right >= gridBounds.right - 1f)
    }

    @Test
    fun classifiedMediaGridShowsTheExistingEmptyStateWhenNothingMatches() {
        composeRule.setContent {
            MaterialTheme {
                EnhancedClassifiedScreen(
                    uiState = com.lyco256.llm.MainUiState(clips = emptyList()),
                    mediaGridState = ClassifiedMediaGridState(),
                    listState = rememberLazyListState(),
                    displayMode = ClassifiedDisplayMode.MediaGrid,
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

        composeRule.onNodeWithText("条件に合うツイートはありません").assertIsDisplayed()
    }

    @Test
    fun mediaGridTweetDialogUsesAnOutsideCloseLayerForEveryState() {
        val details = tagDraftClip(id = 199, tags = emptyList())
        var state by mutableStateOf<MediaGridTweetDialogState>(MediaGridTweetDialogState.Loading)
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = state,
                    hierarchy = TagHierarchy(),
                    onDismiss = { state = MediaGridTweetDialogState.Closed },
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, onResult, _ -> onResult("") },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }

        composeRule.runOnIdle { assertTrue(state is MediaGridTweetDialogState.Loading) }
        composeRule.onAllNodesWithText("ツイート").assertCountEquals(0)
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog_scrim").assertCountEquals(1)
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog_close").assertCountEquals(1)
        val cardBounds = composeRule.onNodeWithTag("media_grid_tweet_dialog").fetchSemanticsNode().boundsInRoot
        val closeBounds = composeRule.onNodeWithTag("media_grid_tweet_dialog_close").fetchSemanticsNode().boundsInRoot
        assertTrue(closeBounds.bottom <= cardBounds.top)
        assertTrue(closeBounds.right >= cardBounds.right - 1f)

        composeRule.onNodeWithTag("media_grid_tweet_dialog").performClick()
        composeRule.runOnIdle { assertTrue(state is MediaGridTweetDialogState.Loading) }
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog").assertCountEquals(1)

        composeRule.runOnIdle { state = MediaGridTweetDialogState.NotFound }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue(state is MediaGridTweetDialogState.NotFound) }
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog").assertCountEquals(1)
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog_scrim").assertCountEquals(1)
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog_close").assertCountEquals(1)

        composeRule.runOnIdle { state = MediaGridTweetDialogState.Loaded(details) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(details.clip.id, (state as MediaGridTweetDialogState.Loaded).clip.clip.id)
        }
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog").assertCountEquals(1)
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog_scrim").assertCountEquals(1)
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog_close").assertCountEquals(1)
        composeRule.onNodeWithTag("media_grid_tweet_dialog_scrim").performTouchInput {
            click(Offset(2f, center.y))
        }
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog").assertCountEquals(0)

        composeRule.runOnIdle { state = MediaGridTweetDialogState.Loading }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("media_grid_tweet_dialog_close").performClick()
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog").assertCountEquals(0)

        composeRule.runOnIdle { state = MediaGridTweetDialogState.NotFound }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue(state is MediaGridTweetDialogState.NotFound) }
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog").assertCountEquals(1)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog").assertCountEquals(0)
    }

    @Test
    fun classifiedMediaGridSelectsWholeTweetsAndStagesBulkTags() {
        val now = "2026-01-01T00:00:00Z"
        val firstTag = TagEntity(id = 10, name = "First", createdAt = now, updatedAt = now)
        val secondTag = TagEntity(id = 11, name = "Second", createdAt = now, updatedAt = now)
        val hierarchy = TagHierarchy(tags = listOf(TagWithCount(firstTag, 1), TagWithCount(secondTag, 1)))
        var columnCount by mutableStateOf(4)
        var openedClipId by mutableStateOf<Long?>(null)
        var applied by mutableStateOf<Pair<Set<Long>, Pair<Set<Long>, Set<Long>>>?>(null)
        val entries = listOf(
            MediaGridEntry(101, 1, 101, "one-a", 0, "photo", "https://example.test/one-a.jpg", "downloaded", null, now, 99),
            MediaGridEntry(102, 1, 102, "one-b", 1, "video_thumbnail", "https://example.test/one-b.jpg", "downloaded", null, now, 99),
            MediaGridEntry(201, 2, 201, "two", 0, "photo", "https://example.test/two.jpg", "downloaded", null, now, 50),
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(400.dp)) {
                    EnhancedClassifiedScreen(
                        uiState = MainUiState(
                            tagHierarchy = hierarchy,
                            sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount),
                        ),
                        mediaGridState = ClassifiedMediaGridState(
                            entries = entries,
                            tagIdsByClip = mapOf(1L to longArrayOf(10L), 2L to longArrayOf(11L)),
                            matchingClipCount = 2,
                            matchingMediaCount = 3,
                            isEmptyByFilter = false,
                        ),
                        listState = rememberLazyListState(),
                        displayMode = ClassifiedDisplayMode.MediaGrid,
                        mediaGridColumnCount = columnCount,
                        onMediaGridColumnCountChange = { columnCount = it },
                        onMediaGridCellClick = { openedClipId = it },
                        onMediaGridBulkTagsChange = { clipIds, addTagIds, removeTagIds, onResult ->
                            applied = clipIds to (addTagIds to removeTagIds)
                            onResult(null)
                        },
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
        }

        composeRule.onNodeWithTag("media_grid_item_101").performTouchInput { longClick() }
        composeRule.onNodeWithText("1件選択中").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_selection_101").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_selection_102").assertIsDisplayed()
        composeRule.onAllNodesWithTag("media_grid_like_count_101").assertCountEquals(0)
        composeRule.onNodeWithTag("media_grid_selection_open_101").performClick()
        composeRule.runOnIdle { assertEquals(1L, openedClipId) }

        composeRule.onNodeWithTag("media_grid_select_all").performClick()
        composeRule.onNodeWithText("2件選択中").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_bulk_tag_open").performClick()
        composeRule.onNodeWithTag("tag_chip_10").performClick()
        composeRule.runOnIdle { assertEquals(null, applied) }
        composeRule.onNodeWithTag("media_grid_bulk_tag_cancel").performClick()
        composeRule.onNodeWithTag("media_grid_bulk_tag_discard_confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_bulk_tag_discard_cancel").performClick()
        composeRule.onNodeWithTag("media_grid_bulk_tag_apply").performClick()
        composeRule.onNodeWithTag("media_grid_bulk_tag_apply_confirm").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(setOf(1L, 2L) to (emptySet<Long>() to setOf(10L)), applied) }

        composeRule.runOnIdle { columnCount = 7 }
        composeRule.onNodeWithTag("media_grid_item_101").performTouchInput { longClick() }
        composeRule.onAllNodesWithTag("media_grid_selection_open_101").assertCountEquals(0)
    }

    @Test
    fun mediaGridTweetDialogReusesCardActionsAndClosesBeforeLocalDelete() {
        val clip = ClipEntity(
            id = 42,
            xPostId = "dialog-actions",
            authorName = "Dialog Author",
            authorUsername = "dialog_author",
            text = "Dialog action test",
            postUrl = "https://x.com/dialog_author/status/dialog-actions",
            xCreatedAt = "2026-01-01T00:00:00Z",
            savedAt = "2026-01-01T00:00:00Z",
            syncedAt = "2026-01-01T00:00:00Z",
        )
        val localFile = File.createTempFile("media-grid-dialog", ".jpg")
        val asset = AssetEntity(
            id = 420,
            clipId = clip.id,
            mediaKey = "dialog-action-photo",
            type = "photo",
            remoteUrl = "https://example.test/dialog-action.jpg",
            previewUrl = null,
            localPath = localFile.absolutePath,
            width = 1200,
            height = 800,
            createdAt = "2026-01-01T00:00:00Z",
        )
        val tag = TagEntity(
            id = 7,
            name = "Dialog Tag",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
        val details = ClipWithDetails(clip = clip, assets = listOf(asset), tags = emptyList())
        var selectedTagIds = emptySet<Long>()
        var savedSummary: String? = null
        var savedOcr: String? = null
        var deletedClipId: Long? = null
        var clickedAuthor = false
        var dialogState by mutableStateOf<MediaGridTweetDialogState>(MediaGridTweetDialogState.Loaded(details))
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = dialogState,
                    hierarchy = TagHierarchy(tags = listOf(TagWithCount(tag, 0))),
                    onDismiss = { dialogState = MediaGridTweetDialogState.Closed },
                    onTagsChange = { _, ids -> selectedTagIds = ids },
                    onSummaryChange = { _, summary -> savedSummary = summary },
                    onOcrSave = { _, text -> savedOcr = text },
                    onOcrDetect = { _, onResult, _ -> onResult("") },
                    onDelete = { deletedClipId = it.id },
                    onAuthorClick = { clickedAuthor = true },
                )
            }
        }

        composeRule.onNodeWithTag("media_asset_${asset.id}").performClick()
        composeRule.onNodeWithTag("image_viewer").assertIsDisplayed()
        composeRule.onNodeWithTag("image_viewer_close").performClick()
        composeRule.onNodeWithTag("media_grid_tweet_dialog").assertIsDisplayed()

        composeRule.onNodeWithTag("classify_${clip.id}").assertIsNotEnabled()
        composeRule.onNodeWithTag("tag_chip_${tag.id}").performClick()
        composeRule.onNodeWithTag("media_grid_tweet_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("classify_${clip.id}").assertIsEnabled()
        composeRule.runOnIdle { assertEquals(emptySet<Long>(), selectedTagIds) }
        composeRule.onNodeWithTag("classify_${clip.id}").performClick()
        composeRule.runOnIdle { assertEquals(setOf(tag.id), selectedTagIds) }
        composeRule.onNodeWithTag("media_grid_tweet_dialog").assertIsDisplayed()

        composeRule.onNodeWithTag("tweet_options_button_${clip.id}").performClick()
        composeRule.onNodeWithTag("tweet_options_summary").performClick()
        composeRule.onNodeWithTag("summary_text").performTextReplacement("updated summary")
        composeRule.onNodeWithTag("summary_save").performClick()
        composeRule.runOnIdle { assertEquals("updated summary", savedSummary) }

        composeRule.onNodeWithTag("tweet_options_button_${clip.id}").performClick()
        composeRule.onNodeWithTag("tweet_options_ocr").performClick()
        composeRule.onNodeWithTag("ocr_result_text").performTextReplacement("updated OCR")
        composeRule.onNodeWithTag("ocr_confirm").performClick()
        composeRule.runOnIdle { assertEquals("updated OCR", savedOcr) }

        composeRule.onNodeWithTag("clip_author_${clip.id}").performClick()
        composeRule.runOnIdle { assertTrue(clickedAuthor) }
        composeRule.onNodeWithTag("clip_open_x_${clip.id}").assertIsDisplayed()

        composeRule.onNodeWithTag("tweet_options_button_${clip.id}").performClick()
        composeRule.onNodeWithTag("tweet_options_local_delete").performClick()
        composeRule.onNodeWithTag("clip_local_delete_confirm_${clip.id}").performClick()
        composeRule.runOnIdle { assertEquals(clip.id, deletedClipId) }
        localFile.delete()
    }

    @Test
    fun closingMediaGridPreviewDiscardsAnUnappliedTagDraft() {
        val tag = TagEntity(70, "Preview Draft", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z")
        val details = tagDraftClip(id = 70, tags = emptyList())
        var state by mutableStateOf<MediaGridTweetDialogState>(MediaGridTweetDialogState.Loaded(details))
        var appliedTagIds: Set<Long>? = null
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = state,
                    hierarchy = TagHierarchy(tags = listOf(TagWithCount(tag, 0))),
                    onDismiss = { state = MediaGridTweetDialogState.Closed },
                    onTagsChange = { _, ids -> appliedTagIds = ids },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("tag_chip_${tag.id}").performClick()
        composeRule.onNodeWithTag("media_grid_tweet_dialog_close").performClick()
        composeRule.onAllNodesWithTag("media_grid_tweet_dialog").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(null, appliedTagIds) }
    }

    @Test
    fun unclassifiedCardEnablesApplyOnlyForAChangedTagDraft() {
        val tag = TagEntity(71, "Draft Tag", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z")
        val details = tagDraftClip(id = 71, tags = emptyList())
        var appliedTagIds: Set<Long>? = null
        composeRule.setContent {
            MaterialTheme {
                EnhancedClipListScreen(
                    title = "draft-test",
                    clips = listOf(details),
                    hierarchy = TagHierarchy(tags = listOf(TagWithCount(tag, 0))),
                    emptyText = "empty",
                    listState = rememberLazyListState(),
                    requireTagConfirmation = true,
                    onTagsChange = { _, ids -> appliedTagIds = ids },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithTag("classify_${details.clip.id}").assertIsNotEnabled()
        composeRule.onNodeWithTag("tag_chip_${tag.id}").performClick()
        composeRule.onNodeWithTag("classify_${details.clip.id}").assertIsEnabled()
        composeRule.onNodeWithTag("tag_chip_${tag.id}").performClick()
        composeRule.onNodeWithTag("classify_${details.clip.id}").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(null, appliedTagIds) }
        composeRule.onNodeWithTag("tag_chip_${tag.id}").performClick()
        composeRule.onNodeWithTag("classify_${details.clip.id}").performClick()
        composeRule.runOnIdle { assertEquals(setOf(tag.id), appliedTagIds) }
    }

    @Test
    fun classifiedCardDoesNotPersistTagChipChangesUntilApply() {
        val first = TagEntity(81, "First", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z")
        val second = TagEntity(82, "Second", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z")
        val details = tagDraftClip(id = 81, tags = listOf(first))
        var appliedTagIds: Set<Long>? = null
        var failApply = true
        composeRule.setContent {
            MaterialTheme {
                EnhancedClassifiedScreen(
                    uiState = MainUiState(
                        clips = listOf(details),
                        tagHierarchy = TagHierarchy(tags = listOf(TagWithCount(first, 1), TagWithCount(second, 0))),
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
                        appliedTagIds = ids
                        complete(if (failApply) "failure" else null)
                    },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("classify_${details.clip.id}").assertIsNotEnabled()
        composeRule.onNodeWithTag("tag_chip_${second.id}").performClick()
        composeRule.runOnIdle { assertEquals(null, appliedTagIds) }
        composeRule.onNodeWithTag("classify_${details.clip.id}").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(setOf(first.id, second.id), appliedTagIds) }
        composeRule.onNodeWithTag("tag_apply_error_${details.clip.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("classify_${details.clip.id}").assertIsEnabled()
        composeRule.runOnIdle {
            failApply = false
            appliedTagIds = null
        }
        composeRule.onNodeWithTag("classify_${details.clip.id}").performClick()
        composeRule.runOnIdle { assertEquals(setOf(first.id, second.id), appliedTagIds) }
        composeRule.onNodeWithTag("classify_${details.clip.id}").assertIsNotEnabled()
    }

    @Test
    fun unclassifiedTweetCardKeepsApplyFixedWhileOnlyTagsScrollHorizontally() {
        val tags = horizontalScrollTags()
        val details = tagDraftClip(id = 200, tags = emptyList())
        val hierarchy = TagHierarchy(tags = tags.map { TagWithCount(it, 0) })
        var appliedTagIds: Set<Long>? = null
        composeRule.setContent {
            MaterialTheme {
                EnhancedClipListScreen(
                    title = "unclassified-card",
                    clips = listOf(details),
                    hierarchy = hierarchy,
                    emptyText = "empty",
                    listState = rememberLazyListState(),
                    onTagsChange = { _, _ -> },
                    onTagsApply = { _, ids, complete -> appliedTagIds = ids; complete(null) },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                )
            }
        }
        assertTagSelectorScrollKeepsApplyFixed(
            details = details,
            hierarchy = hierarchy,
            toggleTag = tags.first(),
            expectedAppliedTagIds = setOf(tags.first().id),
            appliedTagIds = { appliedTagIds },
        )
    }

    @Test
    fun classifiedTweetCardKeepsApplyFixedWhileOnlyTagsScrollHorizontally() {
        val tags = horizontalScrollTags()
        val details = tagDraftClip(id = 200, tags = listOf(tags.last()))
        val hierarchy = TagHierarchy(tags = tags.map { TagWithCount(it, 0) })
        var appliedTagIds: Set<Long>? = null
        composeRule.setContent {
            MaterialTheme {
                EnhancedClassifiedScreen(
                    uiState = MainUiState(clips = listOf(details), tagHierarchy = hierarchy),
                    mediaGridState = ClassifiedMediaGridState(),
                    listState = rememberLazyListState(),
                    displayMode = ClassifiedDisplayMode.Card,
                    mediaGridColumnCount = ClassifiedMediaGridDefaultColumnCount,
                    onMediaGridColumnCountChange = {},
                    onToggleDisplayMode = {},
                    onApplyFilters = {},
                    onApplySort = {},
                    onTagsChange = { _, _ -> },
                    onTagsApply = { _, ids, complete -> appliedTagIds = ids; complete(null) },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }
        assertTagSelectorScrollKeepsApplyFixed(
            details = details,
            hierarchy = hierarchy,
            toggleTag = tags.first(),
            expectedAppliedTagIds = setOf(tags.last().id, tags.first().id),
            appliedTagIds = { appliedTagIds },
        )
    }

    private fun horizontalScrollTags() = (1L..16L).map { index ->
        TagEntity(
            id = 200L + index,
            name = "Long tag name $index",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
    }

    private fun assertTagSelectorScrollKeepsApplyFixed(
        details: ClipWithDetails,
        hierarchy: TagHierarchy,
        toggleTag: TagEntity,
        expectedAppliedTagIds: Set<Long>,
        appliedTagIds: () -> Set<Long>?,
    ) {
        val applyTag = "classify_${details.clip.id}"
        val selectorTag = "tag_selector_${details.clip.id}"
        val orderedRootTagIds = hierarchy.children(null).map { it.id }
        val tailTagId = orderedRootTagIds.last()
        composeRule.onNodeWithTag(applyTag, useUnmergedTree = true).assertIsNotEnabled()
        val selector = composeRule.onNodeWithTag(selectorTag, useUnmergedTree = true)
        val selectorConfig = selector.fetchSemanticsNode().config
        assertTrue(selectorConfig.contains(SemanticsProperties.HorizontalScrollAxisRange))
        assertFalse(selectorConfig.contains(SemanticsProperties.VerticalScrollAxisRange))
        composeRule.onNodeWithTag("tag_chip_${toggleTag.id}", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(applyTag, useUnmergedTree = true).assertIsEnabled()
        val applyBeforeScroll = composeRule.onNodeWithTag(applyTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        selector.performScrollToIndex(orderedRootTagIds.lastIndex)
        composeRule.onAllNodesWithTag("tag_chip_$tailTagId", useUnmergedTree = true).assertCountEquals(1)
        val applyAfterScroll = composeRule.onNodeWithTag(applyTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(applyBeforeScroll.left, applyAfterScroll.left, 0.5f)
        assertEquals(applyBeforeScroll.top, applyAfterScroll.top, 0.5f)
        composeRule.runOnIdle { assertEquals(null, appliedTagIds()) }
        composeRule.onNodeWithTag(applyTag, useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertEquals(expectedAppliedTagIds, appliedTagIds()) }
    }

    @Test
    fun cardWithoutTagsStillShowsAnEmptySelectorAndDisabledApply() {
        val details = tagDraftClip(id = 201, tags = emptyList())
        composeRule.setContent {
            MaterialTheme {
                EnhancedClipListScreen(
                    title = "no-tags",
                    clips = listOf(details),
                    hierarchy = TagHierarchy(),
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

        composeRule.onNodeWithTag("tag_selector_${details.clip.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("classify_${details.clip.id}").assertIsDisplayed().assertIsNotEnabled()
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
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithTag("clip_list").performScrollToIndex(999)
        composeRule.onNodeWithTag("clip_card_1000").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_list").performScrollToIndex(0)
        composeRule.onNodeWithTag("clip_card_1").assertIsDisplayed()
    }

    private fun tagDraftClip(id: Long, tags: List<TagEntity>): ClipWithDetails = ClipWithDetails(
        clip = ClipEntity(
            id = id,
            xPostId = "tag-draft-$id",
            authorName = "Draft Author",
            authorUsername = "draft_author",
            text = "Tag draft test",
            postUrl = "https://x.com/draft_author/status/$id",
            xCreatedAt = "2026-01-01T00:00:00Z",
            savedAt = "2026-01-01T00:00:00Z",
            syncedAt = "2026-01-01T00:00:00Z",
        ),
        assets = emptyList(),
        tags = tags,
    )

    private fun asset(id: Long, mediaKey: String): AssetEntity = AssetEntity(
        id = id,
        clipId = 1,
        mediaKey = mediaKey,
        type = "photo",
        remoteUrl = "https://example.test/$mediaKey.jpg",
        previewUrl = null,
        localPath = "/tmp/$mediaKey.webp",
        width = 1200,
        height = 800,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun mediaGridCellNearestViewportCenterTag(): String {
        val gridBounds = composeRule.onNodeWithTag("classified_media_grid").fetchSemanticsNode().boundsInRoot
        val viewportCenterY = (gridBounds.top + gridBounds.bottom) / 2f
        return composeRule.onAllNodes(
            matcher = SemanticsMatcher("media grid cell") { node ->
                SemanticsProperties.TestTag in node.config &&
                    node.config[SemanticsProperties.TestTag].startsWith("media_grid_item_")
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .minByOrNull { node ->
                val bounds = node.boundsInRoot
                kotlin.math.abs((bounds.top + bounds.bottom) / 2f - viewportCenterY)
            }
            ?.let { node -> node.config[SemanticsProperties.TestTag] }
            ?: error("No visible media-grid cell was found")
    }

    private fun assertMediaGridGeometry(
        tagPrefix: String,
        assets: List<Any>,
        useEnhanced: Boolean,
    ) {
        fun tagAt(index: Int): String = if (useEnhanced) {
            "${tagPrefix}_${(assets[index] as AssetEntity).id}"
        } else {
            "${tagPrefix}_$index"
        }
        fun node(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        fun assertWithin(message: String, condition: Boolean) = assertTrue(message, condition)

        val visibleCount = mutableStateOf(1)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(400.dp)) {
                    if (useEnhanced) {
                        EnhancedMediaGrid(assets.take(visibleCount.value).map { it as AssetEntity })
                    } else {
                        MediaGrid(assets.take(visibleCount.value).map { it as String })
                    }
                }
            }
        }
        composeRule.onAllNodesWithTag(tagAt(0)).assertCountEquals(1)
        val single = node(tagAt(0))
        assertWithin("single cell should have positive left padding", single.left > 0f)
        assertWithin("single cell should stay inside the container", single.right > single.left)

        composeRule.runOnIdle { visibleCount.value = 2 }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(tagAt(0)).assertCountEquals(1)
        composeRule.onAllNodesWithTag(tagAt(1)).assertCountEquals(1)
        val firstPair = node(tagAt(0))
        val secondPair = node(tagAt(1))
        assertWithin("two-cell grid should keep the first row aligned", firstPair.top == secondPair.top)
        assertWithin("two-cell grid should place the second cell to the right", secondPair.left > firstPair.left)
        assertWithin("two-cell grid should not overlap horizontally", secondPair.left > firstPair.right)

        composeRule.runOnIdle { visibleCount.value = 3 }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(tagAt(0)).assertCountEquals(1)
        composeRule.onAllNodesWithTag(tagAt(1)).assertCountEquals(1)
        composeRule.onAllNodesWithTag(tagAt(2)).assertCountEquals(1)
        val third = node(tagAt(2))
        assertWithin("three-cell grid should wrap to a new row", third.top > firstPair.top)
        assertWithin("three-cell grid should still start inside the container", third.left > 0f)

        composeRule.runOnIdle { visibleCount.value = 4 }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(tagAt(0)).assertCountEquals(1)
        composeRule.onAllNodesWithTag(tagAt(1)).assertCountEquals(1)
        composeRule.onAllNodesWithTag(tagAt(2)).assertCountEquals(1)
        composeRule.onAllNodesWithTag(tagAt(3)).assertCountEquals(1)
        val fourth = node(tagAt(3))
        assertWithin("four-cell grid should keep the bottom row aligned", third.top == fourth.top)
        assertWithin("four-cell grid should place the last cell to the right of the third", fourth.left > third.left)
    }
}
