package com.lyco256.llm

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipTagEntity
import com.lyco256.llm.data.MediaGridImageCandidateInput
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import com.lyco256.llm.data.MediaGridPersistentPreviewMetadata
import com.lyco256.llm.data.MediaGridPersistentPreviewStore
import com.lyco256.llm.data.PostStorageManager
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagColorId
import com.lyco256.llm.data.buildMediaGridImageCandidates
import com.lyco256.llm.data.buildMediaGridImageRequest
import com.lyco256.llm.data.mediaGridImageCacheKey
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivityComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetTestData() = runBlocking {
        storage().withDatabase { it.clearAllTables() }
        (composeRule.activity.application as LikeListManagerApp).container.repository.ensureSeedData()
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("main_screen").fetchSemanticsNodes().isNotEmpty()
        }
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
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("保存件数:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
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
    fun classifiedDisplayToggleSwitchesBetweenCardAndMediaGridAndSurvivesActivityRecreation() {
        fun waitDisplayed(tag: String) {
            runCatching {
                composeRule.waitUntil(30_000) {
                    runCatching {
                        composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                        true
                    }.getOrDefault(false)
                }
            }.getOrElse { cause ->
                throw AssertionError("Timed out waiting for displayed testTag: $tag", cause)
            }
        }
        val now = Instant.now().toString()
        val photoPath = File(storage().imageDirectory(), "classified-grid-photo.webp").apply {
            writeBytes(bitmapBytes(4, 4, android.graphics.Color.RED))
        }.absolutePath
        val videoPath = File(storage().imageDirectory(), "classified-grid-video.webp").apply {
            writeBytes(bitmapBytes(4, 4, android.graphics.Color.BLUE))
        }.absolutePath
        val clipAndAssetIds = runBlocking {
            storage().withDatabase { database ->
                val clipId = database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "classified-grid-1",
                        authorName = "Grid Author",
                        authorUsername = "grid_author",
                        text = "Classified grid clip",
                        postUrl = "https://x.com/grid_author/status/classified-grid-1",
                        xCreatedAt = now,
                        savedAt = now,
                        syncedAt = now,
                    ),
                )
                val tagId = database.tagDao().insertTag(
                    TagEntity(
                        name = "GridTag",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
                database.clipDao().insertAssets(
                    listOf(
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "grid-photo",
                            type = "photo",
                            remoteUrl = null,
                            previewUrl = null,
                            localPath = photoPath,
                            width = 1200,
                            height = 1200,
                            sizeBytes = null,
                            downloadState = "downloaded",
                            createdAt = now,
                        ),
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "grid-video",
                            type = "video_thumbnail",
                            remoteUrl = null,
                            previewUrl = null,
                            localPath = videoPath,
                            width = 1200,
                            height = 1200,
                            sizeBytes = null,
                            downloadState = "downloaded",
                            createdAt = now,
                        ),
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "grid-error",
                            type = "photo",
                            remoteUrl = null,
                            previewUrl = null,
                            localPath = null,
                            width = 1200,
                            height = 1200,
                            sizeBytes = null,
                            downloadState = "failed",
                            createdAt = now,
                        ),
                    ),
                )
                clipId to database.clipDao().assetsForClipIds(listOf(clipId))
                    .filter { it.mediaKey.startsWith("grid-") }
                    .associate { it.mediaKey to it.id }
            }
        }
        val clipId = clipAndAssetIds.first
        val assetIds = clipAndAssetIds.second
        val previewSource = File(storage().imageDirectory(), "classified-grid-persistent-preview-source.jpg").apply {
            writeBytes(bitmapBytes(4, 4, android.graphics.Color.GREEN))
        }
        val previewStore = MediaGridPersistentPreviewStore(composeRule.activity.filesDir)
        runBlocking {
            assertEquals(
                com.lyco256.llm.data.MediaGridPreviewGenerationResult.GENERATED,
                previewStore.generate(assetIds.getValue("grid-photo"), previewSource) { true },
            )
        }
        waitUntil { clipTagIds(clipId).isNotEmpty() }

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("classified_display_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("classified_display_toggle").assertIsDisplayed()
        if (composeRule.onAllNodesWithTag("clip_card_$clipId").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("classified_display_toggle").performClick()
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithTag("clip_card_$clipId").fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        composeRule.onNodeWithTag("classified_display_toggle").performClick()

        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("classified_media_grid").fetchSemanticsNodes().isNotEmpty()
        }
        waitDisplayed("classified_media_grid")
        val photoTag = "media_grid_item_${assetIds.getValue("grid-photo")}"
        waitDisplayed(photoTag)
        val photoPlaceholderTag = "media_grid_placeholder_${assetIds.getValue("grid-photo")}"
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag(photoPlaceholderTag).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag(photoPlaceholderTag).fetchSemanticsNodes().isEmpty())
        val persistentPreview = previewStore.previewFile(assetIds.getValue("grid-photo"))
        val previewCandidate = buildMediaGridImageCandidates(
            MediaGridImageCandidateInput(
                assetId = assetIds.getValue("grid-photo"),
                mediaKey = "grid-photo",
                localPath = photoPath,
                previewUrl = null,
                remoteUrl = null,
                displayUrl = photoPath,
                persistentPreview = MediaGridPersistentPreviewMetadata(
                    filePath = persistentPreview.absolutePath,
                    length = persistentPreview.length(),
                    lastModified = persistentPreview.lastModified(),
                ),
            ),
        ).first()
        assertEquals(MediaGridImageSourceKind.PersistentPreview, previewCandidate.kind)
        val previewMemoryKey = mediaGridImageCacheKey(previewCandidate, 256, 256)
        composeRule.waitUntil(30_000) {
            (composeRule.activity.application as LikeListManagerApp).container.mediaGridImageLoader.memoryCache
                ?.get(coil.memory.MemoryCache.Key(previewMemoryKey)) != null
        }
        val videoPlaceholderTag = "media_grid_placeholder_${assetIds.getValue("grid-video")}"
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag(videoPlaceholderTag).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(composeRule.onAllNodesWithTag(videoPlaceholderTag).fetchSemanticsNodes().isEmpty())
        waitDisplayed("media_grid_video_badge_${assetIds.getValue("grid-video")}")
        val errorTag = "media_grid_error_${assetIds.getValue("grid-error")}"
        composeRule.onNodeWithTag("classified_media_grid").performScrollToIndex(2)
        waitDisplayed(errorTag)
        assertTrue(composeRule.onAllNodesWithTag("media_grid_placeholder_${assetIds.getValue("grid-error")}").fetchSemanticsNodes().isEmpty())

        composeRule.waitUntil(30_000) {
            !mainViewModel().mediaGridSessionState.value.showInitialProgress
        }
        waitForGridColumnCount(ClassifiedMediaGridDefaultColumnCount)
        val beforePinchColumns = mainViewModel().mediaGridSessionState.value.columnCount
        MediaGridMorphTestTrace.clear()
        pinchOnGrid("classified_media_grid", centerSpan = 260f, endSpan = 180f)
        waitForMorphCanvasRemoval()
        assertEquals(1, MediaGridMorphTestTrace.fallbackCount())
        val afterPinchInColumns = mediaGridMorphTargetColumnCount(
            beforePinchColumns,
            MediaGridMorphDirection.IncreaseColumns,
        )
        waitForGridColumnCount(afterPinchInColumns)
        assertTrue(composeRule.onAllNodesWithTag("media_grid_morph_overlay", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        MediaGridMorphTestTrace.clear()
        pinchOnGrid("classified_media_grid", centerSpan = 180f, endSpan = 260f)
        waitForMorphCanvasRemoval()
        assertEquals(1, MediaGridMorphTestTrace.fallbackCount())
        val afterPinchOutColumns = mediaGridMorphTargetColumnCount(
            afterPinchInColumns,
            MediaGridMorphDirection.DecreaseColumns,
        )
        waitForGridColumnCount(afterPinchOutColumns)
        assertTrue(composeRule.onAllNodesWithTag("media_grid_morph_overlay", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        MediaGridMorphTestTrace.clear()
        pinchOnGrid("classified_media_grid", centerSpan = 260f, endSpan = 258f)
        waitForMorphCanvasRemoval()
        assertEquals(0, MediaGridMorphTestTrace.fallbackCount())
        waitForGridColumnCount(afterPinchOutColumns)

        composeRule.onNodeWithTag(photoTag, useUnmergedTree = true).performClick()
        waitDisplayed("media_grid_tweet_dialog")
        composeRule.onNodeWithTag("media_grid_tweet_dialog_close").performClick()
        waitDisplayed("classified_media_grid")

        composeRule.onNodeWithTag("classified_media_grid").performScrollToIndex(2)
        waitDisplayed(errorTag)

        composeRule.activityRule.scenario.recreate()

        waitDisplayed("classified_screen")
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("classified_media_grid").fetchSemanticsNodes().isNotEmpty()
        }
        waitDisplayed("classified_media_grid")
        waitDisplayed(photoTag)
        waitForGridColumnCount(beforePinchColumns)

        composeRule.onNodeWithTag("classified_display_toggle").performClick()
        waitDisplayed("clip_card_$clipId")
        composeRule.onNodeWithTag("classified_display_toggle").performClick()
        waitDisplayed("classified_media_grid")
        waitForGridColumnCount(beforePinchColumns)

        // A source-revision change from filter and sort must also produce its first viewport
        // without a compensating scroll or a user tap on the grid.
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("Classified grid clip")
        composeRule.onNodeWithTag("filter_apply").performClick()
        waitDisplayed(photoTag)

        composeRule.onNodeWithTag("sort_open").performClick()
        composeRule.onNodeWithTag("sort_base_like").performClick()
        composeRule.onNodeWithTag("sort_like_direction_high").performClick()
        composeRule.onNodeWithTag("sort_apply").performClick()
        waitDisplayed(photoTag)

        composeRule.onNodeWithTag(photoTag, useUnmergedTree = true).performClick()
        waitDisplayed("media_grid_tweet_dialog")
        waitDisplayed("clip_card_$clipId")
        composeRule.onNodeWithTag("media_grid_tweet_dialog_close").performClick()
        waitDisplayed("classified_media_grid")
        waitForGridColumnCount(beforePinchColumns)

        val videoTag = "media_grid_item_${assetIds.getValue("grid-video")}"
        composeRule.onNodeWithTag(videoTag, useUnmergedTree = true).performClick()
        waitDisplayed("media_grid_tweet_dialog")
        waitDisplayed("clip_card_$clipId")
        composeRule.onNodeWithTag("media_grid_tweet_dialog_close").performClick()
    }

    @Test
    fun productionMorphClaimDrawsBeforePhysicalUpWhenFirstPointerStartsScroll() {
        val now = Instant.now().toString()
        val paths = (0 until 12).map { index ->
            File(storage().imageDirectory(), "production-claim-path-$index.webp").apply {
                writeBytes(bitmapBytes(8, 8, if (index % 2 == 0) android.graphics.Color.MAGENTA else android.graphics.Color.CYAN))
            }.absolutePath
        }
        val clipAndAssetIds = runBlocking {
            storage().withDatabase { database ->
                val clipId = database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "production-claim-path",
                        authorName = "Production Claim Author",
                        authorUsername = "production_claim_author",
                        text = "Production claim path regression",
                        postUrl = "https://x.com/production_claim_author/status/production-claim-path",
                        xCreatedAt = now,
                        savedAt = now,
                        syncedAt = now,
                    ),
                )
                val tagId = database.tagDao().insertTag(
                    TagEntity(name = "ProductionClaimPathTag", createdAt = now, updatedAt = now),
                )
                database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
                database.clipDao().insertAssets(
                    paths.mapIndexed { index, path ->
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "production-claim-path-$index",
                            type = "photo",
                            remoteUrl = null,
                            previewUrl = null,
                            localPath = path,
                            width = 1200,
                            height = 1200,
                            sizeBytes = null,
                            downloadState = "downloaded",
                            createdAt = now,
                        )
                    },
                )
                clipId to database.clipDao().assetsForClipIds(listOf(clipId)).map { it.id }
            }
        }
        val clipId = clipAndAssetIds.first
        val assetIds = clipAndAssetIds.second
        val previewStore = MediaGridPersistentPreviewStore(composeRule.activity.filesDir)
        runBlocking {
            assetIds.zip(paths).forEach { (assetId, path) ->
                previewStore.generate(assetId, File(path)) { true }
            }
        }

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("classified_display_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("Production claim path regression")
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.waitUntil(30_000) {
            assetIds.any { assetId ->
                composeRule.onAllNodesWithTag("media_grid_item_$assetId").fetchSemanticsNodes().isNotEmpty()
            } || composeRule.onAllNodesWithTag("clip_card_$clipId").fetchSemanticsNodes().isNotEmpty()
        }
        if (assetIds.none { assetId ->
                composeRule.onAllNodesWithTag("media_grid_item_$assetId").fetchSemanticsNodes().isNotEmpty()
            }
        ) {
            composeRule.onNodeWithTag("classified_display_toggle").performClick()
        }
        composeRule.waitUntil(30_000) {
            assetIds.any { assetId ->
                composeRule.onAllNodesWithTag("media_grid_item_$assetId").fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.waitUntil(30_000) { !mainViewModel().mediaGridSessionState.value.showInitialProgress }
        assertEquals(4, mainViewModel().mediaGridSessionState.value.columnCount)
        composeRule.onNodeWithTag("classified_media_grid").performScrollToIndex(0)
        composeRule.waitForIdle()

        val viewModel = mainViewModel()
        val residentStore = viewModel.mediaGridSessionState.value.retainedImageStore
            ?: error("Production retained image store is unavailable")
        val imageLoader = (composeRule.activity.application as LikeListManagerApp).container.mediaGridImageLoader
        runBlocking {
            assetIds.zip(paths).forEachIndexed { index, (assetId, path) ->
                val previewFile = previewStore.previewFile(assetId)
                val source = buildMediaGridImageCandidates(
                    MediaGridImageCandidateInput(
                        assetId = assetId,
                        mediaKey = "production-claim-path-$index",
                        localPath = path,
                        previewUrl = null,
                        remoteUrl = null,
                        displayUrl = path,
                        persistentPreview = MediaGridPersistentPreviewMetadata(
                            filePath = previewFile.absolutePath,
                            length = previewFile.length(),
                            lastModified = previewFile.lastModified(),
                        ),
                    ),
                ).first()
                val candidate = MediaGridPreparedCandidate(
                    kind = source.kind,
                    requestData = File(source.data as String),
                    sourceIdentity = source.sourceIdentity,
                    cacheKey = mediaGridImageCacheKey(source, 256, 256),
                    width = 256,
                    height = 256,
                    useDiskCache = false,
                )
                imageLoader.execute(buildMediaGridImageRequest(composeRule.activity, candidate))
                val value = imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(candidate.cacheKey))
                    ?: error("Asset $assetId was not present in the image cache")
                residentStore.retain(assetId, candidate, value, directDrawEligible = true)
            }
        }
        assetIds.forEach { assertTrue(residentStore.hasEligibleDrawHandle(it)) }

        MediaGridMorphTestTrace.clear()
        var observationsBeforeUp = emptyList<MediaGridMorphDrawObservation>()
        scrollThenPinchOnGrid(
            gridTag = "classified_media_grid",
            centerSpan = 260f,
            endSpan = 180f,
            onBeforePhysicalUp = {
                observationsBeforeUp = MediaGridMorphTestTrace.drawEvents()
            },
        )

        val morphDraw = observationsBeforeUp.lastOrNull { it.drawMode == MediaGridSingleSurfaceMode.Morph }
            ?: error(
                "Morph draw was not observed before physical up: draws=$observationsBeforeUp " +
                    "claims=${MediaGridMorphTestTrace.claimEvents()} fallback=${MediaGridMorphTestTrace.fallbackCount()}",
            )
        assertEquals(MediaGridMorphPhase.Tracking, morphDraw.phase)
        assertEquals(MediaGridMorphDirection.IncreaseColumns, morphDraw.direction)
        assertEquals(MediaGridSingleSurfaceMode.Morph, morphDraw.drawMode)
        assertTrue(morphDraw.hasPlan)
        assertTrue(morphDraw.hasActiveRenderModel)
        assertTrue(morphDraw.protectedAssetCount > 0)
        assertTrue(morphDraw.progress > 0f)
        assertEquals(0, MediaGridMorphTestTrace.fallbackCount())
        assertEquals(4, mainViewModel().mediaGridSessionState.value.columnCount)

        waitForGridColumnCount(5)
        waitForMorphCanvasRemoval()
        composeRule.onNodeWithTag("filter_open").assertIsEnabled()
        composeRule.onNodeWithTag("sort_open").assertIsEnabled()
        composeRule.onNodeWithTag("classified_display_toggle").assertIsEnabled()
        val terminal = MediaGridMorphTestTrace.handoffEvents().lastOrNull()
            ?: error("Production handoff terminal observation was missing")
        assertEquals(MediaGridMorphGridHandoffPhase.Idle, terminal.phase)
        assertFalse(terminal.suppressesUserScroll)
        assertFalse(terminal.interactionLocked)
        MediaGridMorphTestTrace.clear()
        composeRule.onNodeWithTag("classified_media_grid").performTouchInput {
            swipeUp(
                startY = centerY + 150f,
                endY = centerY - 250f,
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()
        assertEquals(5, mainViewModel().mediaGridSessionState.value.columnCount)
        assertEquals(0, MediaGridMorphTestTrace.rollbackColumnCountCommandCount())
    }

    @Test
    fun productionMorphMissingTargetFallsBackUntilResidentPublishThenMorphs() {
        val now = Instant.now().toString()
        val paths = (0 until 12).map { index ->
            File(storage().imageDirectory(), "production-missing-target-$index.webp").apply {
                writeBytes(bitmapBytes(8, 8, if (index % 2 == 0) android.graphics.Color.MAGENTA else android.graphics.Color.CYAN))
            }.absolutePath
        }
        val clipAndAssetIds = runBlocking {
            storage().withDatabase { database ->
                val clipId = database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "production-missing-target",
                        authorName = "Production Missing Target Author",
                        authorUsername = "production_missing_target_author",
                        text = "Production missing target readiness",
                        postUrl = "https://x.com/production_missing_target_author/status/production-missing-target",
                        xCreatedAt = now,
                        savedAt = now,
                        syncedAt = now,
                    ),
                )
                val tagId = database.tagDao().insertTag(
                    TagEntity(name = "ProductionMissingTargetTag", createdAt = now, updatedAt = now),
                )
                database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
                database.clipDao().insertAssets(
                    paths.mapIndexed { index, path ->
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "production-missing-target-$index",
                            type = "photo",
                            remoteUrl = null,
                            previewUrl = null,
                            localPath = path,
                            width = 1200,
                            height = 1200,
                            sizeBytes = null,
                            downloadState = "downloaded",
                            createdAt = now,
                        )
                    },
                )
                clipId to database.clipDao().assetsForClipIds(listOf(clipId)).map { it.id }
            }
        }
        val clipId = clipAndAssetIds.first
        val assetIds = clipAndAssetIds.second
        val missingIndex = 2
        val missingAssetId = assetIds[missingIndex]
        val previewStore = MediaGridPersistentPreviewStore(composeRule.activity.filesDir)
        runBlocking {
            assetIds.zip(paths).forEach { (assetId, path) ->
                previewStore.generate(assetId, File(path)) { true }
            }
        }

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("classified_display_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("Production missing target readiness")
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.waitUntil(30_000) {
            assetIds.any { assetId ->
                composeRule.onAllNodesWithTag("media_grid_item_$assetId").fetchSemanticsNodes().isNotEmpty()
            } || composeRule.onAllNodesWithTag("clip_card_$clipId").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("classified_media_grid").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("classified_display_toggle").performClick()
        }
        composeRule.waitUntil(30_000) {
            assetIds.any { assetId ->
                composeRule.onAllNodesWithTag("media_grid_item_$assetId").fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.waitUntil(30_000) {
            val session = mainViewModel().mediaGridSessionState.value
            !session.showInitialProgress && session.frame?.ordinalIndex?.assetIdByMediaOrdinal?.size == assetIds.size
        }

        retainProductionMatrixAssets(assetIds, paths, previewStore)
        prepareProductionMatrixLocation(ProductionMorphMatrixLocation.Start, assetIds.size)

        val imageLoader = (composeRule.activity.application as LikeListManagerApp).container.mediaGridImageLoader
        val residentStore = mainViewModel().mediaGridSessionState.value.retainedImageStore
            ?: error("Production retained image store is unavailable")
        val missingPreview = previewStore.previewFile(missingAssetId)
        val missingSource = buildMediaGridImageCandidates(
            MediaGridImageCandidateInput(
                assetId = missingAssetId,
                mediaKey = "production-location-matrix-$missingIndex",
                localPath = paths[missingIndex],
                previewUrl = null,
                remoteUrl = null,
                displayUrl = paths[missingIndex],
                persistentPreview = MediaGridPersistentPreviewMetadata(
                    filePath = missingPreview.absolutePath,
                    length = missingPreview.length(),
                    lastModified = missingPreview.lastModified(),
                ),
            ),
        ).first()
        val missingCacheKey = mediaGridImageCacheKey(missingSource, 256, 256)
        missingPreview.delete()
        File(paths[missingIndex]).delete()
        imageLoader.memoryCache?.remove(
            coil.memory.MemoryCache.Key(missingCacheKey),
        )
        residentStore.invalidateAsset(missingAssetId)

        MediaGridMorphTestTrace.clear()
        waitForStableIdleReadinessFailure(
            expectedColumnCount = 4,
            expectedReasons = setOf(
                MediaGridMorphClaimReadinessReason.MissingSourceImage,
                MediaGridMorphClaimReadinessReason.MissingTargetImage,
            ),
        )
        var unavailableDraws = emptyList<MediaGridMorphDrawObservation>()
        pinchOnGrid(
            gridTag = "classified_media_grid",
            centerSpan = 260f,
            endSpan = 180f,
            centerYFraction = 0.16f,
            onBeforePhysicalUp = { unavailableDraws = MediaGridMorphTestTrace.drawEvents() },
        )
        val unavailableClaim = MediaGridMorphTestTrace.claimEvents().last { it.generation > 0L }
        assertFalse(unavailableClaim.accepted)
        assertTrue(
            unavailableClaim.readinessReason == MediaGridMorphClaimReadinessReason.MissingSourceImage ||
                unavailableClaim.readinessReason == MediaGridMorphClaimReadinessReason.MissingTargetImage,
        )
        val unavailableReport = unavailableClaim.readinessReport ?: error("Missing-image claim report was missing")
        assertTrue(unavailableReport.requiredTargetImageCount > unavailableReport.resolvedTargetImageCount)
        assertTrue(unavailableDraws.none { it.drawMode == MediaGridSingleSurfaceMode.Morph })
        assertTrue(MediaGridMorphTestTrace.fallbackCount() > 0)
        waitForGridColumnCount(5)
        waitForMorphCanvasRemoval()

        File(paths[missingIndex]).writeBytes(
            bitmapBytes(8, 8, android.graphics.Color.MAGENTA),
        )
        runBlocking { previewStore.generate(missingAssetId, File(paths[missingIndex])) { true } }
        retainProductionMatrixAssets(
            assetIds,
            paths,
            previewStore,
            retainedIndices = assetIds.indices.toSet(),
        )
        waitForStableIdleReadiness(5)
        MediaGridMorphTestTrace.clear()
        var readyDraws = emptyList<MediaGridMorphDrawObservation>()
        pinchOnGrid(
            gridTag = "classified_media_grid",
            centerSpan = 260f,
            endSpan = 180f,
            centerYFraction = 0.16f,
            onBeforePhysicalUp = { readyDraws = MediaGridMorphTestTrace.drawEvents() },
        )
        val readyClaim = MediaGridMorphTestTrace.claimEvents().last { it.generation > 0L }
        assertTrue(readyClaim.accepted)
        assertEquals(null, readyClaim.readinessReason)
        assertTrue(readyDraws.any { it.drawMode == MediaGridSingleSurfaceMode.Morph })
        assertEquals(0, MediaGridMorphTestTrace.fallbackCount())
        waitForGridColumnCount(6)
        waitForMorphCanvasRemoval()
        assertEquals(0, MediaGridMorphTestTrace.rollbackColumnCountCommandCount())
        val terminal = MediaGridMorphTestTrace.handoffEvents().lastOrNull()
            ?: error("Ready production handoff terminal observation was missing")
        assertEquals(MediaGridMorphGridHandoffPhase.Idle, terminal.phase)
        assertFalse(terminal.suppressesUserScroll)
        assertFalse(terminal.interactionLocked)
    }

    @Test
    fun productionMorphLocationMatrixIsStableIdleAndMorphsBeforePhysicalUp() {
        data class MatrixDataset(
            val assetIds: List<Long>,
            val paths: List<String>,
        )

        val now = Instant.now()
        val matrixImagePaths = (0 until 48).map { index ->
            File(
                storage().imageDirectory(),
                "production-location-matrix-${SystemClock.uptimeMillis()}-$index.webp",
            ).apply {
                writeBytes(bitmapBytes(8, 8, android.graphics.Color.rgb(
                    (index * 47) % 255,
                    (index * 83) % 255,
                    (index * 131) % 255,
                )))
            }.absolutePath
        }
        val dataset = runBlocking {
            storage().withDatabase { database ->
                val matrixTagId = database.tagDao().insertTag(
                    TagEntity(
                        name = "ProductionLocationMatrixTag",
                        createdAt = now.toString(),
                        updatedAt = now.toString(),
                    ),
                )
                val ids = (0 until 48).map { index ->
                    val createdAt = now.minus((index / 12).toLong(), ChronoUnit.DAYS).toString()
                    val clipId = database.clipDao().insertClip(
                        ClipEntity(
                            xPostId = "production-location-matrix-$index",
                            authorName = "Production Matrix Author $index",
                            authorUsername = "production_matrix_author_$index",
                            text = "Production location matrix",
                            postUrl = "https://x.com/production_matrix_author_$index/status/$index",
                            xCreatedAt = createdAt,
                            savedAt = createdAt,
                            syncedAt = createdAt,
                            likeCount = (index + 1L) * 1_200L,
                            likeCountFetchedAt = createdAt,
                        ),
                    )
                    database.clipDao().insertAssets(
                        listOf(
                            AssetEntity(
                                clipId = clipId,
                                mediaKey = "production-location-matrix-$index",
                                type = "photo",
                                remoteUrl = null,
                                previewUrl = null,
                                 localPath = matrixImagePaths[index],
                                width = 1200,
                                height = 1200,
                                sizeBytes = null,
                                downloadState = "downloaded",
                                createdAt = createdAt,
                            ),
                        ),
                    )
                    database.clipDao().insertClipTag(ClipTagEntity(clipId, matrixTagId, createdAt))
                    database.clipDao().assetsForClipIds(listOf(clipId)).single().id
                }
                MatrixDataset(ids, matrixImagePaths)
            }
        }
        val previewStore = MediaGridPersistentPreviewStore(composeRule.activity.filesDir)
        runBlocking {
            dataset.assetIds.forEachIndexed { index, assetId ->
                previewStore.generate(assetId, File(dataset.paths[index])) { true }
            }
        }

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("classified_display_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("Production location matrix")
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.waitUntil(30_000) {
            dataset.assetIds.any { assetId ->
                composeRule.onAllNodesWithTag("media_grid_item_$assetId").fetchSemanticsNodes().isNotEmpty()
            } || composeRule.onAllNodesWithTag("clip_list").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("classified_media_grid").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("classified_display_toggle").performClick()
        }
        composeRule.waitUntil(30_000) {
            dataset.assetIds.any { assetId ->
                composeRule.onAllNodesWithTag("media_grid_item_$assetId").fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.waitUntil(30_000) {
            val session = mainViewModel().mediaGridSessionState.value
            !session.showInitialProgress && session.frame?.ordinalIndex?.assetIdByMediaOrdinal?.size == dataset.assetIds.size
        }
        retainProductionMatrixAssets(dataset.assetIds, dataset.paths, previewStore)

        val cases = listOf(
            ProductionMorphMatrixCase(4, 5, ClassifiedSortBase.Default, ProductionMorphMatrixLocation.Start, 0.50f),
            ProductionMorphMatrixCase(5, 4, ClassifiedSortBase.PostTime, ProductionMorphMatrixLocation.HeaderBefore, 0.30f),
            ProductionMorphMatrixCase(4, 5, ClassifiedSortBase.Default, ProductionMorphMatrixLocation.FourRowsDown, 0.50f),
        )

        cases.forEachIndexed { caseIndex, matrixCase ->
            applyProductionMatrixSort(matrixCase.sortBase)
            if (caseIndex == 0) ensureProductionMatrixColumns(matrixCase.fromColumns)
            MediaGridMorphTestTrace.clear()
            prepareProductionMatrixLocation(matrixCase.location, dataset.assetIds.size)
            waitForStableIdleReadiness(matrixCase.fromColumns)
            MediaGridMorphTestTrace.clear()
            var observationsBeforeUp = emptyList<MediaGridMorphDrawObservation>()
            pinchOnGrid(
                    gridTag = "classified_media_grid",
                    centerSpan = if (matrixCase.toColumns > matrixCase.fromColumns) 260f else 180f,
                    endSpan = if (matrixCase.toColumns > matrixCase.fromColumns) 180f else 260f,
                    centerYFraction = matrixCase.centerYFraction,
                    onBeforePhysicalUp = {
                        observationsBeforeUp = MediaGridMorphTestTrace.drawEvents()
                    },
            )
                val claim = MediaGridMorphTestTrace.claimEvents().lastOrNull { it.generation > 0L }
                    ?: error("No production claim observed for $matrixCase")
                assertTrue("claim was not accepted for $matrixCase: $claim", claim.accepted)
                assertTrue(claim.bundlePresent)
                assertTrue(claim.directionPrepared)
                assertEquals(null, claim.readinessReason)
                val report = claim.readinessReport ?: error("Claim report was missing for $matrixCase")
                assertEquals(claim.generation, report.generation)
                assertEquals(report.requiredSourceImageCount, report.resolvedSourceImageCount)
                assertEquals(report.requiredTargetImageCount, report.resolvedTargetImageCount)
                assertTrue(report.sourceViewportComplete)
                assertTrue(report.geometryComplete)
                assertTrue(report.requiredCellCount > 0)
                assertTrue(report.optionalOffscreenCellCount >= 0)
                assertNotNull(report.exactTargetRowId)
                assertNotNull(report.exactTargetRowFirstItemIndex)
                val morphDraws = observationsBeforeUp.filter {
                    it.drawMode == MediaGridSingleSurfaceMode.Morph && it.generation == claim.generation
                }
                assertTrue("FirstMorphDraw missing for $matrixCase: $observationsBeforeUp", morphDraws.isNotEmpty())
                assertTrue(morphDraws.map { it.frameNumber }.distinct().size >= 2)
                assertTrue(morphDraws.map { it.progress }.distinct().size >= 2)
                assertEquals(0, MediaGridMorphTestTrace.fallbackCount())
                waitForGridColumnCount(matrixCase.toColumns)
                waitForMorphCanvasRemoval()
                assertEquals(
                    "Unexpected rollback for $matrixCase: ${MediaGridMorphTestTrace.rollbackReasons()}",
                    0,
                    MediaGridMorphTestTrace.rollbackColumnCountCommandCount(),
                )
                val terminal = MediaGridMorphTestTrace.handoffEvents()
                    .lastOrNull { it.generation == claim.generation }
                    ?: error("Handoff terminal observation was missing for $matrixCase")
                assertEquals(MediaGridMorphGridHandoffPhase.Idle, terminal.phase)
                assertFalse(terminal.suppressesUserScroll)
                assertFalse(terminal.interactionLocked)
                assertTrue(
                    "Direct ScrollToItem layout re-evaluation missing for $matrixCase",
                    MediaGridMorphTestTrace.handoffCommandEvents().any {
                        it.generation == claim.generation &&
                            it.command == "ScrollToItem" &&
                            it.directLayoutReevaluated
                    },
                )
        }
    }

    @Ignore("TEST_HARNESS legacy smoke duplicates the production same-surface claim and is not part of the production sequence.")
    @Test
    fun testHarnessMediaGridUsesSameSurfaceRendererWithoutLegacyMorphCanvas() {
        val now = Instant.now().toString()
        val colors = listOf(android.graphics.Color.MAGENTA, android.graphics.Color.CYAN)
        val paths = colors.mapIndexed { index, color ->
            File(storage().imageDirectory(), "production-morph-live-$index.webp").apply {
                writeBytes(bitmapBytes(8, 8, color))
            }.absolutePath
        }
        val clipAndAssetIds = runBlocking {
            storage().withDatabase { database ->
                val clipId = database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "production-morph-live",
                        authorName = "Morph Author",
                        authorUsername = "morph_author",
                        text = "Production morph live pinch",
                        postUrl = "https://x.com/morph_author/status/production-morph-live",
                        xCreatedAt = now,
                        savedAt = now,
                        syncedAt = now,
                    ),
                )
                val tagId = database.tagDao().insertTag(
                    TagEntity(
                        name = "ProductionMorphLiveTag",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
                database.clipDao().insertAssets(
                    paths.mapIndexed { index, path ->
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "production-morph-live-$index",
                            type = "photo",
                            remoteUrl = null,
                            previewUrl = null,
                            localPath = path,
                            width = 1200,
                            height = 1200,
                            sizeBytes = null,
                            downloadState = "downloaded",
                            createdAt = now,
                        )
                    },
                )
                clipId to database.clipDao().assetsForClipIds(listOf(clipId)).map { it.id }
            }
        }
        val clipId = clipAndAssetIds.first
        val assetIds = clipAndAssetIds.second
        val previewStore = MediaGridPersistentPreviewStore(composeRule.activity.filesDir)
        runBlocking {
            assetIds.zip(paths).forEach { (assetId, path) ->
                previewStore.generate(assetId, File(path)) { true }
            }
        }

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("classified_display_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("Production morph live pinch")
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("media_grid_item_${assetIds.first()}").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("clip_card_$clipId").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("media_grid_item_${assetIds.first()}").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("classified_display_toggle").performClick()
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("media_grid_item_${assetIds.first()}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(30_000) {
            !mainViewModel().mediaGridSessionState.value.showInitialProgress
        }
        val mainViewModel = mainViewModel()
        val residentStore = mainViewModel.mediaGridSessionState.value.retainedImageStore
            ?: error("Main activity did not expose its production retained image store")
        val imageLoader = (composeRule.activity.application as LikeListManagerApp).container.mediaGridImageLoader
        val preparedCandidates = assetIds.zip(paths).map { (assetId, path) ->
            val previewFile = previewStore.previewFile(assetId)
            val source = buildMediaGridImageCandidates(
                MediaGridImageCandidateInput(
                    assetId = assetId,
                    mediaKey = "production-morph-live-${paths.indexOf(path)}",
                    localPath = path,
                    previewUrl = null,
                    remoteUrl = null,
                    displayUrl = path,
                    persistentPreview = MediaGridPersistentPreviewMetadata(
                        filePath = previewFile.absolutePath,
                        length = previewFile.length(),
                        lastModified = previewFile.lastModified(),
                    ),
                ),
            ).first()
            MediaGridPreparedCandidate(
                kind = source.kind,
                requestData = File(source.data as String),
                sourceIdentity = source.sourceIdentity,
                cacheKey = mediaGridImageCacheKey(source, 256, 256),
                width = 256,
                height = 256,
                useDiskCache = false,
            )
        }
        runBlocking {
            preparedCandidates.forEachIndexed { index, candidate ->
                imageLoader.execute(buildMediaGridImageRequest(composeRule.activity, candidate))
                val value = imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(candidate.cacheKey))
                    ?: error("Asset ${assetIds[index]} was not present in the image cache")
                residentStore.retain(assetIds[index], candidate, value, directDrawEligible = true)
            }
        }
        assetIds.forEach { assetId ->
            assertTrue(residentStore.hasEligibleDrawHandle(assetId))
        }

        var canvasSeenDuringGesture = false
        pinchOnGrid(
            gridTag = "classified_media_grid",
            centerSpan = 260f,
            endSpan = 180f,
            onMidGesture = {
                canvasSeenDuringGesture =
                    composeRule.onAllNodesWithTag("media_grid_morph_canvas", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
            },
        )
        assertFalse(canvasSeenDuringGesture)
        assertTrue(
            composeRule.onAllNodesWithTag("media_grid_morph_same_surface", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun classifiedMediaGridSingleFlingKeepsLatestImageAndImmediateCellActionAfterFilter() {
        val now = Instant.now().toString()
        val imagePath = File(storage().imageDirectory(), "viewport-fling.webp").apply {
            writeBytes(bitmapBytes(8, 8, android.graphics.Color.MAGENTA))
        }.absolutePath
        val assetIds = runBlocking {
            storage().withDatabase { database ->
                val tagId = database.tagDao().insertTag(TagEntity(name = "ViewportFlingTag", createdAt = now, updatedAt = now))
                buildList {
                    (1L..96L).forEach { index ->
                        val clipId = database.clipDao().insertClip(
                            ClipEntity(
                                xPostId = "viewport-fling-$index",
                                authorName = "Viewport Author",
                                authorUsername = "viewport_author",
                                text = if (index == 96L) "ViewportFilterTarget" else "Viewport fling item $index",
                                postUrl = "https://x.com/viewport_author/status/$index",
                                xCreatedAt = now,
                                savedAt = now,
                                syncedAt = now,
                            ),
                        )
                        val assetId = 10_000L + index
                        database.clipDao().insertAssets(
                            listOf(
                                AssetEntity(
                                    id = assetId,
                                    clipId = clipId,
                                    mediaKey = "viewport-fling-$index",
                                    type = "photo",
                                    remoteUrl = null,
                                    previewUrl = null,
                                    localPath = imagePath,
                                    width = 800,
                                    height = 800,
                                    downloadState = "downloaded",
                                    createdAt = now,
                                ),
                            ),
                        )
                        database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
                        add(assetId)
                    }
                }
            }
        }

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("classified_display_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("classified_media_grid").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("classified_display_toggle").performClick()
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("media_grid_item_${assetIds.first()}").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("classified_media_grid").performScrollToIndex(assetIds.size / 2)
        composeRule.waitForIdle()
        slowDragDownOnScreen()
        fastFlingThenRetouchOnScreen()
        composeRule.waitForIdle()

        val visibleAfterFling = visibleGridAssetIds(assetIds)
        assertTrue("fling should advance to a later media range", visibleAfterFling.maxOrNull() ?: 0L > assetIds[assetIds.size / 2])
        val selectedAfterFling = visibleAfterFling.maxOrNull() ?: error("No visible media cell after fling")
        composeRule.onNodeWithTag("media_grid_item_$selectedAfterFling", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("media_grid_tweet_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_tweet_dialog_close").performClick()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_query").performTextReplacement("ViewportFilterTarget")
        composeRule.onNodeWithTag("filter_apply").performClick()
        val targetAssetId = assetIds.last()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("media_grid_item_$targetAssetId").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(targetAssetId), visibleGridAssetIds(assetIds))
        composeRule.onNodeWithTag("media_grid_item_$targetAssetId", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("media_grid_tweet_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("media_grid_tweet_dialog_close").performClick()
    }

    @Test
    fun classifiedMediaGridReflectsFilteringSortingAndEmptyStates() {
        val now = Instant.now().toString()
        data class GridFixture(
            val lowAssetId: Long,
            val highAssetId: Long,
            val lowClipId: Long,
            val highClipId: Long,
            val noMediaClipId: Long,
        )
        val fixture = runBlocking {
            storage().withDatabase { database ->
                database.clearAllTables()
                val tagId = database.tagDao().insertTag(
                    TagEntity(
                        name = "GridMatch",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                val lowClipId = database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "grid-low",
                        authorName = "Low",
                        authorUsername = "low",
                        text = "Grid Alpha",
                        postUrl = "https://x.com/low/status/grid-low",
                        xCreatedAt = now,
                        savedAt = "2026-06-15T09:00:00Z",
                        syncedAt = now,
                        likeCount = 5,
                    ),
                )
                val highClipId = database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "grid-high",
                        authorName = "High",
                        authorUsername = "high",
                        text = "Grid Beta",
                        postUrl = "https://x.com/high/status/grid-high",
                        xCreatedAt = now,
                        savedAt = "2026-06-15T10:00:00Z",
                        syncedAt = now,
                        likeCount = 10,
                    ),
                )
                val noMediaClipId = database.clipDao().insertClip(
                    ClipEntity(
                        xPostId = "grid-no-media",
                        authorName = "Plain",
                        authorUsername = "plain",
                        text = "NoMedia Match",
                        postUrl = "https://x.com/plain/status/grid-no-media",
                        xCreatedAt = now,
                        savedAt = "2026-06-15T11:00:00Z",
                        syncedAt = now,
                        likeCount = 1,
                    ),
                )
                listOf(lowClipId, highClipId, noMediaClipId).forEach { clipId ->
                    database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
                }
                database.clipDao().insertAssets(
                    listOf(
                        AssetEntity(
                            clipId = lowClipId,
                            mediaKey = "grid-low-photo",
                            type = "photo",
                            remoteUrl = "https://example.test/grid-low-photo.jpg",
                            previewUrl = null,
                            localPath = null,
                            width = 1200,
                            height = 1200,
                            sizeBytes = null,
                            downloadState = "downloaded",
                            createdAt = now,
                        ),
                        AssetEntity(
                            clipId = highClipId,
                            mediaKey = "grid-high-photo",
                            type = "photo",
                            remoteUrl = "https://example.test/grid-high-photo.jpg",
                            previewUrl = null,
                            localPath = null,
                            width = 1200,
                            height = 1200,
                            sizeBytes = null,
                            downloadState = "downloaded",
                            createdAt = now,
                        ),
                    ),
                )
                val lowAssetId = database.clipDao().assetsForClipIds(listOf(lowClipId)).single().id
                val highAssetId = database.clipDao().assetsForClipIds(listOf(highClipId)).single().id
                GridFixture(lowAssetId, highAssetId, lowClipId, highClipId, noMediaClipId)
            }
        }
        val lowAssetId = fixture.lowAssetId
        val highAssetId = fixture.highAssetId
        val noMediaClipId = fixture.noMediaClipId

        // Recreate after replacing the full fixture so both UI flows observe the same database snapshot.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("main_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("clip_card_${fixture.highClipId}").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("classified_media_grid").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("classified_media_grid").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("classified_display_toggle").performClick()
        }
        composeRule.onNodeWithTag("media_grid_item_$highAssetId", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("filter_dialog", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("filter_query").performTextReplacement("NoMedia")
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.onNodeWithText("この条件に一致する画像・動画サムネイルはありません").assertIsDisplayed()

        composeRule.onNodeWithTag("filter_open").performClick()
        composeRule.onNodeWithTag("filter_clear_all_open").performClick()
        composeRule.onNodeWithTag("filter_clear_all_confirm").performClick()
        composeRule.onNodeWithTag("filter_apply").performClick()
        composeRule.waitUntil(30_000) {
            runCatching {
                composeRule.onNodeWithTag("media_grid_item_$highAssetId", useUnmergedTree = true).assertIsDisplayed()
                composeRule.onNodeWithTag("media_grid_item_$lowAssetId", useUnmergedTree = true).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("sort_open").performClick()
        composeRule.onNodeWithTag("sort_base_like").performClick()
        composeRule.onNodeWithTag("sort_like_direction_high").performClick()
        composeRule.onNodeWithTag("sort_apply").performClick()
        composeRule.waitUntil(30_000) {
            runCatching {
                composeRule.onNodeWithTag("media_grid_item_$highAssetId", useUnmergedTree = true).assertIsDisplayed()
                composeRule.onNodeWithTag("media_grid_item_$lowAssetId", useUnmergedTree = true).assertIsDisplayed()
            }.isSuccess
        }

        val highBounds = composeRule.onNodeWithTag("media_grid_item_$highAssetId", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val lowBounds = composeRule.onNodeWithTag("media_grid_item_$lowAssetId", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(highBounds.left < lowBounds.left)

        composeRule.onNodeWithTag("classified_display_toggle").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("clip_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("clip_list").performScrollToNode(hasTestTag("clip_card_${fixture.highClipId}"))
        composeRule.onNodeWithTag("clip_card_${fixture.highClipId}").assertIsDisplayed()
    }

    @Test
    fun classifiedSortDialogOpensClearsAndAppliesSelection() {
        waitForSeededClip()

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("sort_open").performClick()
        composeRule.onNodeWithTag("sort_dialog").assertExists()

        composeRule.onNodeWithTag("sort_base_like").performClick()
        composeRule.onNodeWithTag("sort_like_direction_high").performClick()

        composeRule.onNodeWithTag("sort_clear_all_open").performClick()

        composeRule.onNodeWithTag("sort_cancel").performClick()
        composeRule.onAllNodesWithTag("sort_dialog").assertCountEquals(0)

        composeRule.onNodeWithTag("sort_open").performClick()
        composeRule.onNodeWithTag("sort_base_like").performClick()
        composeRule.onNodeWithTag("sort_like_direction_high").performClick()
        composeRule.onNodeWithTag("sort_apply").performClick()
        composeRule.onNodeWithText("並び:", substring = true).assertExists()
    }

    @Test
    fun usageAndSettingsSafetyControlsReflectTheIsolatedEnvironment() {
        openSettingsScreen()
        assertSettingsSectionVisible("settings_usage_section")
        composeRule.onNodeWithText("今月のAPI使用量:", substring = true).assertExists()
        composeRule.onNodeWithText("15分rate limit:", substring = true).assertExists()
        assertTrue(composeRule.onAllNodesWithText("警告ライン").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("停止ライン").fetchSemanticsNodes().isEmpty())
        assertSettingsSectionVisible("settings_x_api_section")
        composeRule.onNodeWithTag("settings_login_logout").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings_client_id_input").performTextInput("test-client-id")
        composeRule.onNodeWithTag("settings_login_logout").assertIsNotEnabled()
    }

    @Test
    fun settingsScreenOmitsHiddenLabelsAndShowsDataManagementSummary() {
        openSettingsScreen()

        assertTrue(composeRule.onAllNodesWithText("Callback URI", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Scope", substring = true).fetchSemanticsNodes().isEmpty())

        assertSettingsSectionVisible("settings_data_management_section")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("保存件数:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("settings_storage_usage_progress").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("保存件数:", substring = true).assertExists()
        assertSettingsTextVisible("保存先の使用状況")
        composeRule.onNodeWithText("画像枚数:", substring = true).assertExists()
        assertTrue(composeRule.onAllNodesWithText("保存場所候補", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("現在の保存場所", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("保存先候補", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("移動先:", substring = true).fetchSemanticsNodes().isEmpty())
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
            composeRule.onAllNodesWithTag("settings_like_refresh_confirm").fetchSemanticsNodes().isNotEmpty()
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

        composeRule.onNodeWithTag("clip_list").performScrollToNode(hasTestTag("clip_card_$clipId"))
        composeRule.onNodeWithTag("clip_open_x_$clipId").assertIsDisplayed()
        assertNoEditableSummaryInput(clipId)
        composeRule.onNodeWithTag("clip_like_count_$clipId", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("いいね数: 12,345", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("取得日時:", substring = true).assertIsDisplayed()
        composeRule.waitForIdle()
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
            .assertExists()
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
        composeRule.onNodeWithTag("clip_open_x_$clipId").assertIsDisplayed()
        assertNoEditableSummaryInput(clipId)
        composeRule.onNode(
            hasTestTag("tag_chip_$tagId") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("classify_$clipId").performClick()
        waitUntil { clipTagIds(clipId) == setOf(tagId) }
        composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText("未分類 2件", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("2件", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("tab_classified").performClick()
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("tag_chip_$tagId") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        waitUntil { clipTagIds(clipId).isEmpty() }

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithText("未分類 3件", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("3件", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
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
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("一致件数:24件").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("文字列:\"ScrollFilterNeedle\"", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("clip_list").performScrollToIndex(10)
        composeRule.onNodeWithTag("scroll_to_top").performClick()
        composeRule.onNodeWithTag("clip_list").performScrollToNode(hasTestTag("clip_card_${clipIds.last()}"))
        composeRule.onNodeWithText("文字列:\"ScrollFilterNeedle\"", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("clip_card_${clipIds.last()}").assertIsDisplayed()

        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun pendingTagSelectionSurvivesListScrollUntilClassification() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val tagId = createRootTag("Scroll分類タグ")

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
        composeRule.onNodeWithTag("clip_open_x_$clipId").assertIsDisplayed()
        assertNoEditableSummaryInput(clipId)
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
        val firstGroup = createRootGroup("同名のグループA")
        val secondGroup = createRootGroup("同名のグループB")
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
    fun localDeleteDialogHardDeletesClipAssetsAndFiles() {
        val clipId = waitForSeededClip()
        val totalBefore = totalClipCount()
        val imagePath = runBlocking {
            storage().withDatabase { database ->
                val imageDir = storage().imageDirectory()
                imageDir.mkdirs()
                val file = imageDir.resolve("compose-delete.webp").apply {
                    writeBytes(bitmapBytes(2, 1, android.graphics.Color.MAGENTA))
                }
                database.clipDao().insertAssets(
                    listOf(
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "compose-delete-photo",
                            type = "photo",
                            remoteUrl = "https://example.test/delete",
                            previewUrl = null,
                            localPath = file.absolutePath,
                            width = 2,
                            height = 1,
                            sizeBytes = file.length(),
                            downloadState = "downloaded",
                            createdAt = Instant.now().toString(),
                        ),
                    ),
                )
                file.absolutePath
            }
        }

        composeRule.onNodeWithTag("clip_list").performScrollToNode(hasTestTag("clip_card_$clipId"))
        composeRule.onNodeWithTag("clip_open_x_$clipId").assertIsDisplayed()
        assertNoEditableSummaryInput(clipId)
        composeRule.onNodeWithTag("tweet_options_button_$clipId", useUnmergedTree = true).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("tweet_options_local_delete").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tweet_options_local_delete").performClick()
        composeRule.onNodeWithTag("clip_local_delete_dialog_$clipId").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_local_delete_cancel_$clipId").performClick()
        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        assertTrue(activeClipIds().contains(clipId))

        composeRule.onNodeWithTag("clip_list").performScrollToNode(hasTestTag("clip_card_$clipId"))
        composeRule.onNodeWithTag("clip_open_x_$clipId").assertIsDisplayed()
        assertNoEditableSummaryInput(clipId)
        composeRule.onNodeWithTag("tweet_options_button_$clipId", useUnmergedTree = true).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("tweet_options_local_delete").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tweet_options_local_delete").performClick()
        composeRule.onNodeWithTag("clip_local_delete_dialog_$clipId").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_local_delete_confirm_$clipId").performClick()
        waitUntil { !activeClipIds().contains(clipId) }
        assertEquals(totalBefore - 1, totalClipCount())
        assertTrue(composeRule.onAllNodesWithTag("clip_card_$clipId").fetchSemanticsNodes().isEmpty())
        assertTrue(ocrTextForClip(clipId).isEmpty())
        assertTrue(runBlocking { storage().withDatabase { database -> database.clipDao().assetsForClipIds(listOf(clipId)).isEmpty() } })
        assertTrue(runBlocking { storage().withDatabase { database -> database.clipDao().clipTagsForClipIds(listOf(clipId)).isEmpty() } })
        assertFalse(java.io.File(imagePath).exists())
    }

    @Test
    fun summaryEditPersistsToDatabaseAndSurvivesActivityRecreation() {
        val clipId = waitForSeededClip()
        val summary = "E2E_summary_edit"
        composeRule.onNodeWithTag("tweet_options_button_$clipId", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("tweet_options_summary").performClick()
        composeRule.onNodeWithTag("summary_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("summary_text").performTextReplacement(summary)
        composeRule.onNodeWithTag("summary_save").performClick()
        waitUntil { summaryForClip(clipId) == summary }

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("clip_card_$clipId").assertIsDisplayed()
        composeRule.onNodeWithText(summary, substring = true).assertIsDisplayed()
        assertNoEditableSummaryInput(clipId)
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
        composeRule.onNodeWithTag("image_viewer").performTouchInput { swipeLeft() }
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
        composeRule.onNodeWithTag("tag_rename_open_group_$secondGroup").performClick()
        composeRule.onNodeWithTag("rename_node_name").performTextReplacement("E2E変更後グループ")
        composeRule.onNodeWithTag("rename_node_save").performClick()
        waitUntil { groupsNamed("E2E変更後グループ") == 1 && groupsNamed("E2EグループB") == 0 }
        assertTrue(groupExists(secondGroup))

        val firstTag = createChildTag(firstGroup, "共有タグ")
        createChildTag(secondGroup, "共有タグ")
        assertEquals(2, tagsNamed("共有タグ"))

        composeRule.onNodeWithTag("tag_expand_group_$firstGroup").performClick()
        composeRule.onNodeWithTag("tag_rename_open_tag_$firstTag").performClick()
        composeRule.onNodeWithTag("rename_node_name").performTextReplacement("変更後タグ")
        composeRule.onNodeWithTag("rename_node_save").performClick()
        waitUntil { tagsNamed("変更後タグ") == 1 }

        composeRule.onNodeWithTag("tag_delete_open_tag_$firstTag").performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitUntil { !tagExists(firstTag) }

        composeRule.onNodeWithTag("tag_delete_open_group_$firstGroup").performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitUntil { !groupExists(firstGroup) }
        assertTrue(groupExists(secondGroup))
    }

    @Test
    fun tagManagementGroupAddUsesDropdownMenuAndCreatesChildrenUnderThePressedGroup() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val parentGroup = createRootGroup("E2E_dropdown_parent")

        composeRule.onNodeWithTag("tag_add_open_$parentGroup").performClick()
        composeRule.onNodeWithTag("tag_add_create_group_$parentGroup").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("create_node_name").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_node_name").performTextInput("E2E_dropdown_child_group")
        composeRule.onNodeWithTag("create_node_confirm").performClick()

        val childGroupId = groupIdByName("E2E_dropdown_child_group")
        waitUntil { groupParentGroupId(childGroupId) == parentGroup }
        assertEquals(parentGroup, groupParentGroupId(childGroupId))

        composeRule.onNodeWithTag("tag_add_open_$parentGroup").performClick()
        composeRule.onNodeWithTag("tag_add_create_tag_$parentGroup").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("create_node_name").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create_node_name").performTextInput("E2E_dropdown_child_tag")
        composeRule.onNodeWithTag("create_node_confirm").performClick()

        val childTagId = tagIdByName("E2E_dropdown_child_tag")
        waitUntil { tagParentGroupId(childTagId) == parentGroup }
        assertEquals(parentGroup, tagParentGroupId(childTagId))
    }

    @Test
    fun tagColorPickerPersistsSelectionAcrossCreateAndRenameFlows() {
        composeRule.onNodeWithTag("tab_tags").performClick()

        composeRule.onNodeWithTag("create_root_tag").performClick()
        composeRule.onNodeWithTag("create_node_name").assertIsDisplayed()
        assertPaletteRow("color_palette_row_0", listOf("color_palette_standard", "color_palette_red", "color_palette_orange", "color_palette_yellow", "color_palette_green", "color_palette_cyan"))
        assertPaletteRow("color_palette_row_1", listOf("color_palette_blue", "color_palette_purple", "color_palette_pink", "color_palette_white", "color_palette_brown", "color_palette_skin"))
        composeRule.onNodeWithTag("color_palette_red").performClick()
        composeRule.onNodeWithTag("create_node_name").performTextInput("E2E色タグ")
        composeRule.onNodeWithTag("create_node_confirm").performClick()

        val tagId = tagIdByName("E2E色タグ")
        waitUntil { tagColorId(tagId) == TagColorId.RED.id }

        composeRule.onNodeWithTag("tag_rename_open_tag_$tagId").performClick()
        composeRule.onNodeWithTag("rename_node_name").assertIsDisplayed()
        assertPaletteRow("color_palette_row_0", listOf("color_palette_standard", "color_palette_red", "color_palette_orange", "color_palette_yellow", "color_palette_green", "color_palette_cyan"))
        assertPaletteRow("color_palette_row_1", listOf("color_palette_blue", "color_palette_purple", "color_palette_pink", "color_palette_white", "color_palette_brown", "color_palette_skin"))
        composeRule.onNodeWithTag("color_palette_orange").performClick()
        composeRule.onNodeWithTag("rename_node_save").performClick()

        waitUntil { tagColorId(tagId) == TagColorId.ORANGE.id }
        composeRule.onNodeWithTag("tag_row_tag_$tagId").assertIsDisplayed()
    }

    @Test
    fun mainClipCardsExposeTheXLogoOpenButton() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("clip_open_x_$clipId").assertIsDisplayed()
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
        val groupId = createRootGroup("名前キャンセルグループ")
        val tagId = createChildTag(groupId, "名前キャンセルタグ")
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tag_rename_open_group_$groupId").performClick()
        composeRule.onNodeWithTag("rename_node_name").performTextReplacement("変更されないグループ")
        composeRule.onNodeWithTag("rename_node_cancel").performClick()

        composeRule.onNodeWithTag("tag_expand_group_$groupId").performClick()
        composeRule.onNodeWithTag("tag_rename_open_tag_$tagId").performClick()
        composeRule.onNodeWithTag("rename_node_name").performTextReplacement("変更されないタグ")
        composeRule.onNodeWithTag("rename_node_cancel").performClick()

        assertEquals(1, groupsNamed("名前キャンセルグループ"))
        assertEquals(0, groupsNamed("変更されないグループ"))
        assertEquals(1, tagsNamed("名前キャンセルタグ"))
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

        composeRule.onNodeWithTag("tag_delete_open_group_$groupId").performClick()
        composeRule.onNodeWithTag("tag_delete_cancel_group_$groupId").performClick()

        composeRule.onNodeWithTag("tag_row_group_$groupId").assertIsDisplayed()
        assertTrue(groupExists(groupId))
        assertEquals(1, groupsNamed("削除キャンセルグループ"))
        assertEquals(before, databaseFingerprint())
    }

    @Ignore("Current tag management UI does not expose a move dialog.")
    @Test
    fun tagManagementMovesTagToAnotherGroupThroughDialog() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceGroup = createRootGroup("E2E移動先グループA")
        val targetGroup = createRootGroup("E2E移動先グループB")
        val movedTag = createChildTag(sourceGroup, "E2E移動タグ")
        waitUntil { tagParentGroupId(movedTag) == sourceGroup }

        composeRule.onNodeWithTag("tag_expand_group_$sourceGroup").performClick()
        composeRule.onNodeWithTag("tag_operation_tag_$movedTag").performClick()
        composeRule.onNodeWithTag("tag_move_open_tag_$movedTag").performClick()
        composeRule.onNodeWithTag("move_node_target_group_$targetGroup").performClick()

        waitUntil { tagParentGroupId(movedTag) == targetGroup }
        assertEquals(targetGroup, tagParentGroupId(movedTag))
    }

    @Ignore("Current tag management UI does not expose a move dialog.")
    @Test
    fun tagMoveDialogCancelKeepsParentGroup() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceGroup = createRootGroup("移動キャンセルグループ")
        createRootGroup("移動キャンセルグループ")
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

    @Ignore("Current tag management UI does not expose a move dialog.")
    @Test
    fun groupMoveDialogCancelKeepsParentGroup() {
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceGroup = createRootGroup("グループ移動キャンセルグループ")
        createRootGroup("グループ移動キャンセルグループ")
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
        val sourceTag = createRootTag("一括追加対象タグ")
        val targetTag = createRootTag("一括追加先タグ")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNode(
            hasTestTag("tag_chip_$sourceTag") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("classify_$clipId").performClick()
        waitUntil { clipTagIds(clipId) == setOf(sourceTag) }

        composeRule.onNodeWithTag("tab_tags").performClick()
        composeRule.onNodeWithTag("tag_add_all_open_$sourceTag").performClick()
        composeRule.onNodeWithText("「一括追加対象タグ」の全ツイートに追加するタグを選びます。元のタグは残ります。").assertIsDisplayed()
        composeRule.onNodeWithTag("add_all_target_tag_$targetTag").performClick()

        waitUntil { clipTagIds(clipId) == setOf(sourceTag, targetTag) }
        assertEquals(setOf(sourceTag, targetTag), clipTagIds(clipId))
    }

    @Test
    fun addAllTagsDialogCancelKeepsRelations() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val sourceTag = createRootTag("一括追加キャンセル対象タグ")
        createRootTag("一括追加キャンセル対象タグ")

        composeRule.onNodeWithTag("tab_unclassified").performClick()
        composeRule.onNode(
            hasTestTag("tag_chip_$sourceTag") and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).performClick()
        composeRule.onNodeWithTag("classify_$clipId").performClick()
        waitUntil { clipTagIds(clipId) == setOf(sourceTag) }
        val before = databaseFingerprint()

        composeRule.onNodeWithTag("tab_tags").performClick()
        composeRule.onNodeWithTag("tag_add_all_open_$sourceTag").performClick()
        composeRule.onNodeWithTag("add_all_cancel").performClick()

        assertEquals(setOf(sourceTag), clipTagIds(clipId))
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun dismissingTagPopupOutsideDoesNotModifyOrPropagateToTheClip() {
        val clipId = waitForSeededClip()
        composeRule.onNodeWithTag("tab_tags").performClick()
        val groupId = createRootGroup("ポップアップ確認グループ")
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
        val id = runBlocking { storage().withDatabase { it.tagDao().getTags().single { tag -> tag.name == name }.id } }
        scrollTagsTo("tag_row_tag_$id")
        return id
    }

    private fun createRootGroup(name: String): Long {
        composeRule.onNodeWithTag("create_root_group").performClick()
        composeRule.onNodeWithTag("create_node_name").performTextInput(name)
        composeRule.onNodeWithTag("create_node_confirm").performClick()
        waitUntil { groupsNamed(name) == 1 }
        val id = runBlocking { storage().withDatabase { it.tagDao().getGroups().single { group -> group.name == name }.id } }
        scrollTagsTo("tag_row_group_$id")
        return id
    }

    private fun createChildTag(groupId: Long, name: String): Long {
        val now = Instant.now().toString()
        val insertedId = runBlocking {
            storage().withDatabase { database ->
                database.tagDao().insertTag(
                    TagEntity(
                        name = name,
                        parentGroupId = groupId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
        waitUntil { tagParentGroupId(insertedId) == groupId }
        val persistedId = runBlocking {
            storage().withDatabase { database ->
                database.tagDao().getTags().single { it.name == name && it.parentGroupId == groupId }.id
            }
        }
        return persistedId
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

    private fun assertSettingsTextVisible(text: String) {
        composeRule.onNodeWithTag("settings_content").performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun scrollTagsTo(testTag: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("tag_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("tag_list").performScrollToNode(hasTestTag(testTag))
    }

    private fun assertPaletteRow(rowTag: String, expectedColorTags: List<String>) {
        expectedColorTags.forEach { colorTag ->
            composeRule.onAllNodes(hasTestTag(colorTag) and hasAnyAncestor(hasTestTag(rowTag)))
                .assertCountEquals(1)
        }
        val rowNodes = expectedColorTags.map { colorTag ->
            composeRule.onNode(hasTestTag(colorTag) and hasAnyAncestor(hasTestTag(rowTag))).fetchSemanticsNode()
        }
        assertEquals(
            1,
            rowNodes.map { it.boundsInRoot.top.toInt() }.distinct().size,
        )
    }

    @Test
    fun ocrDialogSavesRecognizedTextAndCanBeCanceledWithoutWriting() {
        val clipId = waitForSeededClip()
        val photoPath = runBlocking {
            storage().withDatabase { database ->
                val imageDir = storage().imageDirectory()
                imageDir.mkdirs()
                val file = imageDir.resolve("compose-ocr.webp").apply {
                    writeBytes(bitmapBytes(2, 1, android.graphics.Color.CYAN))
                }
                database.clipDao().insertAssets(
                    listOf(
                        AssetEntity(
                            clipId = clipId,
                            mediaKey = "compose-ocr-photo",
                            type = "photo",
                            remoteUrl = "https://example.test/ocr",
                            previewUrl = null,
                            localPath = file.absolutePath,
                            width = 2,
                            height = 1,
                            sizeBytes = file.length(),
                            downloadState = "downloaded",
                            createdAt = Instant.now().toString(),
                        ),
                    ),
                )
                file.absolutePath
            }
        }

        composeRule.onNodeWithTag("tweet_options_button_$clipId", useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithTag("tweet_options_ocr").performClick()
        composeRule.onNodeWithTag("ocr_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("clip_open_x_$clipId").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_result_text").assertExists()
        composeRule.onNodeWithTag("ocr_result_text").performTextReplacement("Saved OCR Text")
        composeRule.onNodeWithTag("ocr_confirm").performClick()
        waitUntil { ocrTextForClip(clipId) == "Saved OCR Text" }

        composeRule.onNodeWithTag("tweet_options_button_$clipId", useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithTag("tweet_options_ocr").performClick()
        composeRule.onNodeWithTag("ocr_dialog").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("文字起こし中").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Saved OCR Text", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_redetect").performClick()
        composeRule.onNodeWithTag("ocr_redetect_warning_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_redetect_warning_confirm").performClick()
        composeRule.onNodeWithTag("ocr_cancel").performClick()
        assertEquals("Saved OCR Text", ocrTextForClip(clipId))
        assertTrue(java.io.File(photoPath).exists())
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

    private fun assertNoEditableSummaryInput(clipId: Long) {
        composeRule.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag("clip_card_$clipId")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    private fun ocrTextForClip(clipId: Long): String = runBlocking {
        storage().withDatabase { database ->
            database.openHelper.readableDatabase.query("SELECT ocrText FROM clips WHERE id = ?", arrayOf(clipId.toString())).use { cursor ->
                if (!cursor.moveToFirst()) return@withDatabase ""
                cursor.getString(0)
            }
        }
    }

    private fun bitmapBytes(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun mainViewModel(): MainViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }
        return field.get(composeRule.activity) as MainViewModel
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

    private fun tagIdByName(name: String): Long = runBlocking {
        storage().withDatabase { it.tagDao().getTags().single { tag -> tag.name == name }.id }
    }

    private fun groupsNamed(name: String): Int = runBlocking {
        storage().withDatabase { it.tagDao().getGroups().count { group -> group.name == name } }
    }

    private fun groupIdByName(name: String): Long = runBlocking {
        storage().withDatabase { it.tagDao().getGroups().single { group -> group.name == name }.id }
    }

    private fun tagExists(id: Long): Boolean = runBlocking {
        storage().withDatabase { it.tagDao().getTags().any { tag -> tag.id == id } }
    }

    private fun tagParentGroupId(id: Long): Long? = runBlocking {
        storage().withDatabase { it.tagDao().getTags().single { tag -> tag.id == id }.parentGroupId }
    }

    private fun tagColorId(id: Long): String = runBlocking {
        storage().withDatabase { it.tagDao().getTags().single { tag -> tag.id == id }.colorId }
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
                        "clip:${it.id}:${it.authorId}:${it.authorName}:${it.authorUsername}:${it.text}:${it.xCreatedAt}:${it.summary}:${it.ocrText}:${it.ocrUpdatedAt}:${it.isDeleted}:${it.likeCount}:${it.likeCountFetchedAt}:${it.likeCountFetchFailedAt}:${it.likeCountFetchError}"
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

    private fun slowDragDownOnScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = composeRule.activity.resources.displayMetrics
        val downTime = SystemClock.uptimeMillis()
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.28f
        val endY = metrics.heightPixels * 0.72f
        val steps = 12
        val events = buildList {
            add(MotionEvent.ACTION_DOWN to startY)
            for (step in 1 until steps) {
                val fraction = step.toFloat() / steps
                add(MotionEvent.ACTION_MOVE to (startY + (endY - startY) * fraction))
            }
            add(MotionEvent.ACTION_UP to endY)
        }
        events.forEachIndexed { index, (action, y) ->
            val event = MotionEvent.obtain(downTime, downTime + index * 40L, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
        composeRule.waitForIdle()
    }

    private fun fastFlingThenRetouchOnScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = composeRule.activity.resources.displayMetrics
        val downTime = SystemClock.uptimeMillis()
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.82f
        val endY = metrics.heightPixels * 0.18f
        val steps = 8
        val events = buildList {
            add(MotionEvent.ACTION_DOWN to startY)
            for (step in 1 until steps) {
                val fraction = step.toFloat() / steps
                add(MotionEvent.ACTION_MOVE to (startY + (endY - startY) * fraction))
            }
            add(MotionEvent.ACTION_UP to endY)
        }
        events.forEachIndexed { index, (action, y) ->
            val event = MotionEvent.obtain(downTime, downTime + index * 5L, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        val retouchTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(retouchTime, retouchTime + index * 8L, action, x, metrics.heightPixels * 0.5f, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private enum class ProductionMorphMatrixLocation {
        Start,
        AfterStart,
        Middle,
        FourRowsDown,
        HeaderBefore,
        HeaderAfter,
        PartialTop,
        PartialBottom,
        End,
    }

    private data class ProductionMorphMatrixCase(
        val fromColumns: Int,
        val toColumns: Int,
        val sortBase: ClassifiedSortBase,
        val location: ProductionMorphMatrixLocation,
        val centerYFraction: Float,
    )

    private fun retainProductionMatrixAssets(
        assetIds: List<Long>,
        paths: List<String>,
        previewStore: MediaGridPersistentPreviewStore,
        retainedIndices: Set<Int> = assetIds.indices.toSet(),
    ) {
        val residentStore = mainViewModel().mediaGridSessionState.value.retainedImageStore
            ?: error("Production retained image store is unavailable")
        val imageLoader = (composeRule.activity.application as LikeListManagerApp).container.mediaGridImageLoader
        runBlocking {
            assetIds.zip(paths).forEachIndexed { index, (assetId, path) ->
                if (index !in retainedIndices) return@forEachIndexed
                val previewFile = previewStore.previewFile(assetId)
                val source = buildMediaGridImageCandidates(
                    MediaGridImageCandidateInput(
                        assetId = assetId,
                        mediaKey = "production-location-matrix-$index",
                        localPath = path,
                        previewUrl = null,
                        remoteUrl = null,
                        displayUrl = path,
                        persistentPreview = MediaGridPersistentPreviewMetadata(
                            filePath = previewFile.absolutePath,
                            length = previewFile.length(),
                            lastModified = previewFile.lastModified(),
                        ),
                    ),
                ).first()
                val candidate = MediaGridPreparedCandidate(
                    kind = source.kind,
                    requestData = File(source.data as String),
                    sourceIdentity = source.sourceIdentity,
                    cacheKey = mediaGridImageCacheKey(source, 256, 256),
                    width = 256,
                    height = 256,
                    useDiskCache = false,
                )
                imageLoader.execute(buildMediaGridImageRequest(composeRule.activity, candidate))
                val value = imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(candidate.cacheKey))
                    ?: error("Asset $assetId was not present in the image cache")
                residentStore.retain(assetId, candidate, value, directDrawEligible = true)
            }
        }
        try {
            composeRule.waitUntil(10_000) {
                retainedIndices.all { index -> residentStore.hasEligibleDrawHandle(assetIds[index]) }
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError(
                "Retained image handles were not all eligible: " +
                    "missing=${retainedIndices.filterNot { residentStore.hasEligibleDrawHandle(assetIds[it]) }}, " +
                    "stats=${residentStore.stats()}, " +
                    "drawIndex=${residentStore.drawIndexSnapshot()}",
                error,
            )
        }
    }

    private fun applyProductionMatrixSort(sortBase: ClassifiedSortBase) {
        composeRule.onNodeWithTag("sort_open").performClick()
        when (sortBase) {
            ClassifiedSortBase.Default -> composeRule.onNodeWithTag("sort_clear_all_open").performClick()
            ClassifiedSortBase.PostTime -> {
                composeRule.onNodeWithTag("sort_base_date").performClick()
                composeRule.onNodeWithTag("sort_time_direction_new").performClick()
            }
            ClassifiedSortBase.LikeCount -> {
                composeRule.onNodeWithTag("sort_base_like").performClick()
                composeRule.onNodeWithTag("sort_like_direction_high").performClick()
            }
        }
        composeRule.onNodeWithTag("sort_apply").performClick()
        composeRule.waitUntil(30_000) {
            val session = mainViewModel().mediaGridSessionState.value
            !session.showInitialProgress && session.frame?.key?.dataKey?.sort?.baseOrder == sortBase
        }
    }

    private fun ensureProductionMatrixColumns(target: Int) {
        while (mainViewModel().mediaGridSessionState.value.columnCount != target) {
            val current = mainViewModel().mediaGridSessionState.value.columnCount
            check(current in 2..12)
            waitForStableIdleReadiness(current)
            val increase = current < target
            pinchOnGrid(
                gridTag = "classified_media_grid",
                centerSpan = if (increase) 260f else 180f,
                endSpan = if (increase) 180f else 260f,
                centerYFraction = 0.5f,
            )
            waitForGridColumnCount(if (increase) current + 1 else current - 1)
            waitForMorphCanvasRemoval()
        }
    }

    private fun prepareProductionMatrixLocation(
        location: ProductionMorphMatrixLocation,
        assetCount: Int,
    ) {
        val frame = mainViewModel().mediaGridSessionState.value.frame
            ?: error("Production matrix frame is unavailable")
        val targetOrdinal = when (location) {
            ProductionMorphMatrixLocation.Start -> 0
            ProductionMorphMatrixLocation.AfterStart -> 4
            ProductionMorphMatrixLocation.Middle -> assetCount / 2
            ProductionMorphMatrixLocation.FourRowsDown -> 16
            ProductionMorphMatrixLocation.PartialTop -> 4
            ProductionMorphMatrixLocation.PartialBottom -> (assetCount - 5).coerceAtLeast(0)
            ProductionMorphMatrixLocation.End -> (assetCount - 1).coerceAtLeast(0)
            ProductionMorphMatrixLocation.HeaderBefore,
            ProductionMorphMatrixLocation.HeaderAfter,
            -> null
        }
        val grid = composeRule.onNodeWithTag("classified_media_grid")
        if (location == ProductionMorphMatrixLocation.HeaderBefore ||
            location == ProductionMorphMatrixLocation.HeaderAfter
        ) {
            val header = frame.items.filterIsInstance<MediaGridHeaderItem>().getOrNull(1)
                ?: frame.items.filterIsInstance<MediaGridHeaderItem>().firstOrNull()
                ?: error("Header location requested without a header")
            grid.performScrollToNode(hasTestTag(header.key))
        } else {
            val itemIndex = frame.ordinalIndex.itemIndexByMediaOrdinal[
                targetOrdinal!!.coerceIn(0, frame.ordinalIndex.assetIdByMediaOrdinal.lastIndex),
            ]
            grid.performScrollToIndex(itemIndex)
            if (location == ProductionMorphMatrixLocation.PartialTop) {
                grid.performTouchInput {
                    swipeUp(
                        startY = centerY + 120f,
                        endY = centerY,
                        durationMillis = 300,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun waitForStableIdleReadiness(expectedColumnCount: Int) {
        try {
            composeRule.waitUntil(30_000) {
                val frameKey = mainViewModel().mediaGridSessionState.value.frame?.key ?: return@waitUntil false
                MediaGridMorphTestTrace.idleReadinessEvents().any { event ->
                    event.ready &&
                        event.identity.currentColumnCount == expectedColumnCount &&
                        event.identity.frameKey == frameKey
                }
            }
        } catch (error: ComposeTimeoutException) {
            val session = mainViewModel().mediaGridSessionState.value
            val events = MediaGridMorphTestTrace.idleReadinessEvents().takeLast(8)
            throw AssertionError(
                "Stable idle readiness missing for columns=$expectedColumnCount " +
                    "currentColumns=${session.columnCount}, frame=${session.frame?.key}, events=$events",
                error,
            )
        }
    }

    private fun waitForStableIdleReadinessFailure(
        expectedColumnCount: Int,
        expectedReasons: Set<MediaGridMorphClaimReadinessReason>,
    ) {
        try {
            composeRule.waitUntil(30_000) {
                val frameKey = mainViewModel().mediaGridSessionState.value.frame?.key ?: return@waitUntil false
                MediaGridMorphTestTrace.idleReadinessEvents().any { event ->
                    !event.ready &&
                        event.identity.currentColumnCount == expectedColumnCount &&
                        event.identity.frameKey == frameKey &&
                        event.failureReasons.any { reason ->
                            expectedReasons.any {
                                reason.startsWith("$it:") || reason.contains(":$it:")
                            }
                        }
                }
            }
        } catch (error: ComposeTimeoutException) {
            val session = mainViewModel().mediaGridSessionState.value
            val events = MediaGridMorphTestTrace.idleReadinessEvents().takeLast(8)
            throw AssertionError(
                "Stable idle failure $expectedReasons missing for columns=$expectedColumnCount " +
                    "currentColumns=${session.columnCount}, frame=${session.frame?.key}, events=$events",
                error,
            )
        }
    }

    private fun visibleGridAssetIds(assetIds: List<Long>): List<Long> {
        return assetIds.filter { assetId ->
            composeRule.onAllNodesWithTag("media_grid_item_$assetId", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun gridItemBounds(tag: String): androidx.compose.ui.geometry.Rect =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun waitForMorphCanvasRemoval() {
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("media_grid_morph_canvas", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun waitForGridColumnCount(expected: Int) {
        try {
            composeRule.waitUntil(30_000) {
                val session = mainViewModel().mediaGridSessionState.value
                session.columnCount == expected &&
                    session.requestedColumnCount == expected &&
                    session.frame?.key?.columnCount == expected
            }
        } catch (cause: Throwable) {
            val session = mainViewModel().mediaGridSessionState.value
            throw AssertionError(
                    "Timed out waiting for grid columns=$expected; " +
                    "actual=${session.columnCount}, requested=${session.requestedColumnCount}, " +
                    "frame=${session.frame?.key?.columnCount}, status=${session.controllerState.startup}, " +
                    "showInitialProgress=${session.showInitialProgress}",
                cause,
            )
        }
    }

    private fun scrollThenPinchOnGrid(
        gridTag: String,
        centerSpan: Float,
        endSpan: Float,
        onBeforePhysicalUp: () -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val location = IntArray(2)
        composeRule.activity.window.decorView.getLocationOnScreen(location)
        val gridBounds = composeRule.onNodeWithTag(gridTag).fetchSemanticsNode().boundsInRoot
        val centerX = location[0] + gridBounds.center.x
        val centerY = location[1] + gridBounds.center.y
        val downTime = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )

        fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }

        fun send(action: Int, timeOffset: Long, values: Array<MotionEvent.PointerCoords>) {
            val event = MotionEvent.obtain(
                downTime,
                downTime + timeOffset,
                action,
                values.size,
                properties,
                values,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }

        val startHalfSpan = centerSpan / 2f
        val endHalfSpan = endSpan / 2f
        val scrolledY = centerY + 80f
        send(MotionEvent.ACTION_DOWN, 0, arrayOf(coords(centerX - startHalfSpan, centerY)))
        send(MotionEvent.ACTION_MOVE, 12, arrayOf(coords(centerX - startHalfSpan, scrolledY)))
        send(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            16,
            arrayOf(
                coords(centerX - startHalfSpan, scrolledY),
                coords(centerX + startHalfSpan, scrolledY),
            ),
        )
        repeat(8) { step ->
            val fraction = (step + 1).toFloat() / 8f
            val span = centerSpan + (endSpan - centerSpan) * fraction
            val halfSpan = span / 2f
            send(
                MotionEvent.ACTION_MOVE,
                32L + step * 16L,
                arrayOf(
                    coords(centerX - halfSpan, scrolledY),
                    coords(centerX + halfSpan, scrolledY),
                ),
            )
            if (step == 4) {
                instrumentation.waitForIdleSync()
                onBeforePhysicalUp()
            }
        }
        send(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            160,
            arrayOf(
                coords(centerX - endHalfSpan, scrolledY),
                coords(centerX + endHalfSpan, scrolledY),
            ),
        )
        send(MotionEvent.ACTION_UP, 176, arrayOf(coords(centerX - endHalfSpan, scrolledY)))
        instrumentation.waitForIdleSync()
    }

    private fun pinchOnGrid(
        gridTag: String,
        centerSpan: Float,
        endSpan: Float,
        onMidGesture: (() -> Unit)? = null,
        centerYFraction: Float = 0.5f,
        onBeforePhysicalUp: (() -> Unit)? = null,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val location = IntArray(2)
        composeRule.activity.window.decorView.getLocationOnScreen(location)
        val gridBounds = composeRule.onNodeWithTag(gridTag).fetchSemanticsNode().boundsInRoot
        val centerX = location[0] + gridBounds.center.x
        val centerY = location[1] + gridBounds.top + gridBounds.height * centerYFraction
        val downTime = SystemClock.uptimeMillis()
        val stepCount = 8
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )

        fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }

        fun send(action: Int, eventTimeOffsetMs: Long, pointerCoords: Array<MotionEvent.PointerCoords>) {
            val event = MotionEvent.obtain(
                downTime,
                downTime + eventTimeOffsetMs,
                action,
                pointerCoords.size,
                properties,
                pointerCoords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }

        val startHalfSpan = centerSpan / 2f
        val endHalfSpan = endSpan / 2f
        send(MotionEvent.ACTION_DOWN, 0, arrayOf(coords(centerX - startHalfSpan, centerY)))
        send(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            16,
            arrayOf(
                coords(centerX - startHalfSpan, centerY),
                coords(centerX + startHalfSpan, centerY),
            ),
        )
        repeat(stepCount) { step ->
            val fraction = (step + 1).toFloat() / stepCount.toFloat()
            val span = centerSpan + (endSpan - centerSpan) * fraction
            val halfSpan = span / 2f
            send(
                MotionEvent.ACTION_MOVE,
                32L + step * 16L,
                arrayOf(
                    coords(centerX - halfSpan, centerY),
                    coords(centerX + halfSpan, centerY),
                ),
            )
            if (onMidGesture != null && step == stepCount / 2) {
                instrumentation.waitForIdleSync()
                onMidGesture()
            }
        }
        onBeforePhysicalUp?.invoke()
        send(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            160,
            arrayOf(
                coords(centerX - endHalfSpan, centerY),
                coords(centerX + endHalfSpan, centerY),
            ),
        )
        send(MotionEvent.ACTION_UP, 176, arrayOf(coords(centerX - endHalfSpan, centerY)))
        instrumentation.waitForIdleSync()
    }
}
