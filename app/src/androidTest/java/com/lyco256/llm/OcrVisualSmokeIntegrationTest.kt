package com.lyco256.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrPoint
import com.lyco256.llm.data.OcrPolygon
import com.lyco256.llm.data.OcrPostRecognitionResult
import com.lyco256.llm.data.OcrRecognitionResult
import com.lyco256.llm.data.OcrTextRegion
import com.lyco256.llm.data.withReadingOrder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OcrVisualSmokeIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun capturesHorizontalVerticalAndMultiAssetOcr8States() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outputDirectory = requireNotNull(context.getExternalFilesDir("ocr8-visual")).apply { mkdirs() }
        val horizontalImage = createVisualImage(outputDirectory, "horizontal.png", "横書き", listOf(
            VisualBox(60f, 100f, 180f, 145f, "TL"),
            VisualBox(420f, 100f, 540f, 145f, "TR"),
            VisualBox(60f, 600f, 180f, 645f, "BL"),
            VisualBox(420f, 600f, 540f, 645f, "BR"),
        ))
        val verticalImage = createVisualImage(outputDirectory, "vertical.png", "縦書き", listOf(
            VisualBox(430f, 100f, 475f, 240f, "R1"),
            VisualBox(430f, 300f, 475f, 440f, "R2"),
            VisualBox(125f, 100f, 170f, 240f, "L1"),
            VisualBox(125f, 300f, 170f, 440f, "L2"),
        ))

        val horizontal = horizontalRecognition()
        val vertical = verticalRecognition()
        val horizontalPage = OcrImagePage(11L, horizontalImage.path, 600, 900)
        val verticalPage = OcrImagePage(22L, verticalImage.path, 600, 900)
        val multiAsset = OcrPostRecognitionResult(
            clipId = 8L,
            assets = listOf(
                OcrAssetRecognitionResult(
                    assetId = 11L,
                    localPath = horizontalImage.path,
                    recognition = OcrRecognitionResult(
                        600,
                        900,
                        "raw",
                        listOf(OcrTextRegion("first", box(60f, 100f, 180f, 145f))),
                    ).withReadingOrder(),
                ),
                OcrAssetRecognitionResult(
                    assetId = 22L,
                    localPath = verticalImage.path,
                    recognition = OcrRecognitionResult(
                        600,
                        900,
                        "raw",
                        listOf(OcrTextRegion("second", box(430f, 100f, 475f, 240f))),
                    ).withReadingOrder(),
                ),
            ),
            fullText = "",
        ).rebuildFromRegions()

        var pages by mutableStateOf(listOf(horizontalPage))
        var structured by mutableStateOf(postResult(8L, 11L, horizontal))
        var generation by mutableStateOf(1L)
        composeRule.setContent {
            MaterialTheme {
                OcrTextDialog(
                    previewAssets = pages,
                    text = structured.fullText,
                    structuredResult = structured,
                    structuredResultGeneration = generation,
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

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_result_text_value").assertTextContains("TL\n\nTR\n\nBL\n\nBR")
        saveCapture(outputDirectory, "horizontal-dialog.png")

        composeRule.runOnIdle {
            pages = listOf(verticalPage)
            structured = postResult(8L, 22L, vertical)
            generation++
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_result_text_value").assertTextContains("R1 R2\n\nL1 L2")
        saveCapture(outputDirectory, "vertical-dialog.png")

        composeRule.runOnIdle {
            pages = listOf(horizontalPage, verticalPage)
            structured = multiAsset
            generation++
        }
        composeRule.waitForIdle()
        val layoutResults = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithTag("ocr_result_text_value").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
            getResults(layoutResults)
        }
        val secondPosition = layoutResults.first().getBoundingBox(multiAsset.fullText.indexOf("second")).center
        composeRule.onNodeWithTag("ocr_result_text_value").performTouchInput { click(secondPosition) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_page_indicator").assertTextContains("2 / 2")
        composeRule.onNodeWithTag("ocr_region_text").assertTextContains("second")
        composeRule.onNodeWithTag("ocr_region_text").performTextReplacement("edited second")
        composeRule.waitForIdle()
        assertEquals("edited second", structured.assetFor(22L)?.recognition?.regions?.single()?.text)
        saveCapture(outputDirectory, "multi-asset-selected-dialog.png")
    }

    private fun saveCapture(directory: File, name: String) {
        val pixels = composeRule.onNodeWithTag("ocr_full_screen").captureToImage().toPixelMap()
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            for (x in 0 until pixels.width) {
                for (y in 0 until pixels.height) {
                    bitmap.setPixel(x, y, pixels[x, y].toArgb())
                }
            }
            File(directory, name).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun horizontalRecognition(): OcrRecognitionResult = OcrRecognitionResult(
        600,
        900,
        "raw",
        listOf(
            OcrTextRegion("TL", box(60f, 100f, 180f, 145f)),
            OcrTextRegion("BR", box(420f, 600f, 540f, 645f)),
            OcrTextRegion("BL", box(60f, 600f, 180f, 645f)),
            OcrTextRegion("TR", box(420f, 100f, 540f, 145f)),
        ),
    ).withReadingOrder()

    private fun verticalRecognition(): OcrRecognitionResult = OcrRecognitionResult(
        600,
        900,
        "raw",
        listOf(
            OcrTextRegion("R1", box(430f, 100f, 475f, 240f)),
            OcrTextRegion("R2", box(430f, 300f, 475f, 440f)),
            OcrTextRegion("L1", box(125f, 100f, 170f, 240f)),
            OcrTextRegion("L2", box(125f, 300f, 170f, 440f)),
        ),
    ).withReadingOrder()

    private fun postResult(clipId: Long, assetId: Long, recognition: OcrRecognitionResult) =
        OcrPostRecognitionResult(
            clipId = clipId,
            assets = listOf(OcrAssetRecognitionResult(assetId, "", recognition)),
            fullText = recognition.fullText,
        ).rebuildFromRegions()

    private fun createVisualImage(directory: File, name: String, title: String, boxes: List<VisualBox>): File {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(245, 240, 224))
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(55, 45, 35)
                textSize = 42f
            }
            canvas.drawText(title, 32f, 64f, titlePaint)
            boxes.forEach { box ->
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(255, 226, 120)
                    style = Paint.Style.FILL
                }
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(90, 70, 40)
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }
                val rect = RectF(box.left, box.top, box.right, box.bottom)
                canvas.drawRoundRect(rect, 16f, 16f, fill)
                canvas.drawRoundRect(rect, 16f, 16f, stroke)
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(45, 35, 25)
                    textSize = 28f
                }
                canvas.drawText(box.label, box.left + 12f, box.top + 32f, labelPaint)
            }
            val file = File(directory, name)
            file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrPolygon(
        listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )

    private data class VisualBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val label: String,
    )
}
