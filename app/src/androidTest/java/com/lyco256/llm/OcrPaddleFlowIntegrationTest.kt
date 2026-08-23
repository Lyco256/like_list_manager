package com.lyco256.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrPoint
import com.lyco256.llm.data.OcrPostRecognitionResult
import com.lyco256.llm.data.OcrQualityMode
import com.lyco256.llm.data.OcrRecognitionResult
import com.lyco256.llm.data.PaddleOcrTextGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OcrPaddleFlowIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun realPaddleFlowSupportsModeRedetectRegionEditAndSessionReset() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageFile = File(context.cacheDir, "ocr7-paddle-flow.png")
        createTestImage(imageFile)
        val clip = ClipEntity(
            id = 701L,
            xPostId = "ocr7-test",
            authorName = "OCR7",
            authorUsername = "ocr7",
            text = "",
            postUrl = "https://example.invalid/ocr7",
            xCreatedAt = "2026-08-22T00:00:00Z",
            savedAt = "2026-08-22T00:00:00Z",
            syncedAt = "2026-08-22T00:00:00Z",
        )
        val asset = AssetEntity(
            id = 702L,
            clipId = clip.id,
            mediaKey = "ocr7-media",
            type = "photo",
            remoteUrl = null,
            previewUrl = null,
            localPath = imageFile.path,
            width = 800,
            height = 400,
            createdAt = "2026-08-22T00:00:00Z",
        )
        val clipWithDetails = ClipWithDetails(clip, listOf(asset), emptyList())
        val page = OcrImagePage(asset.id, imageFile.path, 800, 400)
        val gateway = PaddleOcrTextGateway(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requestedModes = Collections.synchronizedList(mutableListOf<OcrQualityMode>())
        val completedDetections = AtomicInteger(0)
        val lastRecognition = AtomicReference<OcrRecognitionResult?>()
        var isOpen by mutableStateOf(true)
        var sessionKey by mutableStateOf(1L)

        try {
            composeRule.setContent {
                MaterialTheme {
                    if (isOpen) {
                        OcrSessionDialog(
                            clip = clipWithDetails,
                            sessionKey = sessionKey,
                            previewAssets = listOf(page),
                            onDetect = { details, mode, success, failure ->
                                requestedModes += mode
                                scope.launch {
                                    runCatching {
                                        val recognition = withContext(Dispatchers.IO) {
                                            val bitmap = BitmapFactory.decodeFile(
                                                details.assets.single().localPath,
                                            ) ?: error("OCR7 test image could not be decoded")
                                            try {
                                                gateway.recognize(bitmap, mode)
                                            } finally {
                                                bitmap.recycle()
                                            }
                                        }
                                        lastRecognition.set(recognition)
                                        success(
                                            OcrPostRecognitionResult(
                                                clipId = details.clip.id,
                                                assets = listOf(
                                                    OcrAssetRecognitionResult(
                                                        assetId = asset.id,
                                                        localPath = imageFile.path,
                                                        recognition = recognition,
                                                    ),
                                                ),
                                                fullText = recognition.fullText,
                                            ),
                                        )
                                        completedDetections.incrementAndGet()
                                    }.onFailure { error ->
                                        failure(error.message ?: "OCR7 real flow failed")
                                    }
                                }
                            },
                            onSave = { _, _, callback -> callback(null) },
                            onDismiss = { isOpen = false },
                        )
                    }
                }
            }

            composeRule.waitUntil(120_000) { completedDetections.get() >= 1 }
            composeRule.waitForIdle()
            assertEquals(listOf(OcrQualityMode.FAST), requestedModes.toList())
            val initialRecognition = requireRecognitionWithPolygon(lastRecognition.get())

            composeRule.onNodeWithTag("ocr_quality_accurate").performClick()
            composeRule.waitForIdle()
            assertEquals(1, requestedModes.size)
            composeRule.onNodeWithTag("ocr_redetect").performClick()
            composeRule.waitUntil(120_000) { completedDetections.get() >= 2 }
            composeRule.waitForIdle()
            assertEquals(OcrQualityMode.ACCURATE, requestedModes[1])
            val accurateRecognition = requireRecognitionWithPolygon(lastRecognition.get())
            assertEquals(initialRecognition.imageWidth, accurateRecognition.imageWidth)

            val region = accurateRecognition.regions.first { it.polygon != null }
            val polygon = checkNotNull(region.polygon)
            val viewer = composeRule.onNodeWithTag("ocr_image_viewer").fetchSemanticsNode()
            val viewportPoints = OcrViewerGeometry.mapPolygonToViewport(
                polygon = polygon,
                sourceWidth = accurateRecognition.imageWidth,
                sourceHeight = accurateRecognition.imageHeight,
                viewportWidth = viewer.boundsInRoot.width,
                viewportHeight = viewer.boundsInRoot.height,
            ) ?: error("OCR7 polygon did not map to the viewer")
            val tapPoint = Offset(
                viewportPoints.map(OcrPoint::x).average().toFloat(),
                viewportPoints.map(OcrPoint::y).average().toFloat(),
            )
            composeRule.onNodeWithTag("ocr_image_viewer").performTouchInput { click(tapPoint) }
            composeRule.onNodeWithTag("ocr_region_text").assertIsDisplayed()
            composeRule.onNodeWithTag("ocr_region_text").performTextReplacement("OCR7 edited")
            composeRule.onNodeWithTag("ocr_region_done").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("OCR7 edited", substring = true).assertIsDisplayed()

            composeRule.onNodeWithTag("ocr_cancel").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("ocr_quality_mode").fetchSemanticsNodes().isEmpty()
            }
            composeRule.runOnIdle {
                sessionKey = 2L
                isOpen = true
            }
            composeRule.waitUntil(120_000) { completedDetections.get() >= 3 }
            composeRule.waitForIdle()
            assertEquals(OcrQualityMode.FAST, requestedModes[2])
        } finally {
            scope.cancel()
            runBlocking { gateway.close() }
            imageFile.delete()
        }
    }

    private fun requireRecognitionWithPolygon(result: OcrRecognitionResult?): OcrRecognitionResult {
        assertNotNull("PaddleOCR returned no recognition result", result)
        assertTrue("PaddleOCR returned no text regions", result!!.regions.isNotEmpty())
        assertTrue("PaddleOCR returned no polygon regions", result.regions.any { it.polygon != null })
        return result
    }

    private fun createTestImage(file: File) {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("OCR7", 220f, 255f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 120f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
            }
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
