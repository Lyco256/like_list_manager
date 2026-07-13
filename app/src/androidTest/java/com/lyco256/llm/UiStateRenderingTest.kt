package com.lyco256.llm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
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
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.PostStorageEstimate
import com.lyco256.llm.data.PostStorageLocation
import com.lyco256.llm.data.PostStorageType
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagWithCount
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
                                MediaGridEntry(21, 2, 21, "grid-photo", 0, "photo", "https://example.test/grid-photo.jpg", "downloaded", false, null, "2026-01-01T00:00:00Z", 1_234),
                                MediaGridEntry(22, 2, 22, "grid-video", 1, "video_thumbnail", "https://example.test/grid-video.jpg", "downloaded", false, null, "2026-01-01T00:00:00Z", 1_234),
                                MediaGridEntry(23, 2, 23, "grid-error", 2, "photo", null, "failed", false, null, "2026-01-01T00:00:00Z", 1_234),
                                MediaGridEntry(24, 2, 24, "grid-missing", 3, "photo", "https://example.test/grid-missing.jpg", "downloaded", false, "/tmp/missing-grid.webp", "2026-01-01T00:00:00Z", 1_234),
                                MediaGridEntry(25, 2, 25, "grid-photo-2", 4, "photo", "https://example.test/grid-photo-2.jpg", "downloaded", false, null, "2026-01-01T00:00:00Z", 1_234),
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
                        onClearAllFilters = {},
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
                                MediaGridEntry(31, 1, 31, "like-photo", 0, "photo", "https://example.test/like-photo.jpg", "downloaded", false, null, "2026-01-01T00:00:00Z", 12_345),
                                MediaGridEntry(32, 1, 32, "like-video", 1, "video_thumbnail", "https://example.test/like-video.jpg", "downloaded", false, null, "2026-01-01T00:00:00Z", 12_345),
                                MediaGridEntry(33, 1, 33, "like-error", 2, "photo", null, "failed", false, null, "2026-01-01T00:00:00Z", 12_345),
                                MediaGridEntry(41, 2, 41, "null-photo", 0, "photo", "https://example.test/null-photo.jpg", "downloaded", false, null, "2026-01-01T00:00:00Z", null),
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
                        onClearAllFilters = {},
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
                    hasLocalFile = false,
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
                        onClearAllFilters = {},
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
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("classified_media_grid").assertIsDisplayed()
        composeRule.onNodeWithTag("classified_media_grid").performScrollToIndex(targetIndex - 1)
        composeRule.onNodeWithTag("media_grid_header_post_time_day_2026-01-20").assertIsDisplayed()
        val gridBounds = composeRule.onNodeWithTag("classified_media_grid").fetchSemanticsNode().boundsInRoot
        val dayHeaderBounds = composeRule.onNodeWithTag("media_grid_header_post_time_day_2026-01-20").fetchSemanticsNode().boundsInRoot
        assertTrue(dayHeaderBounds.left <= gridBounds.left + 1f)
        assertTrue(dayHeaderBounds.right >= gridBounds.right - 1f)
        composeRule.runOnIdle {
            columnCount = 9
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("classified_media_grid").assertIsDisplayed()
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
                    onClearAllFilters = {},
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
    fun mediaGridTweetDialogShowsLoadingAndNotFoundStatesAndCanClose() {
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

        composeRule.onNodeWithTag("media_grid_tweet_dialog_loading").assertIsDisplayed()
        composeRule.runOnIdle { state = MediaGridTweetDialogState.NotFound }
        composeRule.onNodeWithTag("media_grid_tweet_dialog_error").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_tweet_dialog_close").performClick()
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
            MediaGridEntry(101, 1, 101, "one-a", 0, "photo", "https://example.test/one-a.jpg", "downloaded", false, null, now, 99),
            MediaGridEntry(102, 1, 102, "one-b", 1, "video_thumbnail", "https://example.test/one-b.jpg", "downloaded", false, null, now, 99),
            MediaGridEntry(201, 2, 201, "two", 0, "photo", "https://example.test/two.jpg", "downloaded", false, null, now, 50),
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
                            tagIdsByClip = mapOf(1L to setOf(10L), 2L to setOf(11L)),
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
                        onClearAllFilters = {},
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
        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = MediaGridTweetDialogState.Loaded(details),
                    hierarchy = TagHierarchy(tags = listOf(TagWithCount(tag, 0))),
                    onDismiss = {},
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

        composeRule.onNodeWithTag("tag_chip_${tag.id}").performClick()
        composeRule.runOnIdle { assertEquals(setOf(tag.id), selectedTagIds) }

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
        composeRule.onNodeWithTag(tagAt(0)).assertExists()
        val single = node(tagAt(0))
        assertWithin("single cell should have positive left padding", single.left > 0f)
        assertWithin("single cell should stay inside the container", single.right > single.left)

        composeRule.runOnIdle { visibleCount.value = 2 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(tagAt(0)).assertExists()
        composeRule.onNodeWithTag(tagAt(1)).assertExists()
        val firstPair = node(tagAt(0))
        val secondPair = node(tagAt(1))
        assertWithin("two-cell grid should keep the first row aligned", firstPair.top == secondPair.top)
        assertWithin("two-cell grid should place the second cell to the right", secondPair.left > firstPair.left)
        assertWithin("two-cell grid should not overlap horizontally", secondPair.left > firstPair.right)

        composeRule.runOnIdle { visibleCount.value = 3 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(tagAt(0)).assertExists()
        composeRule.onNodeWithTag(tagAt(1)).assertExists()
        composeRule.onNodeWithTag(tagAt(2)).assertExists()
        val third = node(tagAt(2))
        assertWithin("three-cell grid should wrap to a new row", third.top > firstPair.top)
        assertWithin("three-cell grid should still start inside the container", third.left > 0f)

        composeRule.runOnIdle { visibleCount.value = 4 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(tagAt(0)).assertExists()
        composeRule.onNodeWithTag(tagAt(1)).assertExists()
        composeRule.onNodeWithTag(tagAt(2)).assertExists()
        composeRule.onNodeWithTag(tagAt(3)).assertExists()
        val fourth = node(tagAt(3))
        assertWithin("four-cell grid should keep the bottom row aligned", third.top == fourth.top)
        assertWithin("four-cell grid should place the last cell to the right of the third", fourth.left > third.left)
    }
}
