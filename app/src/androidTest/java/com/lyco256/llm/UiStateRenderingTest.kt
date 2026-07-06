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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.PostStorageEstimate
import com.lyco256.llm.data.PostStorageLocation
import com.lyco256.llm.data.PostStorageType
import com.lyco256.llm.data.TagHierarchy
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.Assert.assertTrue
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
    fun mediaGridsUseWiderSidePaddingAndKeepCommonLayouts() {
        val baseAssets = listOf(
            asset(201, "one"),
            asset(202, "two"),
            asset(203, "three"),
            asset(204, "four"),
        )

        assertMediaGridGeometry(tagPrefix = "media_grid_cell", assets = baseAssets.map { it.remoteUrl!! }, useEnhanced = false)
        assertMediaGridGeometry(tagPrefix = "media_asset", assets = baseAssets, useEnhanced = true)
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

    private fun assertMediaGridGeometry(
        tagPrefix: String,
        assets: List<Any>,
        useEnhanced: Boolean,
    ) {
        val rootWidthPx = px(400f)
        val sidePaddingPx = px(16f)
        val rowSpacingPx = px(3f)

        fun node(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        fun assertClose(expected: Float, actual: Float, tolerance: Float = px(1.25f)) {
            assertTrue("expected=$expected actual=$actual", abs(expected - actual) <= tolerance)
        }

        composeRule.setContent { MaterialTheme { Box(Modifier.requiredWidth(400.dp)) { if (useEnhanced) EnhancedMediaGrid(assets.take(1).map { it as AssetEntity }) else MediaGrid(assets.take(1).map { it as String }) } } }
        composeRule.onNodeWithTag("${tagPrefix}_0").assertIsDisplayed()
        val single = node("${tagPrefix}_0")
        assertClose(sidePaddingPx, single.left)
        assertClose(rootWidthPx - sidePaddingPx, single.right)

        composeRule.setContent { MaterialTheme { Box(Modifier.requiredWidth(400.dp)) { if (useEnhanced) EnhancedMediaGrid(assets.take(2).map { it as AssetEntity }) else MediaGrid(assets.take(2).map { it as String }) } } }
        composeRule.onNodeWithTag("${tagPrefix}_0").assertIsDisplayed()
        composeRule.onNodeWithTag("${tagPrefix}_1").assertIsDisplayed()
        val firstPair = node("${tagPrefix}_0")
        val secondPair = node("${tagPrefix}_1")
        assertClose(sidePaddingPx, firstPair.left)
        assertClose(firstPair.top, secondPair.top)
        assertClose(rowSpacingPx, secondPair.left - firstPair.right)

        composeRule.setContent { MaterialTheme { Box(Modifier.requiredWidth(400.dp)) { if (useEnhanced) EnhancedMediaGrid(assets.take(3).map { it as AssetEntity }) else MediaGrid(assets.take(3).map { it as String }) } } }
        composeRule.onNodeWithTag("${tagPrefix}_0").assertIsDisplayed()
        composeRule.onNodeWithTag("${tagPrefix}_1").assertIsDisplayed()
        composeRule.onNodeWithTag("${tagPrefix}_2").assertIsDisplayed()
        val third = node("${tagPrefix}_2")
        assertTrue(third.top > firstPair.top)
        assertClose(sidePaddingPx, third.left)

        composeRule.setContent { MaterialTheme { Box(Modifier.requiredWidth(400.dp)) { if (useEnhanced) EnhancedMediaGrid(assets.take(4).map { it as AssetEntity }) else MediaGrid(assets.take(4).map { it as String }) } } }
        composeRule.onNodeWithTag("${tagPrefix}_0").assertIsDisplayed()
        composeRule.onNodeWithTag("${tagPrefix}_1").assertIsDisplayed()
        composeRule.onNodeWithTag("${tagPrefix}_2").assertIsDisplayed()
        composeRule.onNodeWithTag("${tagPrefix}_3").assertIsDisplayed()
        val fourth = node("${tagPrefix}_3")
        assertClose(third.top, fourth.top)
        assertClose(sidePaddingPx, fourth.left)
    }

    private fun px(dp: Float): Float =
        dp * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
}
