package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.Point
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

data class OcrPoint(
    val x: Float,
    val y: Float,
)

data class OcrPolygon(
    val points: List<OcrPoint>,
) {
    init {
        require(points.size == 4) { "OCR polygon must contain exactly four points" }
    }

    companion object {
        internal fun fromCornerPoints(points: Array<Point>): OcrPolygon? {
            if (points.size != 4) return null
            val converted = points.map { OcrPoint(it.x.toFloat(), it.y.toFloat()) }
            return normalizeOcrPolygon(converted)
        }

        internal fun fromBounds(bounds: OcrTextBounds): OcrPolygon = OcrPolygon(
            listOf(
                OcrPoint(bounds.left.toFloat(), bounds.top.toFloat()),
                OcrPoint(bounds.right.toFloat(), bounds.top.toFloat()),
                OcrPoint(bounds.right.toFloat(), bounds.bottom.toFloat()),
                OcrPoint(bounds.left.toFloat(), bounds.bottom.toFloat()),
            ),
        )
    }
}

internal fun normalizeOcrPolygon(points: List<OcrPoint>): OcrPolygon? {
    if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    return OcrPolygon(points.sortedClockwiseFromTopLeft())
}

data class OcrTextRegion(
    val text: String,
    val polygon: OcrPolygon? = null,
    val confidence: Float? = null,
    val precedingSeparator: String = "",
)

data class OcrRecognitionResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val fullText: String,
    val regions: List<OcrTextRegion> = emptyList(),
)

enum class OcrEngine(val displayName: String) {
    ML_KIT("ML Kit"),
    PP_OCRV6_SMALL("PP-OCRv6 small"),
}

data class OcrDetectionMetadata(
    val engine: OcrEngine,
    val elapsedMs: Long,
)

internal data class OcrTextLineCandidate(
    val text: String,
    val bounds: OcrTextBounds?,
    val order: Int,
    val polygon: OcrPolygon? = null,
    val confidence: Float? = null,
)

internal data class OcrTextBlockCandidate(
    val lines: List<OcrTextLineCandidate>,
    val bounds: OcrTextBounds?,
    val order: Int,
)

interface OcrTextGateway {
    suspend fun recognize(bitmap: Bitmap): OcrRecognitionResult
}

class MlKitOcrTextGateway : OcrTextGateway {
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    override suspend fun recognize(bitmap: Bitmap): OcrRecognitionResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()
        return result.toOcrRecognitionResult(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            isVerticalImage = bitmap.height > bitmap.width,
        )
    }
}

class FakeOcrTextGateway(
    private val recognizer: (Bitmap) -> OcrRecognitionResult = { bitmap ->
        OcrRecognitionResult(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            fullText = "Fake OCR",
        )
    },
) : OcrTextGateway {
    override suspend fun recognize(bitmap: Bitmap): OcrRecognitionResult = recognizer(bitmap)
}

internal fun Text.toOcrRecognitionResult(
    imageWidth: Int,
    imageHeight: Int,
    isVerticalImage: Boolean,
): OcrRecognitionResult {
    val blocks = textBlocks.mapIndexedNotNull { blockIndex, block ->
        val blockBounds = block.boundingBox?.toBounds()
        val lines = block.lines.mapIndexedNotNull { lineIndex, line ->
            val text = line.text.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            val bounds = line.boundingBox?.toBounds()
            OcrTextLineCandidate(
                text = text,
                bounds = bounds,
                order = lineIndex,
                polygon = line.cornerPoints?.let(OcrPolygon::fromCornerPoints)
                    ?: bounds?.let(OcrPolygon::fromBounds),
                confidence = line.confidence,
            )
        }
        if (lines.isEmpty() && block.lines.all { it.text.isBlank() }) return@mapIndexedNotNull null
        OcrTextBlockCandidate(lines = lines, bounds = blockBounds, order = blockIndex)
    }
    return buildOcrRecognitionResult(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        fallbackText = text.trim(),
        blocks = blocks,
        isVerticalImage = isVerticalImage,
    )
}

internal fun buildOcrRecognitionResult(
    imageWidth: Int,
    imageHeight: Int,
    fallbackText: String,
    blocks: List<OcrTextBlockCandidate>,
    isVerticalImage: Boolean,
): OcrRecognitionResult {
    if (blocks.isEmpty()) {
        return OcrRecognitionResult(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            fullText = fallbackText,
            regions = fallbackText.takeIf(String::isNotBlank)?.let {
                listOf(OcrTextRegion(text = it))
            }.orEmpty(),
        )
    }
    val regions = formatOcrRegions(blocks, isVerticalImage = isVerticalImage)
    return OcrRecognitionResult(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        fullText = regions.joinToString(separator = "") { it.precedingSeparator + it.text },
        regions = regions,
    )
}

internal fun Text.toFormattedText(isVerticalImage: Boolean): String = toOcrRecognitionResult(
    imageWidth = 0,
    imageHeight = 0,
    isVerticalImage = isVerticalImage,
).fullText

internal fun formatOcrRegions(
    blocks: List<OcrTextBlockCandidate>,
    isVerticalImage: Boolean,
): List<OcrTextRegion> {
    if (blocks.isEmpty()) return emptyList()
    val sortedBlocks = blocks.sortedWith(
        compareBy<OcrTextBlockCandidate> { it.sortCategory(isVerticalImage) }
            .thenBy { it.sortPosition(isVerticalImage) }
            .thenBy { it.order },
    )
    return buildList {
        sortedBlocks.forEachIndexed { blockIndex, block ->
            val sortedLines = block.lines.sortedWith(
                compareBy<OcrTextLineCandidate> { it.sortCategory(block, isVerticalImage) }
                    .thenBy { it.sortPosition(block, isVerticalImage) }
                    .thenBy { it.order },
            )
            var emittedLineIndex = 0
            sortedLines.forEach { line ->
                val normalizedText = line.text.trim().takeIf(String::isNotBlank) ?: return@forEach
                add(
                    OcrTextRegion(
                        text = normalizedText,
                        polygon = line.polygon,
                        confidence = line.confidence,
                        precedingSeparator = when {
                            isEmpty() -> ""
                            emittedLineIndex == 0 && blockIndex > 0 -> "\n\n"
                            else -> "\n"
                        },
                    ),
                )
                emittedLineIndex += 1
            }
        }
    }
}

internal fun formatOcrText(
    blocks: List<OcrTextBlockCandidate>,
    isVerticalImage: Boolean,
): String = formatOcrRegions(blocks, isVerticalImage)
    .joinToString(separator = "") { it.precedingSeparator + it.text }

private fun List<OcrPoint>.sortedClockwiseFromTopLeft(): List<OcrPoint> {
    val centerX = averageOf { it.x }
    val centerY = averageOf { it.y }
    val clockwise = sortedBy { point -> kotlin.math.atan2(point.y - centerY, point.x - centerX) }
    val topLeftIndex = clockwise.indices.minBy { index ->
        clockwise[index].x + clockwise[index].y
    }
    return clockwise.drop(topLeftIndex) + clockwise.take(topLeftIndex)
}

private fun List<OcrPoint>.averageOf(selector: (OcrPoint) -> Float): Float =
    sumOf { selector(it).toDouble() }.toFloat() / size

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
