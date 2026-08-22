package com.lyco256.llm.data

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal enum class PaddleOcrModelVariant(
    val detectorAssetPath: String,
    val recognizerAssetPath: String,
    val recognitionConfigAssetPath: String,
) {
    SMALL(
        detectorAssetPath = "models/small/det/inference.onnx",
        recognizerAssetPath = "models/small/rec/inference.onnx",
        recognitionConfigAssetPath = "models/small/rec/inference.yml",
    ),
    MEDIUM(
        detectorAssetPath = "models/medium/det/inference.onnx",
        recognizerAssetPath = "models/medium/rec/inference.onnx",
        recognitionConfigAssetPath = "models/medium/rec/inference.yml",
    ),
}

internal data class PaddleOcrTile(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

internal enum class PaddleOcrCandidateSource { WHOLE_IMAGE, TILE }

internal data class PaddleOcrCandidate(
    val text: String,
    val polygon: OcrPolygon?,
    val confidence: Float?,
    val source: PaddleOcrCandidateSource,
    val edgeClipped: Boolean,
)

internal const val PADDLE_OCR_TILE_MAX_SIDE = 1536
internal const val PADDLE_OCR_TILE_OVERLAP = 256
internal const val PADDLE_OCR_TILE_SCALE_LONG_SIDE = 2048
internal const val PADDLE_OCR_TILE_EDGE_TOLERANCE = 32f
internal const val PADDLE_OCR_DUPLICATE_IOU_THRESHOLD = 0.50f
internal const val PADDLE_OCR_DUPLICATE_SMALLER_OVERLAP_THRESHOLD = 0.75f
internal const val PADDLE_OCR_DUPLICATE_AREA_RATIO_THRESHOLD = 2.0f
internal const val PADDLE_OCR_DUPLICATE_CENTER_DISTANCE_FACTOR = 0.75f
internal const val PADDLE_OCR_DUPLICATE_CONFIDENCE_MARGIN = 0.05f

internal fun buildPaddleOcrTiles(
    imageWidth: Int,
    imageHeight: Int,
    maxSide: Int = PADDLE_OCR_TILE_MAX_SIDE,
    overlap: Int = PADDLE_OCR_TILE_OVERLAP,
): List<PaddleOcrTile> {
    require(imageWidth > 0 && imageHeight > 0)
    require(maxSide > 0 && overlap in 0 until maxSide)
    val xRanges = tileRanges(imageWidth, maxSide, overlap)
    val yRanges = tileRanges(imageHeight, maxSide, overlap)
    return yRanges.flatMap { (top, height) ->
        xRanges.map { (left, width) -> PaddleOcrTile(left, top, width, height) }
    }
}

internal fun paddleOcrScaleForTile(tile: PaddleOcrTile, targetLongSide: Int = PADDLE_OCR_TILE_SCALE_LONG_SIDE): Float =
    if (max(tile.width, tile.height) >= targetLongSide) {
        1f
    } else {
        targetLongSide.toFloat() / max(tile.width, tile.height).toFloat()
    }

internal fun mapPaddleOcrTileCandidate(
    raw: PaddleOcrRawResult,
    tile: PaddleOcrTile,
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
): PaddleOcrCandidate? {
    val localPoints = raw.points.map { point ->
        OcrPoint(point.x / scale, point.y / scale)
    }
    val polygon = normalizeOcrPolygon(localPoints.map { point ->
        OcrPoint(
            x = (tile.left + point.x).coerceIn(0f, imageWidth.toFloat()),
            y = (tile.top + point.y).coerceIn(0f, imageHeight.toFloat()),
        )
    }) ?: return null
    return PaddleOcrCandidate(
        text = raw.text.trim(),
        polygon = polygon,
        confidence = raw.confidence,
        source = PaddleOcrCandidateSource.TILE,
        edgeClipped = isPaddleOcrTileEdgeClipped(
            localPoints = localPoints,
            tile = tile,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        ),
    ).takeIf { it.text.isNotBlank() }
}

internal fun isPaddleOcrTileEdgeClipped(
    localPoints: List<OcrPoint>,
    tile: PaddleOcrTile,
    imageWidth: Int,
    imageHeight: Int,
    tolerance: Float = PADDLE_OCR_TILE_EDGE_TOLERANCE,
): Boolean {
    val touchesInternalLeft = tile.left > 0 && localPoints.any { it.x <= tolerance }
    val touchesInternalTop = tile.top > 0 && localPoints.any { it.y <= tolerance }
    val touchesInternalRight = tile.right < imageWidth && localPoints.any { it.x >= tile.width - tolerance }
    val touchesInternalBottom = tile.bottom < imageHeight && localPoints.any { it.y >= tile.height - tolerance }
    return touchesInternalLeft || touchesInternalTop || touchesInternalRight || touchesInternalBottom
}

internal fun List<PaddleOcrCandidate>.integratePaddleOcrCandidates(): List<PaddleOcrCandidate> {
    val candidates = filter { it.text.isNotBlank() && (it.source != PaddleOcrCandidateSource.TILE || it.polygon != null) }
    if (candidates.size < 2) return candidates
    val parent = candidates.indices.toMutableList()

    fun find(index: Int): Int {
        var current = index
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
        }
        return current
    }

    fun union(left: Int, right: Int) {
        val leftRoot = find(left)
        val rightRoot = find(right)
        if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
    }

    for (left in candidates.indices) {
        for (right in left + 1 until candidates.size) {
            if (paddleOcrCandidatesOverlap(candidates[left], candidates[right])) union(left, right)
        }
    }

    return candidates.indices
        .groupBy(::find)
        .values
        .map { cluster -> cluster.map { candidates[it] }.minWith(::comparePaddleOcrRepresentatives) }
        .sortedWith(::comparePaddleOcrReadingOrder)
}

internal fun List<PaddleOcrCandidate>.toPaddleOcrRecognitionResult(
    imageWidth: Int,
    imageHeight: Int,
): OcrRecognitionResult {
    val regions = integratePaddleOcrCandidates().mapIndexed { index, candidate ->
        OcrTextRegion(
            text = candidate.text,
            polygon = candidate.polygon,
            confidence = candidate.confidence,
            precedingSeparator = if (index == 0) "" else "\n",
        )
    }
    return OcrRecognitionResult(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        fullText = regions.joinToString(separator = "") { it.precedingSeparator + it.text },
        regions = regions,
    )
}

internal fun List<PaddleOcrRawResult>.toPaddleOcrCandidates(
    source: PaddleOcrCandidateSource,
): List<PaddleOcrCandidate> = mapNotNull { raw ->
    val text = raw.text.trim()
    if (text.isBlank()) return@mapNotNull null
    PaddleOcrCandidate(
        text = text,
        polygon = normalizeOcrPolygon(raw.points),
        confidence = raw.confidence,
        source = source,
        edgeClipped = false,
    )
}

private fun tileRanges(size: Int, maxSide: Int, overlap: Int): List<Pair<Int, Int>> {
    if (size <= maxSide) return listOf(0 to size)
    val stride = maxSide - overlap
    val starts = mutableListOf(0)
    while (true) {
        val last = starts.last()
        if (last + maxSide >= size) break
        val next = min(last + stride, size - maxSide)
        if (next == last) break
        starts += next
    }
    return starts.distinct().map { it to min(maxSide, size - it) }
}

private fun paddleOcrCandidatesOverlap(left: PaddleOcrCandidate, right: PaddleOcrCandidate): Boolean {
    val leftPolygon = left.polygon ?: return false
    val rightPolygon = right.polygon ?: return false
    val leftArea = polygonArea(leftPolygon)
    val rightArea = polygonArea(rightPolygon)
    if (leftArea <= 0f || rightArea <= 0f) return false
    val areaRatio = max(leftArea, rightArea) / min(leftArea, rightArea)
    if (areaRatio > PADDLE_OCR_DUPLICATE_AREA_RATIO_THRESHOLD) return false
    val intersection = convexPolygonIntersectionArea(leftPolygon.points, rightPolygon.points)
    if (intersection <= 0f) return false
    val union = leftArea + rightArea - intersection
    val iou = if (union > 0f) intersection / union else 0f
    val smallerOverlap = intersection / min(leftArea, rightArea)
    if (iou < PADDLE_OCR_DUPLICATE_IOU_THRESHOLD &&
        smallerOverlap < PADDLE_OCR_DUPLICATE_SMALLER_OVERLAP_THRESHOLD
    ) return false
    val centerDistance = hypot(
        polygonCenterX(leftPolygon) - polygonCenterX(rightPolygon),
        polygonCenterY(leftPolygon) - polygonCenterY(rightPolygon),
    )
    val representativeShortSide = min(shortSide(leftPolygon), shortSide(rightPolygon))
    return representativeShortSide > 0f &&
        centerDistance <= representativeShortSide * PADDLE_OCR_DUPLICATE_CENTER_DISTANCE_FACTOR
}

private fun comparePaddleOcrRepresentatives(left: PaddleOcrCandidate, right: PaddleOcrCandidate): Int {
    if (left.edgeClipped != right.edgeClipped) return if (left.edgeClipped) 1 else -1
    val leftConfidence = left.confidence
    val rightConfidence = right.confidence
    if (leftConfidence != null && rightConfidence != null) {
        val difference = leftConfidence - rightConfidence
        if (abs(difference) >= PADDLE_OCR_DUPLICATE_CONFIDENCE_MARGIN) {
            return if (difference > 0f) -1 else 1
        }
        if (left.source != right.source) {
            return if (left.source == PaddleOcrCandidateSource.TILE) -1 else 1
        }
    } else if (leftConfidence != rightConfidence) {
        return if (leftConfidence != null) -1 else 1
    }
    val leftArea = left.polygon?.let(::polygonArea) ?: 0f
    val rightArea = right.polygon?.let(::polygonArea) ?: 0f
    if (leftArea != rightArea) return if (leftArea > rightArea) -1 else 1
    return paddleOcrStableKey(left).compareTo(paddleOcrStableKey(right))
}

private fun comparePaddleOcrReadingOrder(left: PaddleOcrCandidate, right: PaddleOcrCandidate): Int {
    val leftPolygon = left.polygon
    val rightPolygon = right.polygon
    if (leftPolygon == null || rightPolygon == null) {
        if (leftPolygon != rightPolygon) return if (leftPolygon == null) 1 else -1
        return paddleOcrStableKey(left).compareTo(paddleOcrStableKey(right))
    }
    val yDistance = abs(polygonCenterY(leftPolygon) - polygonCenterY(rightPolygon))
    if (yDistance > min(shortSide(leftPolygon), shortSide(rightPolygon)) * 0.5f) {
        val yDifference = polygonCenterY(leftPolygon).compareTo(polygonCenterY(rightPolygon))
        if (yDifference != 0) return yDifference
    }
    val xDifference = polygonCenterX(leftPolygon).compareTo(polygonCenterX(rightPolygon))
    if (xDifference != 0) return xDifference
    return paddleOcrStableKey(left).compareTo(paddleOcrStableKey(right))
}

private fun paddleOcrStableKey(candidate: PaddleOcrCandidate): String = buildString {
    append(candidate.source.ordinal).append('|')
    append(candidate.polygon?.let(::polygonCenterY) ?: Float.POSITIVE_INFINITY).append('|')
    append(candidate.polygon?.let(::polygonCenterX) ?: Float.POSITIVE_INFINITY).append('|')
    append(candidate.confidence ?: Float.NEGATIVE_INFINITY).append('|')
    append(candidate.text)
}

private fun polygonArea(polygon: OcrPolygon): Float = abs(polygon.points.indices.sumOf { index ->
    val current = polygon.points[index]
    val next = polygon.points[(index + 1) % polygon.points.size]
    (current.x * next.y - next.x * current.y).toDouble()
}.toFloat()) / 2f

private fun polygonSignedArea(points: List<OcrPoint>): Float = points.indices.sumOf { index ->
    val current = points[index]
    val next = points[(index + 1) % points.size]
    (current.x * next.y - next.x * current.y).toDouble()
}.toFloat() / 2f

private fun polygonCenterX(polygon: OcrPolygon): Float = polygon.points.map { it.x }.average().toFloat()
private fun polygonCenterY(polygon: OcrPolygon): Float = polygon.points.map { it.y }.average().toFloat()

private fun shortSide(polygon: OcrPolygon): Float = polygon.points.indices.minOf { index ->
    val current = polygon.points[index]
    val next = polygon.points[(index + 1) % polygon.points.size]
    hypot(current.x - next.x, current.y - next.y)
}

private fun convexPolygonIntersectionArea(left: List<OcrPoint>, right: List<OcrPoint>): Float {
    if (left.size < 3 || right.size < 3) return 0f
    var subject = left
    val orientation = if (polygonSignedArea(right) >= 0f) 1f else -1f
    right.indices.forEach { index ->
        if (subject.isEmpty()) return@forEach
        val clipStart = right[index]
        val clipEnd = right[(index + 1) % right.size]
        val output = mutableListOf<OcrPoint>()
        var previous = subject.last()
        var previousInside = cross(clipStart, clipEnd, previous) * orientation >= 0f
        subject.forEach { current ->
            val currentInside = cross(clipStart, clipEnd, current) * orientation >= 0f
            if (currentInside != previousInside) {
                lineIntersection(previous, current, clipStart, clipEnd)?.let(output::add)
            }
            if (currentInside) output += current
            previous = current
            previousInside = currentInside
        }
        subject = output
    }
    return if (subject.size >= 3) abs(polygonSignedArea(subject)) else 0f
}

private fun cross(a: OcrPoint, b: OcrPoint, point: OcrPoint): Float =
    (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)

private fun lineIntersection(
    firstStart: OcrPoint,
    firstEnd: OcrPoint,
    secondStart: OcrPoint,
    secondEnd: OcrPoint,
): OcrPoint? {
    val firstDeltaX = firstEnd.x - firstStart.x
    val firstDeltaY = firstEnd.y - firstStart.y
    val secondDeltaX = secondEnd.x - secondStart.x
    val secondDeltaY = secondEnd.y - secondStart.y
    val denominator = firstDeltaX * secondDeltaY - firstDeltaY * secondDeltaX
    if (abs(denominator) < 0.0001f) return null
    val offsetX = secondStart.x - firstStart.x
    val offsetY = secondStart.y - firstStart.y
    val factor = (offsetX * secondDeltaY - offsetY * secondDeltaX) / denominator
    return OcrPoint(firstStart.x + factor * firstDeltaX, firstStart.y + factor * firstDeltaY)
}
