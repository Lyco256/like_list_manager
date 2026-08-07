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
) {
    fun select(initialPinchCenter: Offset): MediaGridMorphViewportPlan {
        // Only rows actually intersecting the captured source viewport may
        // select a plan or participate in source validation.  The canonical
        // row list is intentionally allowed to contain bounded overscan rows
        // that are not one-to-one with this list.
        val visibleSourceRows = sourceRows.filter { it.isActualVisibleSourceRow }
        if (visibleSourceRows.isEmpty()) return emptyPlan(initialPinchCenter)
        val visibleSourceRow = visibleSourceRows.firstOrNull { row ->
            initialPinchCenter.y >= row.top && initialPinchCenter.y <= row.bottom
        } ?: visibleSourceRows.minByOrNull { row ->
            abs((row.top + row.bottom) / 2f - initialPinchCenter.y)
        } ?: return emptyPlan(initialPinchCenter)
        val visibleRowsByKey = visibleSourceRows.groupBy { it.rowKey }
        val canonicalRowsByKey = sourceCanonicalRows.groupBy { it.rowKey }
        visibleSourceRows.forEach { row ->
            val key = row.rowKey ?: return emptyPlan(initialPinchCenter)
            val matches = canonicalRowsByKey[key].orEmpty()
            if (matches.size != 1 || !sourceRowMatchesCanonical(row, matches.single())) {
                return emptyPlan(initialPinchCenter)
            }
        }
        val sourceCanonicalRowIndex = visibleSourceRow.rowKey?.let { key ->
            sourceCanonicalRows.indexOfFirst { it.rowKey == key }
        } ?: -1
        if (sourceCanonicalRowIndex < 0) return emptyPlan(initialPinchCenter)
        val focalCell = visibleSourceRow.cells.minByOrNull { cell ->
            val dx = cell.rect.center.x - initialPinchCenter.x
            val dy = cell.rect.center.y - initialPinchCenter.y
            dx * dx + dy * dy
        } ?: return emptyPlan(initialPinchCenter)
        val fixedFocalCenterY = viewport.top + initialPinchCenter.y
        val sourceCellSize = (viewport.width / fromColumnCount.coerceAtLeast(1)).coerceAtLeast(1f)
        val targetCellSize = (viewport.width / toColumnCount.coerceAtLeast(1)).coerceAtLeast(1f)
        val focalV = ((fixedFocalCenterY - visibleSourceRow.top) / sourceCellSize).coerceIn(0f, 1f)

        val directTargetRowIndex = targetRows.indexOfFirst { row ->
            row.cells.any { it.mediaOrdinal == focalCell.mediaOrdinal }
        }
        val targetOrdinal = focalCell.mediaOrdinal
        val targetRowIndex = directTargetRowIndex.takeIf { it >= 0 } ?: return emptyPlan(initialPinchCenter)
        val targetFocalCell = targetRows[targetRowIndex].cells
            .firstOrNull { it.mediaOrdinal == targetOrdinal }
            ?: return emptyPlan(initialPinchCenter)
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
        val startHeaderHeights = headerPlans.associate { it.relativeRow to it.startHeightPx }
        val endHeaderHeights = headerPlans.associate { it.relativeRow to it.endHeightPx }
        val exactTargetRowOffsets = targetRows.mapNotNull { row ->
            val exactRowId = exactTargetLayoutIndex?.let { index ->
                row.cells.firstOrNull()?.mediaOrdinal?.let(index::rowIdForMediaOrdinal)
            }
            val exactTop = exactRowId?.let { rowId -> exactTargetLayoutIndex?.rowTopAtScrollZero(rowId) }
            if (
                exactRowId == null ||
                    exactTop == null ||
                    exactTargetRowId == null ||
                    exactTargetRowTopAtScrollZero == null
            ) {
                null
            } else {
                val relative = exactRowId - exactTargetRowId
                relative to (exactTop - exactTargetRowTopAtScrollZero - relative * targetCellSize)
            }
        }.toMap()
        val rowPlans = (minRelative..maxRelative).map { relativeRow ->
            val canonicalSource = sourceCanonicalRows.getOrNull(sourceCanonicalRowIndex + relativeRow)
            val source = canonicalSource?.let { candidate ->
                val visible = candidate.rowKey?.let { key ->
                    visibleRowsByKey[key]?.singleOrNull()
                }
                if (visible == null) candidate else MediaGridMorphAlignedRow(
                    rowIndex = candidate.rowIndex,
                    cells = visible.cells,
                    headerBefore = candidate.headerBefore,
                    rowKey = candidate.rowKey,
                )
            }
            val target = targetRows.getOrNull(targetRowIndex + relativeRow)
            MediaGridMorphRowPlan(
                relativeRow = relativeRow,
                cells = buildCellPlans(
                    relativeRow = relativeRow,
                    source = source,
                    target = target,
                    startHeaderOffsetPx = mediaGridMorphHeaderOffsetBefore(relativeRow, startHeaderHeights),
                    endHeaderOffsetPx = exactTargetRowOffsets[relativeRow]
                        ?: mediaGridMorphHeaderOffsetBefore(relativeRow, endHeaderHeights),
                ),
            )
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
            targetFocalMediaOrdinal = targetFocalCell?.mediaOrdinal,
            targetFocalAssetId = targetFocalCell?.assetId,
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
        val sourceByColumn = source?.cells?.associateBy { it.column }.orEmpty()
        val targetByColumn = target?.cells?.associateBy { it.column }.orEmpty()
        return (0 until count).map { column ->
            val captured = sourceByColumn[column]
            val targetCell = targetByColumn[column]
            MediaGridMorphCellPlan(
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
        }
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
        val sourceByRelative = sourceHeaders.mapNotNull { header ->
            val rowIndex = sourceRows.indexOfFirst { row ->
                row.cells.any { it.mediaOrdinal == header.firstMediaOrdinal }
            }
            if (rowIndex < 0) null else (rowIndex - sourceRowIndex) to header
        }.toMap()
        val targetByRelative = targetRows.mapNotNull { row ->
            val header = row.headerBefore ?: return@mapNotNull null
            val exactRowId = exactTargetLayoutIndex?.let { index ->
                row.cells.firstOrNull()?.mediaOrdinal?.let(index::rowIdForMediaOrdinal)
            }
            val relative = if (exactRowId != null && exactTargetRowId != null) {
                exactRowId - exactTargetRowId
            } else {
                row.rowIndex - targetRowIndex
            }
            relative to header
        }.toMap()
        val rawPlans = (relativeRange.first..relativeRange.last + 1).mapNotNull { relative ->
            val source = sourceByRelative[relative]
            val target = targetByRelative[relative]
            if (source == null && target == null) return@mapNotNull null
            MediaGridMorphHeaderPlan(
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
        }
        val startHeights = rawPlans.associate { it.relativeRow to it.startHeightPx }
        val endHeights = rawPlans.associate { it.relativeRow to it.endHeightPx }
        return rawPlans.map { plan ->
            val targetHeaderFirstOrdinal = plan.endKey?.let { key ->
                targetRows.firstOrNull { it.headerBefore?.key == key }
                    ?.headerBefore?.firstMediaOrdinal
            }
            val exactTargetHeader = if (targetHeaderFirstOrdinal != null) {
                exactTargetLayoutIndex?.headers?.firstOrNull {
                    it.key == plan.endKey && it.firstMediaOrdinal == targetHeaderFirstOrdinal
                }
            } else null
            val exactEndOffset = if (
                exactTargetHeader != null &&
                    exactTargetRowTopAtScrollZero != null &&
                    exactTargetRowId != null
            ) {
                exactTargetHeader.topAtScrollZero - exactTargetRowTopAtScrollZero -
                    plan.relativeRow * targetCellSize
            } else {
                null
            }
            plan.copy(
                startOffsetBeforePx = mediaGridMorphHeaderOffsetBefore(plan.relativeRow, startHeights) - plan.startHeightPx,
                endOffsetBeforePx = exactEndOffset
                    ?: mediaGridMorphHeaderOffsetBefore(plan.relativeRow, endHeights) - plan.endHeightPx,
            )
        }
    }
}

private fun sourceRowMatchesCanonical(
    visible: MediaGridMorphCapturedRow,
    canonical: MediaGridMorphAlignedRow?,
): Boolean {
    if (canonical == null || visible.rowKey != canonical.rowKey) return false
    val visibleCells = visible.cells.sortedBy { it.column }
    val canonicalByColumn = canonical.cells.associateBy { it.column }
    return visibleCells.all { actual ->
        val expected = canonicalByColumn[actual.column] ?: return@all false
        actual.mediaOrdinal == expected.mediaOrdinal && actual.assetId == expected.assetId
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
        exactTargetLayoutIndex = capture.exactTargetLayoutIndexes[toColumnCount],
    )
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
