package com.lyco256.llm.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class OcrRegionOrientation {
    HORIZONTAL,
    VERTICAL,
    AMBIGUOUS,
}

enum class OcrDominantOrientation {
    HORIZONTAL_DOMINANT,
    VERTICAL_DOMINANT,
}

data class OcrTextLayout(
    val dominantOrientation: OcrDominantOrientation,
    val orderedGroups: List<List<Int>>,
    val regionOrientations: Map<Int, OcrRegionOrientation>,
)

data class OcrRegionTextRange(
    val regionIndex: Int,
    val start: Int,
    val end: Int,
)

data class OcrPostRegionTextRange(
    val assetId: Long,
    val regionIndex: Int,
    val start: Int,
    val end: Int,
)

internal data class OcrStructuredText(
    val text: String,
    val regionRanges: List<OcrRegionTextRange>,
)

/** Named OCR layout thresholds kept in one place so the UI cannot drift from the recognizer contract. */
private object OcrReadingOrderThresholds {
    const val DIRECTION_RATIO = 1.20f
    const val MAX_REGION_SIZE_RATIO = 2.0f
    const val SAME_LINE_OVERLAP = 0.40f
    const val SAME_LINE_CENTER_SIZE = 0.70f
    const val SAME_LINE_GAP_SIZE = 2.0f
    const val ADJACENT_LINE_OVERLAP = 0.30f
    const val ADJACENT_LINE_CENTER_WIDTH = 0.45f
    const val ADJACENT_LINE_GAP_SIZE = 2.75f
    const val IMAGE_GAP_LIMIT = 0.10f
    const val MUTUAL_SCORE_TOLERANCE = 0.35f
}

private data class OcrRegionGeometry(
    val index: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val shortSide: Float,
    val orientation: OcrRegionOrientation,
)

private data class OcrGroupCandidate(
    val first: OcrRegionGeometry,
    val second: OcrRegionGeometry,
    val orientation: OcrRegionOrientation,
    val score: Float,
    val firstSide: Int,
    val secondSide: Int,
)

private data class OcrGroupComponent(
    val regions: MutableSet<Int>,
    val geometry: MutableMap<Int, OcrRegionGeometry>,
)

internal fun OcrRecognitionResult.withReadingOrder(): OcrRecognitionResult {
    if (regions.isEmpty()) return copy(textLayout = null, regionRanges = emptyList())
    val layout = buildOcrTextLayout(this)
    return copy(textLayout = layout).rebuildOcrText()
}

internal fun OcrRecognitionResult.rebuildOcrText(): OcrRecognitionResult {
    val layout = textLayout ?: return this
    val structured = buildOcrStructuredText(regions, layout)
    return copy(fullText = structured.text, regionRanges = structured.regionRanges)
}

internal fun OcrPostRecognitionResult.rebuildOcrPostText(): OcrPostRecognitionResult {
    val rebuiltAssets = assets.map { asset ->
        asset.copy(recognition = asset.recognition.rebuildOcrText())
    }
    val ranges = mutableListOf<OcrPostRegionTextRange>()
    val fullText = buildString {
        rebuiltAssets.forEach { asset ->
            val assetText = asset.recognition.fullText
            if (assetText.isBlank()) return@forEach
            if (isNotEmpty()) append("\n\n")
            val offset = length
            append(assetText)
            asset.recognition.regionRanges.forEach { range ->
                ranges += OcrPostRegionTextRange(
                    assetId = asset.assetId,
                    regionIndex = range.regionIndex,
                    start = range.start + offset,
                    end = range.end + offset,
                )
            }
        }
    }
    return copy(assets = rebuiltAssets, fullText = fullText, regionRanges = ranges)
}

internal fun buildOcrStructuredText(
    regions: List<OcrTextRegion>,
    layout: OcrTextLayout,
): OcrStructuredText {
    val emittedRanges = mutableListOf<OcrRegionTextRange>()
    val text = buildString {
        var emittedGroup = false
        layout.orderedGroups.forEach { group ->
            val nonBlank = group.mapNotNull { index ->
                val region = regions.getOrNull(index) ?: return@mapNotNull null
                val value = region.text.trim()
                value.takeIf(String::isNotEmpty)?.let { index to it }
            }
            if (nonBlank.isEmpty()) return@forEach
            if (emittedGroup) append("\n\n")
            emittedGroup = true
            var previousText: String? = null
            nonBlank.forEach { (index, value) ->
                val separator = previousText?.let { previous -> ocrRegionSeparator(previous, value) }.orEmpty()
                append(separator)
                val start = length
                append(value)
                emittedRanges += OcrRegionTextRange(regionIndex = index, start = start, end = length)
                previousText = value
            }
        }
    }
    return OcrStructuredText(text = text, regionRanges = emittedRanges)
}

private fun ocrRegionSeparator(previous: String, next: String): String {
    val previousLast = previous.lastOrNull() ?: return ""
    val nextFirst = next.firstOrNull() ?: return ""
    if (previousLast.isWhitespace() || nextFirst.isWhitespace()) return ""
    return if (isLatinOrDigit(previousLast) && isLatinOrDigit(nextFirst)) " " else ""
}

private fun isLatinOrDigit(value: Char): Boolean =
    value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9'

private fun buildOcrTextLayout(result: OcrRecognitionResult): OcrTextLayout {
    val geometries = result.regions.mapIndexedNotNull { index, region ->
        region.polygon?.let { polygon ->
            polygonGeometry(index, polygon)
        }
    }
    val horizontalCount = geometries.count { it.orientation == OcrRegionOrientation.HORIZONTAL }
    val verticalCount = geometries.count { it.orientation == OcrRegionOrientation.VERTICAL }
    val dominant = if (verticalCount > horizontalCount) {
        OcrDominantOrientation.VERTICAL_DOMINANT
    } else {
        OcrDominantOrientation.HORIZONTAL_DOMINANT
    }
    val orientations = result.regions.indices.associateWith { index ->
        geometries.firstOrNull { it.index == index }?.orientation ?: OcrRegionOrientation.AMBIGUOUS
    }
    if (geometries.isEmpty()) {
        return OcrTextLayout(
            dominantOrientation = dominant,
            orderedGroups = result.regions.indices.map { listOf(it) },
            regionOrientations = orientations,
        )
    }

    val imageSize = OcrImageSize(result.imageWidth.toFloat(), result.imageHeight.toFloat())
    val representativeSize = geometries
        .filter { it.orientation != OcrRegionOrientation.AMBIGUOUS }
        .map { it.shortSide }
        .medianOrNull()
        ?: geometries.map { it.shortSide }.medianOrNull()
        ?: 1f
    val candidates = geometries.flatMapIndexed { firstPosition, first ->
        geometries.drop(firstPosition + 1).mapNotNull { second ->
            createCandidate(first, second, dominant, imageSize, representativeSize, geometries)
        }
    }
    val bestOrientationForAmbiguous = candidates
        .flatMap { candidate ->
            buildList {
                if (candidate.first.orientation == OcrRegionOrientation.AMBIGUOUS) add(candidate.first.index to candidate)
                if (candidate.second.orientation == OcrRegionOrientation.AMBIGUOUS) add(candidate.second.index to candidate)
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.minByOrNull(OcrGroupCandidate::score)?.orientation }
    val directionFilteredCandidates = candidates.filter { candidate ->
        (candidate.first.orientation != OcrRegionOrientation.AMBIGUOUS || bestOrientationForAmbiguous[candidate.first.index] == candidate.orientation) &&
            (candidate.second.orientation != OcrRegionOrientation.AMBIGUOUS || bestOrientationForAmbiguous[candidate.second.index] == candidate.orientation)
    }
    val bestBySide = directionFilteredCandidates
        .flatMap { candidate ->
            listOf(
                (candidate.first.index to candidate.firstSide) to candidate,
                (candidate.second.index to candidate.secondSide) to candidate,
            )
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.minByOrNull(OcrGroupCandidate::score) }

    val sortedCandidates = directionFilteredCandidates.sortedBy(OcrGroupCandidate::score)
    val components = geometries.associate {
        it.index to OcrGroupComponent(mutableSetOf(it.index), mutableMapOf(it.index to it))
    }.toMutableMap()
    sortedCandidates.forEach { candidate ->
        val firstBest = bestBySide[candidate.first.index to candidate.firstSide]
        val secondBest = bestBySide[candidate.second.index to candidate.secondSide]
        val firstCompetitive = candidate.score <= (firstBest?.score ?: Float.POSITIVE_INFINITY) + OcrReadingOrderThresholds.MUTUAL_SCORE_TOLERANCE
        val secondCompetitive = candidate.score <= (secondBest?.score ?: Float.POSITIVE_INFINITY) + OcrReadingOrderThresholds.MUTUAL_SCORE_TOLERANCE
        val firstIsBest = firstBest === candidate
        val secondIsBest = secondBest === candidate
        if ((!firstIsBest && !secondIsBest) || !firstCompetitive || !secondCompetitive) return@forEach
        val firstComponent = components[candidate.first.index] ?: return@forEach
        val secondComponent = components[candidate.second.index] ?: return@forEach
        if (firstComponent === secondComponent) return@forEach
        if (!componentCanAttach(firstComponent, candidate.first, candidate.orientation, candidate.firstSide) ||
            !componentCanAttach(secondComponent, candidate.second, candidate.orientation, candidate.secondSide)
        ) return@forEach
        val merged = firstComponent.regions + secondComponent.regions
        val target = OcrGroupComponent(
            regions = merged.toMutableSet(),
            geometry = (firstComponent.geometry + secondComponent.geometry).toMutableMap(),
        )
        merged.forEach { components[it] = target }
    }

    val groups = components.values.distinct().map { component ->
        orderRegionsWithinGroup(
            component.regions.mapNotNull { index -> geometries.firstOrNull { it.index == index } },
            dominant,
        )
    }.toMutableList()
    attachPolygonlessRegions(result.regions, groups, geometries)
    val orderedGroups = orderGroups(groups, geometries, dominant)
    return OcrTextLayout(dominant, orderedGroups, orientations)
}

private fun componentCanAttach(
    component: OcrGroupComponent,
    endpoint: OcrRegionGeometry,
    orientation: OcrRegionOrientation,
    side: Int,
): Boolean {
    val sameLine = abs(side) == 1
    val direction = if (side < 0) -1f else 1f
    return component.regions.none { memberIndex ->
        if (memberIndex == endpoint.index) return@none false
        val member = component.geometry[memberIndex] ?: return@none false
        val endpointAxis = if (orientation == OcrRegionOrientation.HORIZONTAL) {
            if (sameLine) endpoint.centerX else endpoint.centerY
        } else {
            if (sameLine) endpoint.centerY else endpoint.centerX
        }
        val memberAxis = if (orientation == OcrRegionOrientation.HORIZONTAL) {
            if (sameLine) member.centerX else member.centerY
        } else {
            if (sameLine) member.centerY else member.centerX
        }
        direction * (memberAxis - endpointAxis) > 0.01f
    }
}

private data class OcrImageSize(val width: Float, val height: Float)

private fun polygonGeometry(index: Int, polygon: OcrPolygon): OcrRegionGeometry? {
    val points = polygon.points
    if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val area = abs(points.indices.sumOf { pointIndex ->
        val next = points[(pointIndex + 1) % points.size]
        (points[pointIndex].x * next.y - next.x * points[pointIndex].y).toDouble()
    }.toFloat() / 2f)
    if (area <= 0.5f) return null
    val left = points.minOf { it.x }
    val top = points.minOf { it.y }
    val right = points.maxOf { it.x }
    val bottom = points.maxOf { it.y }
    val width = right - left
    val height = bottom - top
    if (width <= 0f || height <= 0f) return null
    val horizontalSide = (distance(points[0], points[1]) + distance(points[2], points[3])) / 2f
    val verticalSide = (distance(points[1], points[2]) + distance(points[3], points[0])) / 2f
    if (horizontalSide <= 0f || verticalSide <= 0f) return null
    val orientation = when {
        horizontalSide >= verticalSide * OcrReadingOrderThresholds.DIRECTION_RATIO -> OcrRegionOrientation.HORIZONTAL
        verticalSide >= horizontalSide * OcrReadingOrderThresholds.DIRECTION_RATIO -> OcrRegionOrientation.VERTICAL
        else -> OcrRegionOrientation.AMBIGUOUS
    }
    return OcrRegionGeometry(
        index = index,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        centerX = (left + right) / 2f,
        centerY = (top + bottom) / 2f,
        width = width,
        height = height,
        shortSide = min(horizontalSide, verticalSide),
        orientation = orientation,
    )
}

private fun distance(first: OcrPoint, second: OcrPoint): Float =
    kotlin.math.hypot(second.x - first.x, second.y - first.y)

private fun createCandidate(
    first: OcrRegionGeometry,
    second: OcrRegionGeometry,
    dominant: OcrDominantOrientation,
    imageSize: OcrImageSize,
    representativeSize: Float,
    allRegions: List<OcrRegionGeometry>,
): OcrGroupCandidate? {
    val orientation = compatibleOrientation(first.orientation, second.orientation, dominant) ?: return null
    val localSize = median(first.shortSide, second.shortSide).coerceAtLeast(representativeSize * 0.1f)
    val sizeRatio = max(first.shortSide, second.shortSide) / min(first.shortSide, second.shortSide).coerceAtLeast(0.01f)
    if (sizeRatio > OcrReadingOrderThresholds.MAX_REGION_SIZE_RATIO) return null
    val horizontal = orientation == OcrRegionOrientation.HORIZONTAL
    val primaryGap = if (horizontal) axisGap(first.left, first.right, second.left, second.right) else axisGap(first.top, first.bottom, second.top, second.bottom)
    val secondaryGap = if (horizontal) axisGap(first.top, first.bottom, second.top, second.bottom) else axisGap(first.left, first.right, second.left, second.right)
    val sameLine = if (horizontal) {
        projectionOverlap(first.top, first.bottom, second.top, second.bottom) >= min(first.height, second.height) * OcrReadingOrderThresholds.SAME_LINE_OVERLAP ||
            abs(first.centerY - second.centerY) <= localSize * OcrReadingOrderThresholds.SAME_LINE_CENTER_SIZE
    } else {
        projectionOverlap(first.left, first.right, second.left, second.right) >= min(first.width, second.width) * OcrReadingOrderThresholds.SAME_LINE_OVERLAP ||
            abs(first.centerX - second.centerX) <= localSize * OcrReadingOrderThresholds.SAME_LINE_CENTER_SIZE
    }
    val sameLineLimit = min(
        localSize * OcrReadingOrderThresholds.SAME_LINE_GAP_SIZE,
        if (horizontal) imageSize.width * OcrReadingOrderThresholds.IMAGE_GAP_LIMIT else imageSize.height * OcrReadingOrderThresholds.IMAGE_GAP_LIMIT,
    )
    if (sameLine && primaryGap <= sameLineLimit && !hasInterveningRegion(first, second, orientation, allRegions)) {
        val overlap = if (horizontal) {
            projectionOverlap(first.top, first.bottom, second.top, second.bottom) / min(first.height, second.height)
        } else {
            projectionOverlap(first.left, first.right, second.left, second.right) / min(first.width, second.width)
        }.coerceIn(0f, 1f)
        val centerDelta = if (horizontal) abs(first.centerY - second.centerY) else abs(first.centerX - second.centerX)
        return OcrGroupCandidate(
            first = first,
            second = second,
            orientation = orientation,
            score = primaryGap / localSize + (1f - overlap) + centerDelta / localSize * 0.4f + (sizeRatio - 1f) * 0.4f,
            firstSide = sideFor(first, second, orientation, sameLine = true),
            secondSide = sideFor(second, first, orientation, sameLine = true),
        )
    }
    val adjacentGapLimit = min(
        localSize * OcrReadingOrderThresholds.ADJACENT_LINE_GAP_SIZE,
        if (horizontal) imageSize.height * OcrReadingOrderThresholds.IMAGE_GAP_LIMIT else imageSize.width * OcrReadingOrderThresholds.IMAGE_GAP_LIMIT,
    )
    if (secondaryGap > adjacentGapLimit) return null
    val aligned = if (horizontal) {
        projectionOverlap(first.left, first.right, second.left, second.right) >= min(first.width, second.width) * OcrReadingOrderThresholds.ADJACENT_LINE_OVERLAP ||
            abs(first.centerX - second.centerX) <= max(first.width, second.width) * OcrReadingOrderThresholds.ADJACENT_LINE_CENTER_WIDTH
    } else {
        projectionOverlap(first.top, first.bottom, second.top, second.bottom) >= min(first.height, second.height) * OcrReadingOrderThresholds.ADJACENT_LINE_OVERLAP ||
            abs(first.centerY - second.centerY) <= max(first.height, second.height) * OcrReadingOrderThresholds.ADJACENT_LINE_CENTER_WIDTH
    }
    if (!aligned || hasInterveningRegion(first, second, orientation, allRegions)) return null
    val overlap = if (horizontal) {
        projectionOverlap(first.left, first.right, second.left, second.right) / min(first.width, second.width)
    } else {
        projectionOverlap(first.top, first.bottom, second.top, second.bottom) / min(first.height, second.height)
    }.coerceIn(0f, 1f)
    val centerDelta = if (horizontal) abs(first.centerX - second.centerX) else abs(first.centerY - second.centerY)
    return OcrGroupCandidate(
        first = first,
        second = second,
        orientation = orientation,
        score = secondaryGap / localSize + (1f - overlap) + centerDelta / localSize * 0.4f + (sizeRatio - 1f) * 0.4f + 0.25f,
        firstSide = sideFor(first, second, orientation, sameLine = false),
        secondSide = sideFor(second, first, orientation, sameLine = false),
    )
}

private fun compatibleOrientation(
    first: OcrRegionOrientation,
    second: OcrRegionOrientation,
    dominant: OcrDominantOrientation,
): OcrRegionOrientation? {
    if (first != OcrRegionOrientation.AMBIGUOUS && second != OcrRegionOrientation.AMBIGUOUS && first != second) return null
    if (first == second && first != OcrRegionOrientation.AMBIGUOUS) return first
    if (first != OcrRegionOrientation.AMBIGUOUS) return first
    if (second != OcrRegionOrientation.AMBIGUOUS) return second
    return if (dominant == OcrDominantOrientation.HORIZONTAL_DOMINANT) OcrRegionOrientation.HORIZONTAL else OcrRegionOrientation.VERTICAL
}

private fun sideFor(
    first: OcrRegionGeometry,
    second: OcrRegionGeometry,
    orientation: OcrRegionOrientation,
    sameLine: Boolean,
): Int {
    val delta = when {
        orientation == OcrRegionOrientation.HORIZONTAL && sameLine -> second.centerX - first.centerX
        orientation == OcrRegionOrientation.HORIZONTAL -> second.centerY - first.centerY
        orientation == OcrRegionOrientation.VERTICAL && sameLine -> second.centerY - first.centerY
        else -> second.centerX - first.centerX
    }
    val sign = if (delta < 0f) -1 else 1
    return sign * if (sameLine) 1 else 2
}

private fun hasInterveningRegion(
    first: OcrRegionGeometry,
    second: OcrRegionGeometry,
    orientation: OcrRegionOrientation,
    allRegions: List<OcrRegionGeometry>,
): Boolean {
    if (allRegions.isEmpty()) return false
    val horizontal = orientation == OcrRegionOrientation.HORIZONTAL
    return allRegions.any { middle ->
        if (middle.index == first.index || middle.index == second.index) return@any false
        if (middle.orientation != OcrRegionOrientation.AMBIGUOUS && middle.orientation != orientation) return@any false
        val primaryBetween = if (horizontal) {
            middle.centerX > min(first.centerX, second.centerX) && middle.centerX < max(first.centerX, second.centerX) &&
                projectionOverlap(first.top, first.bottom, middle.top, middle.bottom) > 0f
        } else {
            middle.centerY > min(first.centerY, second.centerY) && middle.centerY < max(first.centerY, second.centerY) &&
                projectionOverlap(first.left, first.right, middle.left, middle.right) > 0f
        }
        primaryBetween
    }
}

private fun axisGap(firstStart: Float, firstEnd: Float, secondStart: Float, secondEnd: Float): Float =
    max(0f, max(firstStart, secondStart) - min(firstEnd, secondEnd))

private fun projectionOverlap(firstStart: Float, firstEnd: Float, secondStart: Float, secondEnd: Float): Float =
    max(0f, min(firstEnd, secondEnd) - max(firstStart, secondStart))

private fun median(first: Float, second: Float): Float = (first + second) / 2f

private fun List<Float>.medianOrNull(): Float? {
    if (isEmpty()) return null
    val sorted = sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else median(sorted[sorted.size / 2 - 1], sorted[sorted.size / 2])
}

private fun orderRegionsWithinGroup(
    regions: List<OcrRegionGeometry>,
    dominant: OcrDominantOrientation,
): List<Int> {
    if (regions.isEmpty()) return emptyList()
    val horizontalCount = regions.count { it.orientation == OcrRegionOrientation.HORIZONTAL }
    val verticalCount = regions.count { it.orientation == OcrRegionOrientation.VERTICAL }
    val orientation = when {
        horizontalCount > verticalCount -> OcrRegionOrientation.HORIZONTAL
        verticalCount > horizontalCount -> OcrRegionOrientation.VERTICAL
        dominant == OcrDominantOrientation.HORIZONTAL_DOMINANT -> OcrRegionOrientation.HORIZONTAL
        else -> OcrRegionOrientation.VERTICAL
    }
    val bands = mutableListOf<MutableList<OcrRegionGeometry>>()
    val ordered = if (orientation == OcrRegionOrientation.HORIZONTAL) regions.sortedBy { it.top } else regions.sortedByDescending { it.right }
    ordered.forEach { region ->
        val band = bands.firstOrNull { existing ->
            val representative = existing.map { if (orientation == OcrRegionOrientation.HORIZONTAL) it.centerY else it.centerX }.average().toFloat()
            val size = existing.map { it.shortSide }.medianOrNull() ?: region.shortSide
            if (orientation == OcrRegionOrientation.HORIZONTAL) {
                projectionOverlap(existing.minOf { it.top }, existing.maxOf { it.bottom }, region.top, region.bottom) >= min(existing.minOf { it.height }, region.height) * OcrReadingOrderThresholds.SAME_LINE_OVERLAP ||
                    abs(representative - region.centerY) <= size * OcrReadingOrderThresholds.SAME_LINE_CENTER_SIZE
            } else {
                projectionOverlap(existing.minOf { it.left }, existing.maxOf { it.right }, region.left, region.right) >= min(existing.minOf { it.width }, region.width) * OcrReadingOrderThresholds.SAME_LINE_OVERLAP ||
                    abs(representative - region.centerX) <= size * OcrReadingOrderThresholds.SAME_LINE_CENTER_SIZE
            }
        }
        if (band == null) bands += mutableListOf(region) else band += region
    }
    return bands
        .sortedWith(compareBy<MutableList<OcrRegionGeometry>> { if (orientation == OcrRegionOrientation.HORIZONTAL) it.minOf(OcrRegionGeometry::top) else -it.maxOf(OcrRegionGeometry::right) })
        .flatMap { band ->
            if (orientation == OcrRegionOrientation.HORIZONTAL) band.sortedBy(OcrRegionGeometry::left) else band.sortedBy(OcrRegionGeometry::top)
        }
        .map(OcrRegionGeometry::index)
}

private fun orderGroups(
    groups: List<List<Int>>,
    geometries: List<OcrRegionGeometry>,
    dominant: OcrDominantOrientation,
): List<List<Int>> {
    val byIndex = geometries.associateBy(OcrRegionGeometry::index)
    data class GroupBox(val group: List<Int>, val left: Float, val top: Float, val right: Float, val bottom: Float)
    val boxes = groups.map { group ->
        val members = group.mapNotNull(byIndex::get)
        GroupBox(group, members.minOfOrNull { it.left } ?: Float.MAX_VALUE, members.minOfOrNull { it.top } ?: Float.MAX_VALUE, members.maxOfOrNull { it.right } ?: Float.MIN_VALUE, members.maxOfOrNull { it.bottom } ?: Float.MIN_VALUE)
    }.sortedBy { it.top }
    val bands = mutableListOf<MutableList<GroupBox>>()
    boxes.forEach { box ->
        val band = bands.firstOrNull { existing ->
            val top = existing.minOf { it.top }
            val bottom = existing.maxOf { it.bottom }
            val overlap = projectionOverlap(top, bottom, box.top, box.bottom)
            val center = (top + bottom) / 2f
            val boxCenter = (box.top + box.bottom) / 2f
            overlap >= min(bottom - top, box.bottom - box.top) * OcrReadingOrderThresholds.SAME_LINE_OVERLAP ||
                abs(center - boxCenter) <= max(bottom - top, box.bottom - box.top) * OcrReadingOrderThresholds.SAME_LINE_CENTER_SIZE
        }
        if (band == null) bands += mutableListOf(box) else band += box
    }
    return bands.flatMap { band ->
        if (dominant == OcrDominantOrientation.HORIZONTAL_DOMINANT) band.sortedBy { it.left }
        else band.sortedByDescending { it.right }
    }.map(GroupBox::group)
}

private fun attachPolygonlessRegions(
    regions: List<OcrTextRegion>,
    groups: MutableList<List<Int>>,
    geometries: List<OcrRegionGeometry>,
) {
    if (geometries.isEmpty()) return
    val validIndices = geometries.map { it.index }.toSet()
    val groupByIndex = groups.flatMapIndexed { groupIndex, group -> group.map { it to groupIndex } }.toMap().toMutableMap()
    regions.indices.filterNot(validIndices::contains).forEach { index ->
        val previous = (index - 1 downTo 0).firstOrNull(validIndices::contains)
        val next = (index + 1 until regions.size).firstOrNull(validIndices::contains)
        val anchor = previous ?: next ?: return@forEach
        val groupIndex = groupByIndex[anchor] ?: return@forEach
        val current = groups[groupIndex].toMutableList()
        if (previous != null) current += index else current.add(0, index)
        groups[groupIndex] = current
        groupByIndex[index] = groupIndex
    }
}
