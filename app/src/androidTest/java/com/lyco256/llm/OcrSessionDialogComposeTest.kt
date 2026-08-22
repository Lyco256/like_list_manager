package com.lyco256.llm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.junit4.createComposeRule
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrDetectionMetadata
import com.lyco256.llm.data.OcrDetectionResult
import com.lyco256.llm.data.OcrEngine
import com.lyco256.llm.data.OcrPoint
import com.lyco256.llm.data.OcrPolygon
import com.lyco256.llm.data.OcrPostRecognitionResult
import com.lyco256.llm.data.OcrRecognitionResult
import com.lyco256.llm.data.OcrTextRegion
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagWithCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OcrSessionDialogComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun engineSelectionWaitsForRedetectAndDisplayedMetadataChangesOnlyAfterSuccess() {
        val requestedEngines = mutableListOf<OcrEngine>()
        var success: ((OcrDetectionResult) -> Unit)? = null
        var failure: ((String) -> Unit)? = null

        composeRule.setContent {
            MaterialTheme {
                OcrSessionDialog(
                    clip = clip(),
                    sessionKey = 1L,
                    previewAssets = emptyList(),
                    onDetect = { _, _, _ -> error("comparison handler must be used") },
                    onDetectForEngine = { _, engine, onSuccess, onFailure ->
                        requestedEngines += engine
                        success = onSuccess
                        failure = onFailure
                    },
                    onSave = { _, _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("ocr_engine_ml_kit").assertIsSelected()
        composeRule.onAllNodesWithTag("ocr_detection_metadata").assertCountEquals(0)
        composeRule.onNodeWithTag("ocr_engine_pp_ocrv6_small").performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<OcrEngine>(), requestedEngines)

        composeRule.onNodeWithTag("ocr_redetect").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(OcrEngine.PP_OCRV6_SMALL), requestedEngines)
        composeRule.runOnIdle {
            success!!(
                OcrDetectionResult(
                    recognition = structuredText(7L, "pp result"),
                    metadata = OcrDetectionMetadata(OcrEngine.PP_OCRV6_SMALL, 2_400L),
                ),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_detection_metadata").assertTextContains("PP-OCRv6 small · 2.4s")
        composeRule.onNodeWithText("pp result").assertIsDisplayed()

        composeRule.onNodeWithTag("ocr_engine_ml_kit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_detection_metadata").assertTextContains("PP-OCRv6 small · 2.4s")
        assertEquals(1, requestedEngines.size)

        composeRule.onNodeWithTag("ocr_redetect").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(OcrEngine.PP_OCRV6_SMALL, OcrEngine.ML_KIT), requestedEngines)
        composeRule.runOnIdle { failure!!("ML Kit failed") }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ML Kit failed").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_detection_metadata").assertTextContains("PP-OCRv6 small · 2.4s")
        composeRule.onNodeWithText("pp result").assertIsDisplayed()
    }

    @Test
    fun fullScreenViewerUsesAssetPagesAndSwitchesAtFitScale() {
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    previewAssets = listOf(
                        OcrImagePage(11L, "/tmp/ocr-first.webp", 100, 100),
                        OcrImagePage(22L, "/tmp/ocr-second.webp", 100, 100),
                    ),
                    text = "draft",
                    structuredResult = null,
                    isProcessing = false,
                    errorMessage = null,
                    onTextChange = {},
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("ocr_full_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_page_indicator").assertTextContains("1 / 2")
        composeRule.onNodeWithTag("ocr_image_asset_11").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_page_indicator").assertTextContains("2 / 2")
        composeRule.onNodeWithTag("ocr_image_asset_22").assertIsDisplayed()
    }

    @Test
    fun structuredHighlightFollowsTheMatchingAssetPageOnly() {
        val structured = OcrPostRecognitionResult(
            clipId = 7L,
            assets = listOf(
                OcrAssetRecognitionResult(
                    assetId = 22L,
                    localPath = "/tmp/ocr-second.webp",
                    recognition = OcrRecognitionResult(
                        imageWidth = 100,
                        imageHeight = 100,
                        fullText = "second",
                        regions = listOf(
                            OcrTextRegion(
                                text = "second",
                                polygon = OcrPolygon(
                                    listOf(
                                        OcrPoint(10f, 10f),
                                        OcrPoint(70f, 12f),
                                        OcrPoint(68f, 30f),
                                        OcrPoint(8f, 28f),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            fullText = "second",
        )

        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    previewAssets = listOf(
                        OcrImagePage(11L, "/tmp/ocr-first.webp", 100, 100),
                        OcrImagePage(22L, "/tmp/ocr-second.webp", 100, 100),
                    ),
                    text = "second",
                    structuredResult = structured,
                    isProcessing = false,
                    errorMessage = null,
                    onTextChange = {},
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("ocr_polygon_overlay").assertCountEquals(0)
        composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_polygon_overlay").assertIsDisplayed()
    }

    @Test
    fun polygonSelectionShowsSingleRegionEditorAndRebuildsThePostDraft() {
        var structured by mutableStateOf(structuredWithRegions())
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    previewAssets = listOf(OcrImagePage(11L, "/tmp/ocr.webp", 100, 100)),
                    text = structured.fullText,
                    structuredResult = structured,
                    isProcessing = false,
                    errorMessage = null,
                    onTextChange = { error("structured OCR must not expose whole-text editing") },
                    onRegionTextChange = { key, value ->
                        structured = structured.withRegionText(key, value)!!
                    },
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { click(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_region_text").assertTextContains("first")
        composeRule.onNodeWithTag("ocr_polygon_overlay_selected").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("ocr_result_text").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("ocr_region_text").performTextReplacement("edited")
        composeRule.onNodeWithTag("ocr_region_done").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("edited\nsecond", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_polygon_overlay").assertIsDisplayed()
    }

    @Test
    fun polygonOutsideTapClearsSelectionAndPageChangeDoesNotSelectTheSwipeEnd() {
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    previewAssets = listOf(
                        OcrImagePage(11L, "/tmp/ocr-first.webp", 100, 100),
                        OcrImagePage(22L, "/tmp/ocr-second.webp", 100, 100),
                    ),
                    text = "first\n\nsecond",
                    structuredResult = structuredWithRegions(),
                    isProcessing = false,
                    errorMessage = null,
                    onTextChange = {},
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { click(center) }
        composeRule.onNodeWithTag("ocr_region_text").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { click(topLeft) }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_region_text").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_region_text").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("ocr_page_indicator").assertTextContains("2 / 2")
    }

    @Test
    fun pinchAndPanDoNotSelectButAStaticTapAfterZoomDoes() {
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    previewAssets = listOf(OcrImagePage(11L, "/tmp/ocr.webp", 100, 100)),
                    text = "first\nsecond",
                    structuredResult = structuredWithRegions(),
                    isProcessing = false,
                    errorMessage = null,
                    onTextChange = {},
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        val viewer = composeRule.onNodeWithTag("ocr_image_viewer")
        viewer.performTouchInput {
            down(0, center - androidx.compose.ui.geometry.Offset(30f, 0f))
            down(1, center + androidx.compose.ui.geometry.Offset(30f, 0f))
            moveTo(0, center - androidx.compose.ui.geometry.Offset(55f, 0f), 100)
            moveTo(1, center + androidx.compose.ui.geometry.Offset(55f, 0f), 100)
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_region_text").fetchSemanticsNodes().isEmpty())

        viewer.performTouchInput { click(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_region_text").assertTextContains("first")

        viewer.performTouchInput {
            down(0, center)
            moveTo(0, center + androidx.compose.ui.geometry.Offset(30f, 0f), 100)
            up(0)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_region_text").assertTextContains("first")
    }

    @Test
    fun selectingAnotherPolygonShowsItsCurrentValueAndRedetectFailureKeepsItAvailable() {
        var processing by mutableStateOf(false)
        var errorMessage by mutableStateOf<String?>(null)
        val firstPolygon = OcrPolygon(
            listOf(
                OcrPoint(10f, 10f), OcrPoint(40f, 10f),
                OcrPoint(40f, 40f), OcrPoint(10f, 40f),
            ),
        )
        val secondPolygon = OcrPolygon(
            listOf(
                OcrPoint(60f, 60f), OcrPoint(90f, 60f),
                OcrPoint(90f, 90f), OcrPoint(60f, 90f),
            ),
        )
        var structured by mutableStateOf(
            OcrPostRecognitionResult(
                clipId = 7L,
                assets = listOf(
                    OcrAssetRecognitionResult(
                        11L,
                        "/tmp/ocr.webp",
                        OcrRecognitionResult(
                            100,
                            100,
                            "first\nsecond",
                            listOf(
                                OcrTextRegion("first", firstPolygon),
                                OcrTextRegion("second", secondPolygon, precedingSeparator = "\n"),
                            ),
                        ),
                    ),
                ),
                fullText = "first\nsecond",
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    previewAssets = listOf(OcrImagePage(11L, "/tmp/ocr.webp", 100, 100)),
                    text = structured.fullText,
                    structuredResult = structured,
                    isProcessing = processing,
                    errorMessage = errorMessage,
                    onTextChange = {},
                    onRegionTextChange = { key, value ->
                        structured = structured.withRegionText(key, value)!!
                    },
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        val viewer = composeRule.onNodeWithTag("ocr_image_viewer")
        viewer.performTouchInput {
            click(center + androidx.compose.ui.geometry.Offset(center.x * 0.5f, center.y * 0.5f))
        }
        composeRule.onNodeWithTag("ocr_region_text").assertTextContains("second")
        composeRule.onNodeWithTag("ocr_region_text").performTextReplacement("edited")
        composeRule.onNodeWithTag("ocr_region_done").performClick()

        composeRule.runOnIdle {
            processing = true
            errorMessage = null
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_region_text").fetchSemanticsNodes().isEmpty())

        composeRule.runOnIdle {
            processing = false
            errorMessage = "recognition failed"
        }
        composeRule.waitForIdle()
        viewer.performTouchInput {
            click(center + androidx.compose.ui.geometry.Offset(center.x * 0.5f, center.y * 0.5f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_region_text").assertTextContains("edited")
    }

    @Test
    fun detectionStartAndStructuredReplacementClearTheSelectedRegion() {
        var processing by mutableStateOf(false)
        var generation by mutableStateOf(1L)
        var structured by mutableStateOf(structuredWithRegions())
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    previewAssets = listOf(OcrImagePage(11L, "/tmp/ocr.webp", 100, 100)),
                    text = structured.fullText,
                    structuredResult = structured,
                    structuredResultGeneration = generation,
                    isProcessing = processing,
                    errorMessage = null,
                    onTextChange = {},
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { click(center) }
        composeRule.onNodeWithTag("ocr_region_text").assertIsDisplayed()

        composeRule.runOnIdle { processing = true }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_region_text").fetchSemanticsNodes().isEmpty())

        composeRule.runOnIdle {
            processing = false
            generation++
            structured = structured.copy(fullText = "replacement")
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_region_text").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun redetectResultRemovingCurrentAssetMovesToAnotherValidPage() {
        var structured by mutableStateOf(
            OcrPostRecognitionResult(
                clipId = 7L,
                assets = listOf(
                    OcrAssetRecognitionResult(11L, "/tmp/ocr-first.webp", recognition(11L)),
                    OcrAssetRecognitionResult(22L, "/tmp/ocr-second.webp", recognition(22L)),
                ),
                fullText = "first\nsecond",
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    sessionKey = 1L,
                    previewAssets = listOf(
                        OcrImagePage(11L, "/tmp/ocr-first.webp", 100, 100),
                        OcrImagePage(22L, "/tmp/ocr-second.webp", 100, 100),
                    ),
                    text = "second",
                    structuredResult = structured,
                    isProcessing = false,
                    errorMessage = null,
                    onTextChange = {},
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_image_asset_22").assertIsDisplayed()

        structured = structured.copy(
            assets = listOf(
                OcrAssetRecognitionResult(11L, "/tmp/ocr-first.webp", recognition(11L)),
            ),
            fullText = "first",
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_page_indicator").assertTextContains("1 / 2")
        composeRule.onNodeWithTag("ocr_image_asset_11").assertIsDisplayed()
    }

    @Test
    fun detectingKeepsThePreviousStructuredPolygonVisible() {
        val structured = OcrPostRecognitionResult(
            clipId = 7L,
            assets = listOf(
                OcrAssetRecognitionResult(11L, "/tmp/ocr-first.webp", recognitionWithPolygon(11L)),
            ),
            fullText = "first",
        )
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    sessionKey = 1L,
                    previewAssets = listOf(OcrImagePage(11L, "/tmp/ocr-first.webp", 100, 100)),
                    text = "first",
                    structuredResult = structured,
                    isProcessing = true,
                    errorMessage = null,
                    onTextChange = {},
                    onRedetect = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("ocr_detecting").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_polygon_overlay").assertIsDisplayed()
    }

    @Test
    fun saveDoesNotDismissBeforeCompletionAndFailureKeepsDraftForRetry() {
        var saveCalls = 0
        lateinit var completeSave: (String?) -> Unit

        composeRule.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) {
                OcrSessionDialog(
                    clip = clip(),
                    sessionKey = 1L,
                    previewAssets = emptyList(),
                    onDetect = { _, _, _ -> error("saved OCR must not auto-detect") },
                    onSave = { _, _, complete ->
                        saveCalls++
                        completeSave = complete
                    },
                    onDismiss = { open = false },
                )
            }
        }

        composeRule.onNodeWithTag("ocr_result_text").assertTextContains("persisted")
        composeRule.onNodeWithTag("ocr_confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(1, saveCalls)
        composeRule.onNodeWithTag("ocr_full_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_saving").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_cancel").assertIsNotEnabled()
        composeRule.onNodeWithTag("ocr_redetect").assertIsNotEnabled()
        composeRule.onNodeWithTag("ocr_result_text").assertIsNotEnabled()

        completeSave("save failed")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_full_screen").assertIsDisplayed()
        composeRule.onNodeWithText("save failed").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_result_text").assertTextContains("persisted")

        composeRule.onNodeWithTag("ocr_confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(2, saveCalls)
        completeSave(null)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_full_screen").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun cancelDropsManualDraftAndReopenUsesPersistedText() {
        composeRule.setContent {
            var open by remember { mutableStateOf(false) }
            var sessionKey by remember { mutableStateOf(0L) }
            androidx.compose.material3.TextButton(
                onClick = {
                    sessionKey++
                    open = true
                },
                modifier = Modifier.testTag("ocr_open"),
            ) { androidx.compose.material3.Text("open") }
            if (open) {
                androidx.compose.runtime.key(sessionKey) {
                    OcrSessionDialog(
                        clip = clip(),
                        sessionKey = sessionKey,
                        previewAssets = emptyList(),
                        onDetect = { _, _, _ -> error("saved OCR must not auto-detect") },
                        onSave = { _, _, _ -> error("cancelled session must not save") },
                        onDismiss = { open = false },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("ocr_open").performClick()
        composeRule.onNodeWithTag("ocr_result_text").performTextReplacement("edited")
        composeRule.onNodeWithTag("ocr_cancel").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_full_screen").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("ocr_open").performClick()
        composeRule.onNodeWithTag("ocr_result_text").assertTextContains("persisted")
    }

    @Test
    fun mediaGridEntryUsesTheSameOcrSessionDialog() {
        var saveCalls = 0
        var comparisonCalls = 0
        var requestedEngine: OcrEngine? = null
        lateinit var completeSave: (String?) -> Unit

        composeRule.setContent {
            MaterialTheme {
                MediaGridTweetDialog(
                    state = MediaGridTweetDialogState.Loaded(clip(withPhoto = true)),
                    hierarchy = TagHierarchy(),
                    onDismiss = {},
                    onTagsChange = { _, _ -> },
                    onSummaryChange = { _, _ -> },
                    onOcrSave = { _, _ -> },
                    onOcrDetect = { _, _, _ -> },
                    onOcrSaveResult = { _, _, complete ->
                        saveCalls++
                        completeSave = complete
                    },
                    onOcrDetectStructured = { _, _, _ -> },
                    onOcrDetectComparison = { details, engine, success, _ ->
                        comparisonCalls++
                        requestedEngine = engine
                        success(
                            OcrDetectionResult(
                                recognition = structuredText(details.clip.id, "PP grid"),
                                metadata = OcrDetectionMetadata(engine, 1_300L),
                            ),
                        )
                    },
                    onDelete = {},
                    onAuthorClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("media_grid_tweet_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("tweet_options_button_7").performClick()
        composeRule.onNodeWithTag("tweet_options_ocr").performClick()
        composeRule.onNodeWithTag("ocr_full_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_result_text").assertTextContains("persisted")
        composeRule.onNodeWithTag("ocr_engine_pp_ocrv6_small").performClick()
        composeRule.onNodeWithTag("ocr_redetect").performClick()
        composeRule.waitForIdle()
        assertEquals(1, comparisonCalls)
        assertEquals(OcrEngine.PP_OCRV6_SMALL, requestedEngine)
        composeRule.onNodeWithText("PP grid").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_detection_metadata").assertTextContains("PP-OCRv6 small · 1.3s")
        composeRule.onNodeWithTag("ocr_cancel").performClick()
        composeRule.waitForIdle()
        assertEquals(0, saveCalls)
        assertTrue(composeRule.onAllNodesWithTag("ocr_full_screen").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("media_grid_tweet_dialog").assertIsDisplayed()
    }

    @Test
    fun classifiedCardEntryUsesTheSameOcrSessionDialog() {
        val tag = TagEntity(
            id = 11,
            name = "OCR classified",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
        val classifiedClip = clip(withPhoto = true).copy(tags = listOf(tag))
        val state = MainUiState(
            clips = listOf(classifiedClip),
            hasReceivedInitialClipEmission = true,
            tagHierarchy = TagHierarchy(tags = listOf(TagWithCount(tag, 0))),
        )

        composeRule.setContent {
            MaterialTheme {
                EnhancedClassifiedScreen(
                    uiState = state,
                    mediaGridState = ClassifiedMediaGridState(),
                    listState = rememberLazyListState(),
                    displayMode = ClassifiedDisplayMode.Card,
                    mediaGridColumnCount = 2,
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

        composeRule.onNodeWithTag("tweet_options_button_7").performClick()
        composeRule.onNodeWithTag("tweet_options_ocr").performClick()
        composeRule.onNodeWithTag("ocr_full_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_result_text").assertTextContains("persisted")
        composeRule.onNodeWithTag("ocr_cancel").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("ocr_full_screen").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("clip_card_7").assertIsDisplayed()
    }

    private fun clip(withPhoto: Boolean = false): ClipWithDetails = ClipWithDetails(
        clip = ClipEntity(
            id = 7,
            xPostId = "ocr-compose",
            authorName = "Author",
            authorUsername = "author",
            text = "text",
            postUrl = "https://example.test/post",
            xCreatedAt = "2026-01-01T00:00:00Z",
            savedAt = "2026-01-01T00:00:00Z",
            syncedAt = "2026-01-01T00:00:00Z",
            ocrText = "persisted",
        ),
        assets = if (withPhoto) {
            listOf(
                AssetEntity(
                    id = 1,
                    clipId = 7,
                    mediaKey = "ocr-photo",
                    type = "photo",
                    remoteUrl = "https://example.test/ocr-photo",
                    previewUrl = null,
                    localPath = "/tmp/ocr-photo.webp",
                    createdAt = "2026-01-01T00:00:00Z",
                ),
            )
        } else {
            emptyList()
        },
        tags = emptyList(),
    )

    private fun recognition(id: Long): OcrRecognitionResult = OcrRecognitionResult(
        imageWidth = 100,
        imageHeight = 100,
        fullText = id.toString(),
    )

    private fun recognitionWithPolygon(id: Long): OcrRecognitionResult = OcrRecognitionResult(
        imageWidth = 100,
        imageHeight = 100,
        fullText = id.toString(),
        regions = listOf(
            OcrTextRegion(
                text = id.toString(),
                polygon = OcrPolygon(
                    listOf(
                        OcrPoint(10f, 10f),
                        OcrPoint(50f, 10f),
                        OcrPoint(50f, 30f),
                        OcrPoint(10f, 30f),
                    ),
                ),
            ),
        ),
    )

    private fun structuredWithRegions(): OcrPostRecognitionResult = OcrPostRecognitionResult(
        clipId = 7L,
        assets = listOf(
            OcrAssetRecognitionResult(
                assetId = 11L,
                localPath = "/tmp/ocr.webp",
                recognition = OcrRecognitionResult(
                    imageWidth = 100,
                    imageHeight = 100,
                    fullText = "first\nsecond",
                    regions = listOf(
                        OcrTextRegion(
                            text = "first",
                            polygon = OcrPolygon(
                                listOf(
                                    OcrPoint(10f, 10f),
                                    OcrPoint(90f, 10f),
                                    OcrPoint(90f, 90f),
                                    OcrPoint(10f, 90f),
                                ),
                            ),
                        ),
                        OcrTextRegion(text = "second", precedingSeparator = "\n"),
                    ),
                ),
            ),
        ),
        fullText = "first\nsecond",
    )

    private fun structuredText(clipId: Long, text: String): OcrPostRecognitionResult = OcrPostRecognitionResult(
        clipId = clipId,
        assets = listOf(
            OcrAssetRecognitionResult(
                assetId = 1L,
                localPath = "/tmp/ocr.webp",
                recognition = OcrRecognitionResult(
                    imageWidth = 100,
                    imageHeight = 100,
                    fullText = text,
                    regions = listOf(OcrTextRegion(text = text)),
                ),
            ),
        ),
        fullText = text,
    )
}
