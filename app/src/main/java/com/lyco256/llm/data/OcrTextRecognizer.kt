package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.Point

enum class OcrQualityMode(val displayName: String) {
    FAST("高速"),
    ACCURATE("高精度"),
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
        internal fun fromCornerPoints(points: Array<Point>): OcrPolygon? =
            normalizeOcrPolygon(points.map { OcrPoint(it.x.toFloat(), it.y.toFloat()) })
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

interface OcrTextGateway {
    suspend fun recognize(
        bitmap: Bitmap,
        mode: OcrQualityMode = OcrQualityMode.FAST,
    ): OcrRecognitionResult
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
    override suspend fun recognize(bitmap: Bitmap, mode: OcrQualityMode): OcrRecognitionResult =
        recognizer(bitmap)
}

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
