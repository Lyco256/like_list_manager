package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class OcrTextBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
}

internal data class OcrTextLineCandidate(
    val text: String,
    val bounds: OcrTextBounds?,
    val order: Int,
)

internal data class OcrTextBlockCandidate(
    val lines: List<OcrTextLineCandidate>,
    val bounds: OcrTextBounds?,
    val order: Int,
)

interface OcrTextGateway {
    suspend fun recognize(bitmap: Bitmap): String
}

class MlKitOcrTextGateway : OcrTextGateway {
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    override suspend fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()
        return result.toFormattedText(isVerticalImage = bitmap.height > bitmap.width)
    }
}

class FakeOcrTextGateway(
    private val recognizer: (Bitmap) -> String = { "Fake OCR" },
) : OcrTextGateway {
    override suspend fun recognize(bitmap: Bitmap): String = recognizer(bitmap)
}

internal fun Text.toFormattedText(isVerticalImage: Boolean): String {
    val blocks = textBlocks.mapIndexedNotNull { blockIndex, block ->
        val blockBounds = block.boundingBox?.toBounds()
        val lines = block.lines.mapIndexedNotNull { lineIndex, line ->
            val text = line.text.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            OcrTextLineCandidate(
                text = text,
                bounds = line.boundingBox?.toBounds(),
                order = lineIndex,
            )
        }
        if (lines.isEmpty() && block.lines.all { it.text.isBlank() }) return@mapIndexedNotNull null
        OcrTextBlockCandidate(lines = lines, bounds = blockBounds, order = blockIndex)
    }
    return if (blocks.isEmpty()) {
        text.trim()
    } else {
        formatOcrText(blocks, isVerticalImage = isVerticalImage)
    }
}

internal fun formatOcrText(
    blocks: List<OcrTextBlockCandidate>,
    isVerticalImage: Boolean,
): String {
    if (blocks.isEmpty()) return ""
    val sortedBlocks = blocks.sortedWith(
        compareBy<OcrTextBlockCandidate> { it.sortCategory(isVerticalImage) }
            .thenBy { it.sortPosition(isVerticalImage) }
            .thenBy { it.order },
    )
    return sortedBlocks.mapNotNull { block ->
        val sortedLines = block.lines.sortedWith(
            compareBy<OcrTextLineCandidate> { it.sortCategory(block, isVerticalImage) }
                .thenBy { it.sortPosition(block, isVerticalImage) }
                .thenBy { it.order },
        )
        sortedLines.mapNotNull { it.text.trim().takeIf(String::isNotBlank) }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }.joinToString("\n\n")
}

private fun OcrTextBlockCandidate.sortCategory(isVerticalImage: Boolean): Int = when {
    bounds == null -> 1
    isVerticalBlock() && isVerticalImage -> 0
    isHorizontalBlock() && !isVerticalImage -> 0
    isMiddleBlock() -> 1
    else -> 2
}

private fun OcrTextBlockCandidate.sortPosition(isVerticalImage: Boolean): Int = bounds?.let { box ->
    if (isVerticalImage) box.top * 10_000 + box.left else box.centerX * 10_000 + box.top
} ?: order

private fun OcrTextLineCandidate.sortCategory(block: OcrTextBlockCandidate, isVerticalImage: Boolean): Int = when {
    bounds == null -> 1
    else -> 0
}

private fun OcrTextLineCandidate.sortPosition(block: OcrTextBlockCandidate, isVerticalImage: Boolean): Int = bounds?.let { box ->
    when {
        block.isVerticalBlock() -> box.top * 10_000 + box.left
        block.isHorizontalBlock() -> box.centerX * 10_000 + box.top
        isVerticalImage -> box.top * 10_000 + box.left
        else -> box.centerX * 10_000 + box.top
    }
} ?: order

private fun OcrTextBlockCandidate.isVerticalBlock(): Boolean = bounds?.let {
    it.height >= it.width * 1.25
} == true

private fun OcrTextBlockCandidate.isHorizontalBlock(): Boolean = bounds?.let {
    it.width >= it.height * 1.15
} == true

private fun OcrTextBlockCandidate.isMiddleBlock(): Boolean = !isVerticalBlock() && !isHorizontalBlock()

private fun Rect.toBounds(): OcrTextBounds = OcrTextBounds(left, top, right, bottom)

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener {
        continuation.cancel()
    }
}
