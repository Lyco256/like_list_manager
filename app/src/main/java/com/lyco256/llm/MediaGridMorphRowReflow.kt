package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A bounded target row built from the same sorted media ordinal stream as the real grid. */
internal data class MediaGridMorphTargetRow(
    val rowIndex: Int,
    val cells: List<MediaGridMorphCapturedCell>,
    val headerBefore: MediaGridMorphTargetHeader? = null,
)

internal data class MediaGridMorphTargetHeader(
    val beforeRowIndex: Int,
    val key: String,
    val title: String,
    val firstMediaOrdinal: Int,
)

internal data class MediaGridMorphCellPlan(
    val relativeRow: Int,
    val column: Int,
    val startNormalizedLeft: Float,
    val startNormalizedRight: Float,
    val endNormalizedLeft: Float,
    val endNormalizedRight: Float,
    val startRect: Rect,
    val endRect: Rect,
    val startContent: MediaGridMorphSlotContent,
    val endContent: MediaGridMorphSlotContent,
    val targetMediaOrdinal: Int? = null,
)

internal data class MediaGridMorphRowPlan(
    val relativeRow: Int,
    val sourceTop: Float,
    val sourceBottom: Float,
    val targetTop: Float,
    val targetBottom: Float,
    val cells: List<MediaGridMorphCellPlan>,
)

internal data class MediaGridMorphHeaderPlan(
    val relativeRow: Int,
    val startRect: Rect,
    val endRect: Rect,
    val startTitle: String?,
    val endTitle: String?,
)

/**
 * Immutable, bounded plan template. It deliberately contains rows and screen
 * columns, never dataset-item start/end rect correspondences.
 */
internal data class MediaGridMorphViewportPlanTemplate(
    val sourceRevision: Long,
    val sourceFrameKey: MediaGridRenderKey,
    val fromColumnCount: Int,
    val toColumnCount: Int,
    val viewport: Rect,
    val sourceRows: List<MediaGridMorphCapturedRow>,
    val targetRows: List<MediaGridMorphTargetRow>,
    val targetHeaders: List<MediaGridMorphTargetHeader>,
    val sourceHeaders: List<MediaGridMorphCapturedHeaderRect>,
    val sortBase: ClassifiedSortBase,
    val totalMediaCount: Int,
    val mediaOrdinalRange: IntRange,
    val headerHeightPx: Float,
) {
    fun select(initialPinchCenter: Offset): MediaGridMorphViewportPlan {
        val sourceRowIndex = sourceRows.indexOfFirst { row ->
            initialPinchCenter.y >= row.top && initialPinchCenter.y <= row.bottom
        }.takeIf { it >= 0 } ?: sourceRows.indices.minByOrNull { index ->
            abs((sourceRows[index].top + sourceRows[index].bottom) / 2f - initialPinchCenter.y)
        } ?: return emptyPlan(initialPinchCenter)
        val sourceRow = sourceRows[sourceRowIndex]
        val focalCell = sourceRow.cells.minByOrNull { cell ->
            val dx = cell.rect.center.x - initialPinchCenter.x
            val dy = cell.rect.center.y - initialPinchCenter.y
            dx * dx + dy * dy
        } ?: return emptyPlan(initialPinchCenter)
        val sourceRowHeight = (sourceRow.bottom - sourceRow.top).coerceAtLeast(1f)
        val focalV = ((initialPinchCenter.y - sourceRow.top) / sourceRowHeight).coerceIn(0f, 1f)
        val targetCellHeight = (viewport.width / toColumnCount.coerceAtLeast(1)).coerceAtLeast(1f)

        val directTargetRowIndex = targetRows.indexOfFirst { row ->
            row.cells.any { it.mediaOrdinal == focalCell.mediaOrdinal }
        }
        val targetOrdinal = if (directTargetRowIndex >= 0) {
            focalCell.mediaOrdinal
        } else {
            val sourceFraction = focalCell.mediaOrdinal.toFloat() /
                max(totalMediaCount - 1, 1).toFloat()
            targetRows.asSequence()
                .flatMap { it.cells.asSequence() }
                .minByOrNull { target ->
                    abs(target.mediaOrdinal.toFloat() / max(totalMediaCount - 1, 1).toFloat() - sourceFraction)
                }?.mediaOrdinal ?: focalCell.mediaOrdinal
        }
        val targetRowIndex = targetRows.indexOfFirst { row ->
            row.cells.any { it.mediaOrdinal == targetOrdinal }
        }.takeIf { it >= 0 } ?: targetRows.indices.minByOrNull { index ->
            targetRows[index].cells.minOfOrNull { abs(it.mediaOrdinal - targetOrdinal) } ?: Int.MAX_VALUE
        } ?: 0
        val targetRowTopByIndex = targetRowTops(targetRowIndex, targetCellHeight)
        val targetFocalCell = targetRows.getOrNull(targetRowIndex)?.cells
            ?.firstOrNull { it.mediaOrdinal == targetOrdinal }
        val idealTargetRowTop = initialPinchCenter.y - focalV * targetCellHeight
        val achievableTargetRowTop = targetAnchorRowTopWithinScrollBounds(
            targetOrdinal = targetOrdinal,
            targetRowIndex = targetRowIndex,
            targetCellHeight = targetCellHeight,
            idealTargetRowTop = idealTargetRowTop,
        )
        val targetTopShift = achievableTargetRowTop - (targetRowTopByIndex[targetRowIndex] ?: achievableTargetRowTop)
        val shiftedTargetTops = targetRowTopByIndex.mapValues { it.value + targetTopShift }

        val minRelative = min(-sourceRowIndex, -targetRowIndex)
        val maxRelative = max(sourceRows.lastIndex - sourceRowIndex, targetRows.lastIndex - targetRowIndex)
        val rowPlans = (minRelative..maxRelative).map { relativeRow ->
            val source = sourceRows.getOrNull(sourceRowIndex + relativeRow)
            val target = targetRows.getOrNull(targetRowIndex + relativeRow)
            val sourceTop = source?.top ?: if (relativeRow < 0) viewport.top - targetCellHeight else viewport.bottom
            val sourceBottom = source?.bottom ?: sourceTop
            val targetTop = shiftedTargetTops[target?.rowIndex] ?: if (relativeRow < 0) viewport.top - targetCellHeight else viewport.bottom
            val targetBottom = target?.let { targetTop + targetCellHeight } ?: targetTop
            MediaGridMorphRowPlan(
                relativeRow = relativeRow,
                sourceTop = sourceTop,
                sourceBottom = sourceBottom,
                targetTop = targetTop,
                targetBottom = targetBottom,
                cells = buildCellPlans(
                    relativeRow = relativeRow,
                    source = source,
                    target = target,
                    sourceTop = sourceTop,
                    sourceBottom = sourceBottom,
                    targetTop = targetTop,
                    targetBottom = targetBottom,
                ),
            )
        }
        val headerPlans = buildHeaderPlans(
            sourceRowIndex = sourceRowIndex,
            targetRowIndex = targetRowIndex,
            sourceRows = sourceRows,
            targetRows = targetRows,
            sourceHeaders = sourceHeaders,
            shiftedTargetTops = shiftedTargetTops,
            targetCellHeight = targetCellHeight,
            relativeRange = minRelative..maxRelative,
        )
        return MediaGridMorphViewportPlan(
            sourceRevision = sourceRevision,
            sourceFrameKey = sourceFrameKey,
            fromColumnCount = fromColumnCount,
            toColumnCount = toColumnCount,
            viewport = viewport,
            initialPinchCenter = initialPinchCenter,
            focalMediaOrdinal = focalCell.mediaOrdinal,
            focalAssetId = focalCell.assetId,
            targetFocalMediaOrdinal = targetFocalCell?.mediaOrdinal,
            targetFocalAssetId = targetFocalCell?.assetId,
            focalV = focalV,
            targetAnchorRowIndex = targetRowIndex,
            targetAnchorRowTop = shiftedTargetTops[targetRowIndex] ?: achievableTargetRowTop,
            usedOrdinalFractionFallback = directTargetRowIndex < 0,
            rowPlans = rowPlans,
            headerPlans = headerPlans,
        )
    }

    private fun targetRowTops(anchorRowIndex: Int, cellHeight: Float): Map<Int, Float> {
        if (targetRows.isEmpty()) return emptyMap()
        val result = HashMap<Int, Float>(targetRows.size)
        result[anchorRowIndex] = viewport.center.y - cellHeight / 2f
        for (index in anchorRowIndex - 1 downTo 0) {
            val lower = result[index + 1] ?: continue
            val header = targetRows[index + 1].headerBefore
            result[index] = lower - (headerHeightPx.takeIf { header != null } ?: 0f) - cellHeight
        }
        for (index in anchorRowIndex + 1..targetRows.lastIndex) {
            val upper = result[index - 1] ?: continue
            val header = targetRows[index].headerBefore
            result[index] = upper + cellHeight + (headerHeightPx.takeIf { header != null } ?: 0f)
        }
        return result
    }

    /**
     * Converts the ideal focal-row top into a top that a real LazyGrid can
     * actually realize. Default ordering has no full-span headers, so its
     * global row index and total content height are exact. Header-based sorts
     * use the bounded target window plus the media ordinal prefix as the
     * conservative geometry available to this immutable capture.
     */
    private fun targetAnchorRowTopWithinScrollBounds(
        targetOrdinal: Int,
        targetRowIndex: Int,
        targetCellHeight: Float,
        idealTargetRowTop: Float,
    ): Float {
        val rowPrefix = if (sortBase == ClassifiedSortBase.Default) {
            targetOrdinal / toColumnCount.coerceAtLeast(1)
        } else {
            mediaOrdinalRange.first / toColumnCount.coerceAtLeast(1) + targetRowIndex
        }
        val totalMediaRows = ceil(totalMediaCount.toFloat() / toColumnCount.coerceAtLeast(1)).toInt()
        val estimatedHeaderCount = if (sortBase == ClassifiedSortBase.Default) 0 else targetHeaders.size
        val contentHeight = totalMediaRows * targetCellHeight + estimatedHeaderCount * headerHeightPx
        val maxScroll = (contentHeight - viewport.height).coerceAtLeast(0f)
        val rowTopAtScrollZero = viewport.top + rowPrefix * targetCellHeight
        val minimumTop = rowTopAtScrollZero - maxScroll
        val maximumTop = rowTopAtScrollZero
        return idealTargetRowTop.coerceIn(minimumTop, maximumTop)
    }

    private fun emptyPlan(center: Offset): MediaGridMorphViewportPlan = MediaGridMorphViewportPlan(
        sourceRevision = sourceRevision,
        sourceFrameKey = sourceFrameKey,
        fromColumnCount = fromColumnCount,
        toColumnCount = toColumnCount,
        viewport = viewport,
        initialPinchCenter = center,
        focalMediaOrdinal = null,
        focalAssetId = null,
        targetFocalMediaOrdinal = null,
        targetFocalAssetId = null,
        focalV = 0.5f,
        targetAnchorRowIndex = 0,
        targetAnchorRowTop = center.y,
        usedOrdinalFractionFallback = false,
        rowPlans = emptyList(),
        headerPlans = emptyList(),
    )

    private fun buildCellPlans(
        relativeRow: Int,
        source: MediaGridMorphCapturedRow?,
        target: MediaGridMorphTargetRow?,
        sourceTop: Float,
        sourceBottom: Float,
        targetTop: Float,
        targetBottom: Float,
    ): List<MediaGridMorphCellPlan> {
        val sourceWidth = viewport.width / fromColumnCount.coerceAtLeast(1)
        val targetWidth = viewport.width / toColumnCount.coerceAtLeast(1)
        val count = max(fromColumnCount, toColumnCount)
        val sourceByColumn = source?.cells?.associateBy { it.column }.orEmpty()
        val targetByColumn = target?.cells?.associateBy { it.column }.orEmpty()
        return (0 until count).map { column ->
            val startLeft = if (column < fromColumnCount) column.toFloat() / fromColumnCount else 1f
            val startRight = if (column < fromColumnCount) (column + 1).toFloat() / fromColumnCount else 1f
            val endLeft = if (column < toColumnCount) column.toFloat() / toColumnCount else 1f
            val endRight = if (column < toColumnCount) (column + 1).toFloat() / toColumnCount else 1f
            val captured = sourceByColumn[column]
            val targetCell = targetByColumn[column]
            val startRect = captured?.rect ?: Rect(
                viewport.left + startLeft * viewport.width,
                sourceTop,
                viewport.left + startRight * viewport.width,
                sourceBottom,
            )
            val endRect = Rect(
                viewport.left + endLeft * viewport.width,
                targetTop,
                viewport.left + endRight * viewport.width,
                targetBottom,
            )
            MediaGridMorphCellPlan(
                relativeRow = relativeRow,
                column = column,
                startNormalizedLeft = startLeft,
                startNormalizedRight = startRight,
                endNormalizedLeft = endLeft,
                endNormalizedRight = endRight,
                startRect = startRect,
                endRect = endRect,
                startContent = captured?.let { MediaGridMorphSlotContent.Image(it.assetId) }
                    ?: MediaGridMorphSlotContent.Placeholder,
                endContent = targetCell?.let { MediaGridMorphSlotContent.Image(it.assetId) }
                    ?: MediaGridMorphSlotContent.Placeholder,
                targetMediaOrdinal = targetCell?.mediaOrdinal,
            )
        }
    }

    private fun buildHeaderPlans(
        sourceRowIndex: Int,
        targetRowIndex: Int,
        sourceRows: List<MediaGridMorphCapturedRow>,
        targetRows: List<MediaGridMorphTargetRow>,
        sourceHeaders: List<MediaGridMorphCapturedHeaderRect>,
        shiftedTargetTops: Map<Int, Float>,
        targetCellHeight: Float,
        relativeRange: IntRange,
    ): List<MediaGridMorphHeaderPlan> {
        val sourceByRelative = sourceHeaders.mapNotNull { header ->
            val rowIndex = sourceRows.indexOfFirst { row -> row.cells.any { it.mediaOrdinal >= header.firstMediaOrdinal } }
            if (rowIndex < 0) null else (rowIndex - sourceRowIndex) to header
        }.toMap()
        val targetByRelative = targetRows.mapNotNull { row ->
            val header = row.headerBefore ?: return@mapNotNull null
            (row.rowIndex - targetRowIndex) to header
        }.toMap()
        return (relativeRange.first..relativeRange.last + 1).mapNotNull { relative ->
            val source = sourceByRelative[relative]
            val target = targetByRelative[relative]
            if (source == null && target == null) return@mapNotNull null
            val sourceRect = source?.rect ?: Rect(viewport.left, viewport.center.y, viewport.right, viewport.center.y)
            val targetRow = target?.beforeRowIndex?.let(targetRows::getOrNull)
            val targetTop = targetRow?.let { shiftedTargetTops[it.rowIndex] ?: viewport.center.y } ?: viewport.center.y
            val endRect = if (target != null) {
                Rect(viewport.left, targetTop - headerHeightPx, viewport.right, targetTop)
            } else {
                Rect(viewport.left, targetTop, viewport.right, targetTop)
            }
            MediaGridMorphHeaderPlan(
                relativeRow = relative,
                startRect = sourceRect,
                endRect = endRect,
                startTitle = source?.title,
                endTitle = target?.title,
            )
        }
    }
}

internal data class MediaGridMorphViewportPlan(
    val sourceRevision: Long,
    val sourceFrameKey: MediaGridRenderKey,
    val fromColumnCount: Int,
    val toColumnCount: Int,
    val viewport: Rect,
    val initialPinchCenter: Offset,
    val focalMediaOrdinal: Int?,
    val focalAssetId: Long?,
    val targetFocalMediaOrdinal: Int?,
    val targetFocalAssetId: Long?,
    val focalV: Float,
    val targetAnchorRowIndex: Int,
    val targetAnchorRowTop: Float,
    val usedOrdinalFractionFallback: Boolean,
    val rowPlans: List<MediaGridMorphRowPlan>,
    val headerPlans: List<MediaGridMorphHeaderPlan>,
)

internal fun buildMediaGridMorphViewportPlanTemplate(
    capture: MediaGridMorphCapture,
    toColumnCount: Int,
): MediaGridMorphViewportPlanTemplate {
    val sourceRows = capture.sourceRows.ifEmpty { fallbackCapturedRows(capture) }
    val targetLayout = buildTargetRows(capture, toColumnCount)
    return MediaGridMorphViewportPlanTemplate(
        sourceRevision = capture.identity.sourceRevision,
        sourceFrameKey = capture.identity.frameKey,
        fromColumnCount = capture.identity.columnCount,
        toColumnCount = toColumnCount,
        viewport = capture.viewport,
        sourceRows = sourceRows,
        targetRows = targetLayout.first,
        targetHeaders = targetLayout.second,
        sourceHeaders = capture.visibleHeaderRects,
        sortBase = capture.sortBase,
        totalMediaCount = capture.totalMediaCount,
        mediaOrdinalRange = capture.mediaOrdinalRange,
        headerHeightPx = capture.headerHeightPx,
    )
}

internal fun buildMediaGridMorphViewportPlan(
    capture: MediaGridMorphCapture,
    toColumnCount: Int,
    initialPinchCenter: Offset,
): MediaGridMorphViewportPlan = buildMediaGridMorphViewportPlanTemplate(capture, toColumnCount).select(initialPinchCenter)

private fun fallbackCapturedRows(capture: MediaGridMorphCapture): List<MediaGridMorphCapturedRow> =
    capture.visibleMediaRects
        .groupBy { it.rect.top to it.rect.bottom }
        .entries
        .sortedBy { it.key.first }
        .mapIndexed { rowIndex, (_, rects) ->
            MediaGridMorphCapturedRow(
                visibleRow = rowIndex,
                top = rects.minOf { it.rect.top },
                bottom = rects.maxOf { it.rect.bottom },
                cells = rects.sortedBy { it.rect.left }.mapIndexed { column, rect ->
                    MediaGridMorphCapturedCell(
                        column = column,
                        mediaOrdinal = rect.mediaOrdinal,
                        assetId = rect.assetId ?: capture.media.firstOrNull { it.mediaOrdinal == rect.mediaOrdinal }?.assetId ?: -1L,
                        rect = rect.rect,
                        isPartiallyVisible = rect.isPartiallyVisible,
                    )
                }.filter { it.assetId >= 0L },
                isPartiallyVisible = rects.any { it.isPartiallyVisible },
            )
        }

private fun buildTargetRows(
    capture: MediaGridMorphCapture,
    columnCount: Int,
): Pair<List<MediaGridMorphTargetRow>, List<MediaGridMorphTargetHeader>> {
    val rows = ArrayList<MediaGridMorphTargetRow>()
    val headers = ArrayList<MediaGridMorphTargetHeader>()
    val boundedMedia = capture.media.distinctBy { it.mediaOrdinal }.sortedBy { it.mediaOrdinal }
    var current = ArrayList<MediaGridMorphCapturedCell>(columnCount)
    var previousBucket: MediaGridMorphBucketSpec? = capture.precedingMedia?.let {
        mediaGridMorphBucketSpec(it.xCreatedAt, it.likeCount, capture.sortBase, columnCount)
    }
    fun flush() {
        if (current.isNotEmpty()) {
            rows += MediaGridMorphTargetRow(rows.size, current.toList())
            current = ArrayList(columnCount)
        }
    }
    boundedMedia.forEach { item ->
        val bucket = mediaGridMorphBucketSpec(item.xCreatedAt, item.likeCount, capture.sortBase, columnCount)
        if (bucket != null && bucket.key != previousBucket?.key) {
            flush()
            val header = MediaGridMorphTargetHeader(rows.size, "media_grid_header_${bucket.safeKey}", bucket.label, item.mediaOrdinal)
            headers += header
            current = ArrayList(columnCount)
            previousBucket = bucket
        }
        current += MediaGridMorphCapturedCell(
            column = current.size,
            mediaOrdinal = item.mediaOrdinal,
            assetId = item.assetId,
            rect = Rect.Zero,
            isPartiallyVisible = false,
        )
        if (current.size == columnCount) flush()
    }
    flush()
    if (headers.isEmpty()) return rows.map { it.copy(headerBefore = null) } to headers
    val headersByRow = headers.associateBy { it.beforeRowIndex }
    return rows.map { it.copy(headerBefore = headersByRow[it.rowIndex]) } to headers
}

internal fun mediaGridMorphProgressForDistance(
    initialDistance: Float,
    currentDistance: Float,
    fromColumnCount: Int,
    toColumnCount: Int,
): Float {
    if (!initialDistance.isFinite() || !currentDistance.isFinite() || initialDistance <= 0f || currentDistance <= 0f) return 0f
    val currentRatio = currentDistance / initialDistance
    val targetRatio = fromColumnCount.toFloat() / toColumnCount.coerceAtLeast(1).toFloat()
    val denominator = targetRatio - 1f
    if (!denominator.isFinite() || abs(denominator) < 0.0001f) return 0f
    val raw = (currentRatio - 1f) / denominator
    return raw.coerceIn(0f, 1f)
}

internal fun mediaGridMorphProgressForDistance(
    initialDistance: Float,
    currentDistance: Float,
    direction: MediaGridMorphDirection,
    currentColumnCount: Int,
): Float = mediaGridMorphProgressForDistance(
    initialDistance,
    currentDistance,
    currentColumnCount,
    mediaGridMorphTargetColumnCount(currentColumnCount, direction),
)

internal fun mediaGridMorphRowCellRect(
    cell: MediaGridMorphCellPlan,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        lerpRowEdge(cell.startRect.left, cell.endRect.left, p),
        lerpRowEdge(cell.startRect.top, cell.endRect.top, p),
        lerpRowEdge(cell.startRect.right, cell.endRect.right, p),
        lerpRowEdge(cell.startRect.bottom, cell.endRect.bottom, p),
    )
}

private fun lerpRowEdge(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress
