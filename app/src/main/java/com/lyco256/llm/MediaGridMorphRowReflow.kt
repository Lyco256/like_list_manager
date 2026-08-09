package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A bounded row built from the same aligned media ordinal stream as the real grid. */
internal data class MediaGridMorphAlignedRow(
    val rowIndex: Int,
    val cells: List<MediaGridMorphCapturedCell>,
    val headerBefore: MediaGridMorphAlignedHeader? = null,
    val rowKey: MediaGridMorphSourceRowKey? = null,
)

internal data class MediaGridMorphAlignedHeader(
    val beforeRowIndex: Int,
    val key: String,
    val title: String,
    val firstMediaOrdinal: Int,
)

internal data class MediaGridMorphCellPlan(
    val relativeRow: Int,
    val column: Int,
    val startContent: MediaGridMorphSlotContent,
    val endContent: MediaGridMorphSlotContent,
    val targetMediaOrdinal: Int? = null,
    val startHeaderOffsetPx: Float = 0f,
    val endHeaderOffsetPx: Float = 0f,
    val sourceRect: Rect? = null,
    val sourceItemIndex: Int? = null,
    val sourceItemKey: String? = null,
    val sourcePreparedImageIdentity: MediaGridResidentImageIdentity? = null,
)

internal data class MediaGridMorphRowPlan(
    val relativeRow: Int,
    val cells: List<MediaGridMorphCellPlan>,
)

internal data class MediaGridMorphHeaderPlan(
    val relativeRow: Int,
    val startHeightPx: Float,
    val endHeightPx: Float,
    val startOffsetBeforePx: Float,
    val endOffsetBeforePx: Float,
    val startTitle: String?,
    val endTitle: String?,
    val sourceRect: Rect? = null,
    val startKey: String? = null,
    val endKey: String? = null,
)

/** Bounded lookup data prepared once with the idle viewport template. */
internal data class MediaGridMorphViewportSelectionIndex(
    val actualVisibleSourceRows: List<MediaGridMorphCapturedRow>,
    val sourceCanonicalRowIndexByVisibleRow: IntArray,
    val targetMediaOrdinalBase: Int,
    val targetRowIndexByMediaOrdinal: IntArray,
    val targetExactRowIdByRowIndex: IntArray,
    val targetExactRowTopByRowIndex: FloatArray,
    val sourceHeaderCanonicalRowIndexByHeaderIndex: IntArray,
    val targetHeaderRowIndexByHeaderIndex: IntArray,
    val targetHeaderExactRowIdByHeaderIndex: IntArray,
    val targetHeaderTopByHeaderIndex: FloatArray,
) {
    fun targetRowIndex(mediaOrdinal: Int): Int =
        targetRowIndexByMediaOrdinal.getOrNull(mediaOrdinal - targetMediaOrdinalBase) ?: -1
}

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
    /** Geometry/focal selection uses sourceRows; source content uses visible rows by row key. */
    val sourceCanonicalRows: List<MediaGridMorphAlignedRow>,
    val targetRows: List<MediaGridMorphAlignedRow>,
    val targetHeaders: List<MediaGridMorphAlignedHeader>,
    val sourceHeaders: List<MediaGridMorphCapturedHeaderRect>,
    val sortBase: ClassifiedSortBase,
    val totalMediaCount: Int,
    val mediaOrdinalRange: IntRange,
    val headerHeightPx: Float,
    val exactTargetLayoutIndex: MediaGridMorphExactTargetLayoutIndex? = null,
    val selectionIndex: MediaGridMorphViewportSelectionIndex,
) {
    fun select(initialPinchCenter: Offset): MediaGridMorphViewportPlan {
        // Only rows actually intersecting the captured source viewport may
        // select a plan or participate in source validation.  The canonical
        // row list is intentionally allowed to contain bounded overscan rows
        // that are not one-to-one with this list.
        val visibleSourceRows = selectionIndex.actualVisibleSourceRows
        if (visibleSourceRows.isEmpty()) return emptyPlan(initialPinchCenter)
        var visibleSourceRowIndex = -1
        var nearestDistance = Float.POSITIVE_INFINITY
        var containingRowFound = false
        var visibleIndex = 0
        while (visibleIndex < visibleSourceRows.size) {
            val row = visibleSourceRows[visibleIndex]
            val canonicalIndex = selectionIndex.sourceCanonicalRowIndexByVisibleRow.getOrElse(visibleIndex) { -1 }
            if (canonicalIndex !in sourceCanonicalRows.indices ||
                !sourceRowMatchesCanonical(row, sourceCanonicalRows[canonicalIndex])
            ) {
                return emptyPlan(initialPinchCenter)
            }
            if (initialPinchCenter.y >= row.top && initialPinchCenter.y <= row.bottom) {
                if (!containingRowFound) {
                    visibleSourceRowIndex = visibleIndex
                    containingRowFound = true
                }
            } else if (!containingRowFound) {
                val distance = abs((row.top + row.bottom) / 2f - initialPinchCenter.y)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    visibleSourceRowIndex = visibleIndex
                }
            }
            visibleIndex++
        }
        if (visibleSourceRowIndex !in visibleSourceRows.indices) return emptyPlan(initialPinchCenter)
        val visibleSourceRow = visibleSourceRows[visibleSourceRowIndex]
        val sourceCanonicalRowIndex = selectionIndex.sourceCanonicalRowIndexByVisibleRow[visibleSourceRowIndex]
        if (sourceCanonicalRowIndex < 0) return emptyPlan(initialPinchCenter)
        var focalCell: MediaGridMorphCapturedCell? = null
        var focalDistance = Float.POSITIVE_INFINITY
        var focalCellIndex = 0
        while (focalCellIndex < visibleSourceRow.cells.size) {
            val cell = visibleSourceRow.cells[focalCellIndex]
            val dx = cell.rect.center.x - initialPinchCenter.x
            val dy = cell.rect.center.y - initialPinchCenter.y
            val distance = dx * dx + dy * dy
            if (distance < focalDistance) {
                focalDistance = distance
                focalCell = cell
            }
            focalCellIndex++
        }
        focalCell ?: return emptyPlan(initialPinchCenter)
        val fixedFocalCenterY = viewport.top + initialPinchCenter.y
        val sourceCellSize = (viewport.width / fromColumnCount.coerceAtLeast(1)).coerceAtLeast(1f)
        val targetCellSize = (viewport.width / toColumnCount.coerceAtLeast(1)).coerceAtLeast(1f)
        val focalV = ((fixedFocalCenterY - visibleSourceRow.top) / sourceCellSize).coerceIn(0f, 1f)

        val targetOrdinal = focalCell.mediaOrdinal
        val targetRowIndex = selectionIndex.targetRowIndex(targetOrdinal)
            .takeIf { it in targetRows.indices } ?: return emptyPlan(initialPinchCenter)
        var targetFocalCell: MediaGridMorphCapturedCell? = null
        var targetCellIndex = 0
        while (targetCellIndex < targetRows[targetRowIndex].cells.size) {
            val candidate = targetRows[targetRowIndex].cells[targetCellIndex]
            if (candidate.mediaOrdinal == targetOrdinal) {
                targetFocalCell = candidate
                break
            }
            targetCellIndex++
        }
        targetFocalCell ?: return emptyPlan(initialPinchCenter)
        val idealTargetRowTop = fixedFocalCenterY - focalV * targetCellSize
        val exactIndex = exactTargetLayoutIndex ?: return emptyPlan(initialPinchCenter)
        val exactTargetRowId = exactIndex.rowIdForMediaOrdinal(targetOrdinal) ?: return emptyPlan(initialPinchCenter)
        val exactTargetRowTopAtScrollZero = exactIndex.rowTopAtScrollZero(exactTargetRowId)
            ?: return emptyPlan(initialPinchCenter)
        val achievableTargetRowTop = exactIndex.achievableRowTop(exactTargetRowId, idealTargetRowTop)
            ?: return emptyPlan(initialPinchCenter)
        val targetRowTopAdjustment = achievableTargetRowTop - idealTargetRowTop

        val contentMinRelative = min(-sourceCanonicalRowIndex, -targetRowIndex)
        val contentMaxRelative = max(sourceCanonicalRows.lastIndex - sourceCanonicalRowIndex, targetRows.lastIndex - targetRowIndex)
        val viewportMinRelative = min(
            relativeRowAtViewportEdge(viewport.top, fixedFocalCenterY, focalV, sourceCellSize),
            relativeRowAtViewportEdge(viewport.top, fixedFocalCenterY, focalV, targetCellSize),
        ) - MediaGridMorphDefaults.OverscanRows
        val viewportMaxRelative = max(
            relativeRowAtViewportEdge(viewport.bottom, fixedFocalCenterY, focalV, sourceCellSize),
            relativeRowAtViewportEdge(viewport.bottom, fixedFocalCenterY, focalV, targetCellSize),
        ) + MediaGridMorphDefaults.OverscanRows
        val minRelative = min(contentMinRelative, viewportMinRelative)
        val maxRelative = max(contentMaxRelative, viewportMaxRelative)
        val relativeRange = minRelative..maxRelative
        val headerPlans = buildHeaderPlans(
            sourceRowIndex = sourceCanonicalRowIndex,
            targetRowIndex = targetRowIndex,
            sourceRows = sourceCanonicalRows,
            targetRows = targetRows,
            sourceHeaders = sourceHeaders,
            relativeRange = relativeRange,
            exactTargetLayoutIndex = exactTargetLayoutIndex,
            exactTargetRowId = exactTargetRowId,
            exactTargetRowTopAtScrollZero = exactTargetRowTopAtScrollZero,
            targetCellSize = targetCellSize,
        )
        val rowPlans = ArrayList<MediaGridMorphRowPlan>((maxRelative - minRelative + 1).coerceAtLeast(0))
        var relativeRow = minRelative
        while (relativeRow <= maxRelative) {
            val canonicalSource = sourceCanonicalRows.getOrNull(sourceCanonicalRowIndex + relativeRow)
            val source = canonicalSource?.let { candidate ->
                val visible = visibleSourceRowForCanonicalIndex(sourceCanonicalRowIndex + relativeRow)
                if (visible == null) candidate else MediaGridMorphAlignedRow(
                    rowIndex = candidate.rowIndex,
                    cells = visible.cells,
                    headerBefore = candidate.headerBefore,
                    rowKey = candidate.rowKey,
                )
            }
            val target = targetRows.getOrNull(targetRowIndex + relativeRow)
            rowPlans += MediaGridMorphRowPlan(
                relativeRow = relativeRow,
                cells = buildCellPlans(
                    relativeRow = relativeRow,
                    source = source,
                    target = target,
                    startHeaderOffsetPx = headerOffsetBefore(relativeRow, headerPlans, useStart = true),
                    endHeaderOffsetPx = exactTargetRowOffset(
                        relativeRow = relativeRow,
                        targetRowIndex = targetRowIndex,
                        exactTargetRowId = exactTargetRowId,
                        exactTargetRowTopAtScrollZero = exactTargetRowTopAtScrollZero,
                        targetCellSize = targetCellSize,
                    ) ?: headerOffsetBefore(relativeRow, headerPlans, useStart = false),
                ),
            )
            relativeRow++
        }
        return MediaGridMorphViewportPlan(
            sourceRevision = sourceRevision,
            sourceFrameKey = sourceFrameKey,
            fromColumnCount = fromColumnCount,
            toColumnCount = toColumnCount,
            viewport = viewport,
            initialPinchCenter = initialPinchCenter,
            focalMediaOrdinal = focalCell.mediaOrdinal,
            focalAssetId = focalCell.assetId,
            targetFocalMediaOrdinal = targetFocalCell.mediaOrdinal,
            targetFocalAssetId = targetFocalCell.assetId,
            sourceCellSize = sourceCellSize,
            targetCellSize = targetCellSize,
            fixedFocalCenterY = fixedFocalCenterY,
            sourceFocalRowTop = visibleSourceRow.top,
            focalV = focalV,
            relativeRowRange = relativeRange,
            targetAnchorRowIndex = exactTargetRowId ?: targetRowIndex,
            targetAnchorRowTop = achievableTargetRowTop,
            rowPlans = rowPlans,
            headerPlans = headerPlans,
            exactTargetLayoutIndex = exactTargetLayoutIndex,
            targetAnchorRowId = exactTargetRowId,
            targetAnchorRowFirstItemIndex = exactTargetRowId?.let { rowId ->
                exactTargetLayoutIndex?.rowFirstItemIndex(rowId)
            },
            targetAnchorRowMediaOrdinals = exactTargetRowId?.let { rowId ->
                exactTargetLayoutIndex?.rowMediaOrdinals(rowId)
            }
                ?: IntArray(0),
            targetAnchorRowTopAtScrollZero = exactTargetRowTopAtScrollZero,
            targetUnclampedRowTop = idealTargetRowTop,
            targetMaxScroll = exactIndex.maxScrollPx,
            targetRowTopAdjustment = targetRowTopAdjustment,
        )
    }

    private fun visibleSourceRowForCanonicalIndex(canonicalIndex: Int): MediaGridMorphCapturedRow? {
        var index = 0
        while (index < selectionIndex.sourceCanonicalRowIndexByVisibleRow.size) {
            if (selectionIndex.sourceCanonicalRowIndexByVisibleRow[index] == canonicalIndex) {
                return selectionIndex.actualVisibleSourceRows.getOrNull(index)
            }
            index++
        }
        return null
    }

    private fun exactTargetRowOffset(
        relativeRow: Int,
        targetRowIndex: Int,
        exactTargetRowId: Int,
        exactTargetRowTopAtScrollZero: Float,
        targetCellSize: Float,
    ): Float? {
        var rowIndex = 0
        while (rowIndex < selectionIndex.targetExactRowIdByRowIndex.size) {
            val rowId = selectionIndex.targetExactRowIdByRowIndex[rowIndex]
            if (rowId >= 0 && rowId - exactTargetRowId == relativeRow) {
                val top = selectionIndex.targetExactRowTopByRowIndex.getOrElse(rowIndex) { Float.NaN }
                if (top.isFinite()) {
                    return top - exactTargetRowTopAtScrollZero - relativeRow * targetCellSize
                }
            }
            rowIndex++
        }
        return null
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
        sourceCellSize = viewport.width / fromColumnCount.coerceAtLeast(1),
        targetCellSize = viewport.width / toColumnCount.coerceAtLeast(1),
        fixedFocalCenterY = viewport.top + center.y,
        sourceFocalRowTop = viewport.top + center.y,
        focalV = 0.5f,
        relativeRowRange = IntRange.EMPTY,
        targetAnchorRowIndex = 0,
        targetAnchorRowTop = center.y,
        rowPlans = emptyList(),
        headerPlans = emptyList(),
    )

    private fun buildCellPlans(
        relativeRow: Int,
        source: MediaGridMorphAlignedRow?,
        target: MediaGridMorphAlignedRow?,
        startHeaderOffsetPx: Float,
        endHeaderOffsetPx: Float,
    ): List<MediaGridMorphCellPlan> {
        val count = max(fromColumnCount, toColumnCount)
        val result = ArrayList<MediaGridMorphCellPlan>(count)
        var column = 0
        while (column < count) {
            val captured = cellAtColumn(source, column)
            val targetCell = cellAtColumn(target, column)
            result += MediaGridMorphCellPlan(
                relativeRow = relativeRow,
                column = column,
                startContent = captured?.let { MediaGridMorphSlotContent.Image(it.assetId) }
                    ?: MediaGridMorphSlotContent.NoMedia,
                endContent = targetCell?.let { MediaGridMorphSlotContent.Image(it.assetId) }
                    ?: MediaGridMorphSlotContent.NoMedia,
                targetMediaOrdinal = targetCell?.mediaOrdinal,
                startHeaderOffsetPx = startHeaderOffsetPx,
                endHeaderOffsetPx = endHeaderOffsetPx,
                sourceRect = captured?.rect?.takeIf { it.width > 0f && it.height > 0f },
                sourceItemIndex = captured?.itemIndex?.takeIf { it >= 0 },
                sourceItemKey = captured?.itemKey,
                sourcePreparedImageIdentity = captured?.preparedImageIdentity,
            )
            column++
        }
        return result
    }

    private fun cellAtColumn(row: MediaGridMorphAlignedRow?, column: Int): MediaGridMorphCapturedCell? {
        val cells = row?.cells ?: return null
        var index = 0
        while (index < cells.size) {
            if (cells[index].column == column) return cells[index]
            index++
        }
        return null
    }

    private fun buildHeaderPlans(
        sourceRowIndex: Int,
        targetRowIndex: Int,
        sourceRows: List<MediaGridMorphAlignedRow>,
        targetRows: List<MediaGridMorphAlignedRow>,
        sourceHeaders: List<MediaGridMorphCapturedHeaderRect>,
        relativeRange: IntRange,
        exactTargetLayoutIndex: MediaGridMorphExactTargetLayoutIndex?,
        exactTargetRowId: Int?,
        exactTargetRowTopAtScrollZero: Float?,
        targetCellSize: Float,
    ): List<MediaGridMorphHeaderPlan> {
        val rawPlans = ArrayList<MediaGridMorphHeaderPlan>()
        var relative = relativeRange.first
        while (relative <= relativeRange.last + 1) {
            val source = sourceHeaderAtRelative(relative, sourceRowIndex)
            val targetHeaderIndex = targetHeaderIndexAtRelative(relative, targetRowIndex, exactTargetRowId)
            val target = targetHeaders.getOrNull(targetHeaderIndex)
            if (source != null || target != null) rawPlans += MediaGridMorphHeaderPlan(
                relativeRow = relative,
                startHeightPx = source?.rect?.height ?: 0f,
                endHeightPx = target?.let { headerHeightPx } ?: 0f,
                startOffsetBeforePx = 0f,
                endOffsetBeforePx = 0f,
                startTitle = source?.title,
                endTitle = target?.title,
                sourceRect = source?.rect,
                startKey = source?.key,
                endKey = target?.key,
            )
            relative++
        }
        val result = ArrayList<MediaGridMorphHeaderPlan>(rawPlans.size)
        var planIndex = 0
        while (planIndex < rawPlans.size) {
            val plan = rawPlans[planIndex]
            val targetHeaderIndex = targetHeaderIndexAtRelative(plan.relativeRow, targetRowIndex, exactTargetRowId)
            val exactHeaderTop = selectionIndex.targetHeaderTopByHeaderIndex
                .getOrElse(targetHeaderIndex) { Float.NaN }
            val exactEndOffset = if (
                exactHeaderTop.isFinite() &&
                    exactTargetRowTopAtScrollZero != null &&
                    exactTargetRowId != null
            ) {
                exactHeaderTop - exactTargetRowTopAtScrollZero -
                    plan.relativeRow * targetCellSize
            } else {
                null
            }
            result += plan.copy(
                startOffsetBeforePx = headerOffsetBefore(plan.relativeRow, rawPlans, useStart = true) - plan.startHeightPx,
                endOffsetBeforePx = exactEndOffset
                    ?: headerOffsetBefore(plan.relativeRow, rawPlans, useStart = false) - plan.endHeightPx,
            )
            planIndex++
        }
        return result
    }

    private fun sourceHeaderAtRelative(
        relative: Int,
        sourceRowIndex: Int,
    ): MediaGridMorphCapturedHeaderRect? {
        var index = 0
        while (index < sourceHeaders.size) {
            val canonicalIndex = selectionIndex.sourceHeaderCanonicalRowIndexByHeaderIndex
                .getOrElse(index) { -1 }
            if (canonicalIndex >= 0 && canonicalIndex - sourceRowIndex == relative) return sourceHeaders[index]
            index++
        }
        return null
    }

    private fun targetHeaderIndexAtRelative(
        relative: Int,
        targetRowIndex: Int,
        exactTargetRowId: Int?,
    ): Int {
        var index = 0
        while (index < targetHeaders.size) {
            val rowIndex = selectionIndex.targetHeaderRowIndexByHeaderIndex.getOrElse(index) { -1 }
            val exactRowId = selectionIndex.targetHeaderExactRowIdByHeaderIndex.getOrElse(index) { -1 }
            val headerRelative = if (exactRowId >= 0 && exactTargetRowId != null) {
                exactRowId - exactTargetRowId
            } else {
                rowIndex - targetRowIndex
            }
            if (rowIndex >= 0 && headerRelative == relative) return index
            index++
        }
        return -1
    }

    private fun headerOffsetBefore(
        relativeRow: Int,
        plans: List<MediaGridMorphHeaderPlan>,
        useStart: Boolean,
    ): Float {
        var total = 0f
        if (relativeRow > 0) {
            var row = 1
            while (row <= relativeRow) {
                var index = 0
                while (index < plans.size) {
                    val plan = plans[index]
                    if (plan.relativeRow == row) total += if (useStart) plan.startHeightPx else plan.endHeightPx
                    index++
                }
                row++
            }
        } else if (relativeRow < 0) {
            var row = relativeRow + 1
            while (row <= 0) {
                var index = 0
                while (index < plans.size) {
                    val plan = plans[index]
                    if (plan.relativeRow == row) total -= if (useStart) plan.startHeightPx else plan.endHeightPx
                    index++
                }
                row++
            }
        }
        return total
    }
}

private fun sourceRowMatchesCanonical(
    visible: MediaGridMorphCapturedRow,
    canonical: MediaGridMorphAlignedRow?,
): Boolean {
    if (canonical == null || visible.rowKey != canonical.rowKey) return false
    var visibleIndex = 0
    while (visibleIndex < visible.cells.size) {
        val actual = visible.cells[visibleIndex]
        var canonicalIndex = 0
        var expected: MediaGridMorphCapturedCell? = null
        while (canonicalIndex < canonical.cells.size) {
            val candidate = canonical.cells[canonicalIndex]
            if (candidate.column == actual.column) {
                expected = candidate
                break
            }
            canonicalIndex++
        }
        if (expected == null || actual.mediaOrdinal != expected.mediaOrdinal || actual.assetId != expected.assetId) {
            return false
        }
        visibleIndex++
    }
    return true
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
    val sourceCellSize: Float,
    val targetCellSize: Float,
    val fixedFocalCenterY: Float,
    val sourceFocalRowTop: Float,
    val focalV: Float,
    val relativeRowRange: IntRange,
    val targetAnchorRowIndex: Int,
    val targetAnchorRowTop: Float,
    val rowPlans: List<MediaGridMorphRowPlan>,
    val headerPlans: List<MediaGridMorphHeaderPlan>,
    val exactTargetLayoutIndex: MediaGridMorphExactTargetLayoutIndex? = null,
    val targetAnchorRowId: Int? = null,
    val targetAnchorRowFirstItemIndex: Int? = null,
    val targetAnchorRowMediaOrdinals: IntArray = IntArray(0),
    val targetAnchorRowTopAtScrollZero: Float? = null,
    val targetUnclampedRowTop: Float? = null,
    val targetMaxScroll: Float = 0f,
    val targetRowTopAdjustment: Float = 0f,
)

internal fun buildMediaGridMorphViewportPlanTemplate(
    capture: MediaGridMorphCapture,
    toColumnCount: Int,
): MediaGridMorphViewportPlanTemplate {
    val sourceRows = normalizeCapturedSourceRows(
        rows = capture.sourceRows.ifEmpty { fallbackCapturedRows(capture) },
        capture = capture,
    )
    val sourceLayout = buildMediaGridMorphExactTargetRows(capture, capture.identity.columnCount)
    val targetLayout = buildMediaGridMorphExactTargetRows(capture, toColumnCount)
    val exactTargetLayoutIndex = capture.exactTargetLayoutIndexes[toColumnCount]
    val selectionIndex = buildMediaGridMorphViewportSelectionIndex(
        sourceRows = sourceRows,
        sourceCanonicalRows = sourceLayout.first,
        targetRows = targetLayout.first,
        sourceHeaders = capture.visibleHeaderRects,
        targetHeaders = targetLayout.second,
        mediaOrdinalRange = capture.mediaOrdinalRange,
        exactTargetLayoutIndex = exactTargetLayoutIndex,
    )
    return MediaGridMorphViewportPlanTemplate(
        sourceRevision = capture.identity.sourceRevision,
        sourceFrameKey = capture.identity.frameKey,
        fromColumnCount = capture.identity.columnCount,
        toColumnCount = toColumnCount,
        viewport = capture.viewport,
        sourceRows = sourceRows,
        sourceCanonicalRows = sourceLayout.first,
        targetRows = targetLayout.first,
        targetHeaders = targetLayout.second,
        sourceHeaders = capture.visibleHeaderRects,
        sortBase = capture.sortBase,
        totalMediaCount = capture.totalMediaCount,
        mediaOrdinalRange = capture.mediaOrdinalRange,
        headerHeightPx = capture.headerHeightPx,
        exactTargetLayoutIndex = exactTargetLayoutIndex,
        selectionIndex = selectionIndex,
    )
}

private fun buildMediaGridMorphViewportSelectionIndex(
    sourceRows: List<MediaGridMorphCapturedRow>,
    sourceCanonicalRows: List<MediaGridMorphAlignedRow>,
    targetRows: List<MediaGridMorphAlignedRow>,
    sourceHeaders: List<MediaGridMorphCapturedHeaderRect>,
    targetHeaders: List<MediaGridMorphAlignedHeader>,
    mediaOrdinalRange: IntRange,
    exactTargetLayoutIndex: MediaGridMorphExactTargetLayoutIndex?,
): MediaGridMorphViewportSelectionIndex {
    val visibleRows = ArrayList<MediaGridMorphCapturedRow>()
    var sourceRowIndex = 0
    while (sourceRowIndex < sourceRows.size) {
        val row = sourceRows[sourceRowIndex]
        if (row.isActualVisibleSourceRow) visibleRows += row
        sourceRowIndex++
    }
    val canonicalByVisible = IntArray(visibleRows.size) { -1 }
    var visibleIndex = 0
    while (visibleIndex < visibleRows.size) {
        val key = visibleRows[visibleIndex].rowKey
        var match = -1
        var canonicalIndex = 0
        while (canonicalIndex < sourceCanonicalRows.size) {
            if (key != null && sourceCanonicalRows[canonicalIndex].rowKey == key) {
                if (match >= 0) {
                    match = -1
                    break
                }
                match = canonicalIndex
            }
            canonicalIndex++
        }
        canonicalByVisible[visibleIndex] = match
        visibleIndex++
    }

    val ordinalBase = mediaOrdinalRange.first
    val targetRowByOrdinal = IntArray((mediaOrdinalRange.last - ordinalBase + 1).coerceAtLeast(0)) { -1 }
    val exactRowIds = IntArray(targetRows.size) { -1 }
    val exactRowTops = FloatArray(targetRows.size) { Float.NaN }
    var targetRowIndex = 0
    while (targetRowIndex < targetRows.size) {
        val row = targetRows[targetRowIndex]
        var cellIndex = 0
        while (cellIndex < row.cells.size) {
            val ordinal = row.cells[cellIndex].mediaOrdinal
            val offset = ordinal - ordinalBase
            if (offset in targetRowByOrdinal.indices) targetRowByOrdinal[offset] = targetRowIndex
            cellIndex++
        }
        val firstOrdinal = row.cells.firstOrNull()?.mediaOrdinal
        val exactRowId = firstOrdinal?.let { exactTargetLayoutIndex?.rowIdForMediaOrdinal(it) }
        if (exactRowId != null) {
            exactRowIds[targetRowIndex] = exactRowId
            exactRowTops[targetRowIndex] = exactTargetLayoutIndex?.rowTopAtScrollZero(exactRowId) ?: Float.NaN
        }
        targetRowIndex++
    }

    val sourceHeaderRows = IntArray(sourceHeaders.size) { -1 }
    var sourceHeaderIndex = 0
    while (sourceHeaderIndex < sourceHeaders.size) {
        val ordinal = sourceHeaders[sourceHeaderIndex].firstMediaOrdinal
        var canonicalIndex = 0
        while (canonicalIndex < sourceCanonicalRows.size) {
            if (rowContainsMediaOrdinal(sourceCanonicalRows[canonicalIndex], ordinal)) {
                sourceHeaderRows[sourceHeaderIndex] = canonicalIndex
                break
            }
            canonicalIndex++
        }
        sourceHeaderIndex++
    }

    val targetHeaderRows = IntArray(targetHeaders.size) { -1 }
    val targetHeaderExactRows = IntArray(targetHeaders.size) { -1 }
    val targetHeaderTops = FloatArray(targetHeaders.size) { Float.NaN }
    var targetHeaderIndex = 0
    while (targetHeaderIndex < targetHeaders.size) {
        val header = targetHeaders[targetHeaderIndex]
        var rowIndex = 0
        while (rowIndex < targetRows.size) {
            val rowHeader = targetRows[rowIndex].headerBefore
            if (rowHeader?.key == header.key && rowHeader.firstMediaOrdinal == header.firstMediaOrdinal) {
                targetHeaderRows[targetHeaderIndex] = rowIndex
                val exactRowId = exactRowIds.getOrElse(rowIndex) { -1 }
                targetHeaderExactRows[targetHeaderIndex] = exactRowId
                if (exactRowId >= 0) {
                    targetHeaderTops[targetHeaderIndex] = exactTargetLayoutIndex
                        ?.headerForRow(exactRowId)
                        ?.takeIf { it.key == header.key && it.firstMediaOrdinal == header.firstMediaOrdinal }
                        ?.topAtScrollZero
                        ?: Float.NaN
                }
                break
            }
            rowIndex++
        }
        targetHeaderIndex++
    }
    return MediaGridMorphViewportSelectionIndex(
        actualVisibleSourceRows = visibleRows,
        sourceCanonicalRowIndexByVisibleRow = canonicalByVisible,
        targetMediaOrdinalBase = ordinalBase,
        targetRowIndexByMediaOrdinal = targetRowByOrdinal,
        targetExactRowIdByRowIndex = exactRowIds,
        targetExactRowTopByRowIndex = exactRowTops,
        sourceHeaderCanonicalRowIndexByHeaderIndex = sourceHeaderRows,
        targetHeaderRowIndexByHeaderIndex = targetHeaderRows,
        targetHeaderExactRowIdByHeaderIndex = targetHeaderExactRows,
        targetHeaderTopByHeaderIndex = targetHeaderTops,
    )
}

private fun rowContainsMediaOrdinal(row: MediaGridMorphAlignedRow, mediaOrdinal: Int): Boolean {
    var index = 0
    while (index < row.cells.size) {
        if (row.cells[index].mediaOrdinal == mediaOrdinal) return true
        index++
    }
    return false
}

private fun buildMediaGridMorphExactTargetRows(
    capture: MediaGridMorphCapture,
    columnCount: Int,
): Pair<List<MediaGridMorphAlignedRow>, List<MediaGridMorphAlignedHeader>> {
    val exact = capture.exactTargetLayoutIndexes[columnCount]
        ?: return buildMediaGridMorphRowsForColumnCount(capture, columnCount)
    val mediaByOrdinal = capture.media.associateBy { it.mediaOrdinal }
    val rowIds = capture.media.asSequence()
        .mapNotNull { exact.rowIdForMediaOrdinal(it.mediaOrdinal) }
        .distinct()
        .sorted()
        .toList()
    val headers = ArrayList<MediaGridMorphAlignedHeader>()
    val rows = rowIds.mapNotNull { rowId ->
        val rowOrdinals = exact.rowMediaOrdinals(rowId) ?: return@mapNotNull null
        val cells = rowOrdinals.mapIndexed { column, ordinal ->
            val item = mediaByOrdinal[ordinal]
            val assetId = exact.assetIdByMediaOrdinal.getOrNull(ordinal)
                ?: item?.assetId
                ?: return@mapIndexed null
            MediaGridMorphCapturedCell(
                column = column,
                mediaOrdinal = ordinal,
                assetId = assetId,
                rect = Rect.Zero,
                isPartiallyVisible = false,
                itemIndex = exact.itemIndexByMediaOrdinal.getOrNull(ordinal) ?: item?.itemIndex ?: -1,
                itemKey = item?.itemKey,
                preparedImageIdentity = item?.preparedImageIdentity,
            )
        }.filterNotNull()
        if (cells.isEmpty()) return@mapNotNull null
        val header = exact.headerForRow(rowId)?.let {
            MediaGridMorphAlignedHeader(
                beforeRowIndex = rowId,
                key = it.key,
                title = it.title,
                firstMediaOrdinal = it.firstMediaOrdinal,
            ).also(headers::add)
        }
        MediaGridMorphAlignedRow(
            rowIndex = rowId,
            cells = cells,
            headerBefore = header,
            rowKey = MediaGridMorphSourceRowKey(
                canonicalStartItemIndex = exact.rowFirstItemIndex(rowId) ?: cells.first().itemIndex,
                canonicalStartMediaOrdinal = rowOrdinals.firstOrNull() ?: cells.first().mediaOrdinal,
                headerOrBucketKey = rowOrdinals.asSequence()
                    .mapNotNull(mediaByOrdinal::get)
                    .mapNotNull { media ->
                        mediaGridMorphBucketSpec(
                            media.xCreatedAt,
                            media.likeCount,
                            capture.sortBase,
                            columnCount,
                        )?.key
                    }
                    .firstOrNull(),
                currentColumnCount = columnCount,
            ),
        )
    }
    return rows to headers
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
                cells = rects.sortedBy { it.rect.left }.mapIndexedNotNull { column, rect ->
                    val assetId = rect.assetId
                        ?: capture.media.firstOrNull { it.mediaOrdinal == rect.mediaOrdinal }?.assetId
                        ?: return@mapIndexedNotNull null
                    MediaGridMorphCapturedCell(
                        column = column,
                        mediaOrdinal = rect.mediaOrdinal,
                        assetId = assetId,
                        rect = rect.rect,
                        isPartiallyVisible = rect.isPartiallyVisible,
                    )
                },
                isPartiallyVisible = rects.any { it.isPartiallyVisible },
                // visibleMediaRects comes directly from LazyGridLayoutInfo;
                // rows synthesized from it are actual viewport rows, not
                // canonical overscan rows.
                isActualVisibleSourceRow = true,
            )
        }

private fun normalizeCapturedSourceRows(
    rows: List<MediaGridMorphCapturedRow>,
    capture: MediaGridMorphCapture,
): List<MediaGridMorphCapturedRow> {
    val mediaByOrdinal = (capture.precedingMediaWindow + capture.media).associateBy { it.mediaOrdinal }
    return rows.map { row ->
        val cells = row.cells.map { cell ->
            val media = mediaByOrdinal[cell.mediaOrdinal]
            cell.copy(
                itemIndex = cell.itemIndex.takeIf { it >= 0 } ?: media?.itemIndex ?: -1,
                itemKey = cell.itemKey ?: media?.itemKey,
                preparedImageIdentity = cell.preparedImageIdentity ?: media?.preparedImageIdentity,
            )
        }
        val first = cells.minByOrNull { it.column }
        val startOrdinal = first?.let { it.mediaOrdinal - it.column }
        val startMedia = startOrdinal?.let(mediaByOrdinal::get)
        row.copy(
            cells = cells,
            rowKey = row.rowKey ?: first?.let {
                MediaGridMorphSourceRowKey(
                    canonicalStartItemIndex = startMedia?.itemIndex ?: it.itemIndex,
                    canonicalStartMediaOrdinal = startOrdinal ?: it.mediaOrdinal,
                    headerOrBucketKey = startMedia?.let { media ->
                        mediaGridMorphBucketSpec(
                            media.xCreatedAt,
                            media.likeCount,
                            capture.sortBase,
                            capture.identity.columnCount,
                        )?.key
                    },
                    currentColumnCount = capture.identity.columnCount,
                )
            },
        )
    }
}

private fun relativeRowAtViewportEdge(
    edgeY: Float,
    fixedFocalCenterY: Float,
    focalV: Float,
    cellSize: Float,
): Int {
    val focalRowTop = fixedFocalCenterY - focalV * cellSize
    return floor((edgeY - focalRowTop) / cellSize).toInt()
}

/** Header displacement before a media row relative to the fixed focal row. */
internal fun mediaGridMorphHeaderOffsetBefore(
    relativeRow: Int,
    headerHeightByRelativeRow: Map<Int, Float>,
): Float = when {
    relativeRow > 0 -> (1..relativeRow).fold(0f) { total, row -> total + (headerHeightByRelativeRow[row] ?: 0f) }
    relativeRow < 0 -> -(relativeRow + 1..0).fold(0f) { total, row -> total + (headerHeightByRelativeRow[row] ?: 0f) }
    else -> 0f
}

internal fun buildMediaGridMorphRowsForColumnCount(
    capture: MediaGridMorphCapture,
    columnCount: Int,
): Pair<List<MediaGridMorphAlignedRow>, List<MediaGridMorphAlignedHeader>> {
    val rows = ArrayList<MediaGridMorphAlignedRow>()
    val headers = ArrayList<MediaGridMorphAlignedHeader>()
    val boundedMedia = capture.media.distinctBy { it.mediaOrdinal }.sortedBy { it.mediaOrdinal }
    val mediaByOrdinal = (capture.precedingMediaWindow + capture.media)
        .associateBy { it.mediaOrdinal }
    var current = ArrayList<MediaGridMorphCapturedCell>(columnCount)
    var previousBucket: MediaGridMorphBucketSpec? = capture.precedingMedia?.let {
        mediaGridMorphBucketSpec(it.xCreatedAt, it.likeCount, capture.sortBase, columnCount)
    }
    var nextColumn = capture.startColumnOffset(columnCount)
    fun flush() {
        if (current.isNotEmpty()) {
            val first = current.minBy { it.column }
            val startOrdinal = first.mediaOrdinal - first.column
            val startMedia = mediaByOrdinal[startOrdinal]
            val bucketKey = startMedia?.let {
                mediaGridMorphBucketSpec(it.xCreatedAt, it.likeCount, capture.sortBase, columnCount)?.key
            }
            rows += MediaGridMorphAlignedRow(
                rowIndex = rows.size,
                cells = current.toList(),
                rowKey = MediaGridMorphSourceRowKey(
                    canonicalStartItemIndex = startMedia?.itemIndex ?: first.itemIndex,
                    canonicalStartMediaOrdinal = startOrdinal,
                    headerOrBucketKey = bucketKey,
                    currentColumnCount = columnCount,
                ),
            )
            current = ArrayList(columnCount)
            nextColumn = 0
        }
    }
    boundedMedia.forEach { item ->
        val bucket = mediaGridMorphBucketSpec(item.xCreatedAt, item.likeCount, capture.sortBase, columnCount)
        if (bucket != null && bucket.key != previousBucket?.key) {
            flush()
            val header = MediaGridMorphAlignedHeader(rows.size, "media_grid_header_${bucket.safeKey}", bucket.label, item.mediaOrdinal)
            headers += header
            current = ArrayList(columnCount)
            nextColumn = 0
            previousBucket = bucket
        }
        current += MediaGridMorphCapturedCell(
            column = nextColumn,
            mediaOrdinal = item.mediaOrdinal,
            assetId = item.assetId,
            rect = Rect.Zero,
            isPartiallyVisible = false,
            itemIndex = item.itemIndex,
            itemKey = item.itemKey,
            preparedImageIdentity = item.preparedImageIdentity,
        )
        nextColumn++
        if (nextColumn == columnCount) flush()
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

internal data class MediaGridMorphReleaseDecision(
    val direction: MediaGridMorphDirection?,
    val targetColumnCount: Int,
    val progress: Float,
)

/** One release rule shared by Morph and every one-step fallback path. */
internal fun mediaGridMorphCanonicalReleaseDecision(
    currentColumnCount: Int,
    initialDistance: Float,
    releaseDistance: Float,
    lockedDirection: MediaGridMorphDirection? = null,
): MediaGridMorphReleaseDecision {
    if (!initialDistance.isFinite() || !releaseDistance.isFinite() || initialDistance <= 0f || releaseDistance <= 0f) {
        return MediaGridMorphReleaseDecision(null, currentColumnCount, 0f)
    }
    val direction = lockedDirection ?: when {
        releaseDistance < initialDistance -> MediaGridMorphDirection.IncreaseColumns
        releaseDistance > initialDistance -> MediaGridMorphDirection.DecreaseColumns
        else -> null
    } ?: return MediaGridMorphReleaseDecision(null, currentColumnCount, 0f)
    val target = mediaGridMorphTargetColumnCount(currentColumnCount, direction)
    if (target == currentColumnCount) return MediaGridMorphReleaseDecision(direction, currentColumnCount, 0f)
    return MediaGridMorphReleaseDecision(
        direction = direction,
        targetColumnCount = target,
        progress = mediaGridMorphProgressForDistance(
            initialDistance = initialDistance,
            currentDistance = releaseDistance,
            fromColumnCount = currentColumnCount,
            toColumnCount = target,
        ),
    )
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
    plan: MediaGridMorphViewportPlan,
    cell: MediaGridMorphCellPlan,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f && cell.sourceRect != null) return cell.sourceRect
    val currentCellSize = mediaGridMorphCurrentCellSize(plan, p)
    val focalRowTop = plan.fixedFocalCenterY - plan.focalV * currentCellSize
    val targetAnchorAdjustment = plan.targetRowTopAdjustment * p
    val rowTop = focalRowTop +
        cell.relativeRow * currentCellSize +
        lerpRowEdge(cell.startHeaderOffsetPx, cell.endHeaderOffsetPx, p) +
        targetAnchorAdjustment
    val gridLeft = plan.viewport.left
    return Rect(
        gridLeft + cell.column * currentCellSize,
        rowTop,
        gridLeft + cell.column * currentCellSize + currentCellSize,
        rowTop + currentCellSize,
    )
}

internal fun mediaGridMorphCurrentCellSize(
    plan: MediaGridMorphViewportPlan,
    progress: Float,
): Float = lerpRowEdge(plan.sourceCellSize, plan.targetCellSize, progress.coerceIn(0f, 1f))

private fun lerpRowEdge(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress
