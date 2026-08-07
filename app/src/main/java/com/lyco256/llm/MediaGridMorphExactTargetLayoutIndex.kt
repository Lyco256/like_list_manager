package com.lyco256.llm

import androidx.compose.ui.geometry.Rect

/**
 * Compact, frame-wide geometry index for one adjacent target column count.
 * It contains only primitive row/item geometry and header metadata; media
 * entries, Bitmaps, and resident images are never copied into the index.
 */
internal data class MediaGridMorphExactTargetHeader(
    val itemIndex: Int,
    val firstMediaOrdinal: Int,
    val key: String,
    val title: String,
    val topAtScrollZero: Float,
    val height: Float,
)

internal data class MediaGridMorphExactTargetLayoutIndex(
    val sourceFrameKey: MediaGridRenderKey,
    val targetFrameKey: MediaGridRenderKey,
    val targetColumnCount: Int,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val cellSizePx: Float,
    val columnLeftPx: IntArray,
    val columnWidthPx: IntArray,
    val rowHeightPx: Float,
    val assetIdByMediaOrdinal: LongArray,
    val itemIndexByMediaOrdinal: IntArray,
    val rowIdByMediaOrdinal: IntArray,
    val rowFirstItemIndex: IntArray,
    val rowMediaOrdinalStart: IntArray,
    val rowMediaOrdinalCount: IntArray,
    val rowMediaOrdinals: IntArray,
    val rowTopAtScrollZero: FloatArray,
    val rowHeaderIndex: IntArray,
    val headers: List<MediaGridMorphExactTargetHeader>,
    val contentHeightPx: Float,
    val maxScrollPx: Float,
) {
    fun rowIdForMediaOrdinal(mediaOrdinal: Int): Int? =
        rowIdByMediaOrdinal.getOrNull(mediaOrdinal)?.takeIf { it >= 0 }

    fun rowFirstItemIndex(rowId: Int): Int? = rowFirstItemIndex.getOrNull(rowId)

    fun rowTopAtScrollZero(rowId: Int): Float? = rowTopAtScrollZero.getOrNull(rowId)

    fun rowMediaOrdinals(rowId: Int): IntArray? {
        val start = rowMediaOrdinalStart.getOrNull(rowId) ?: return null
        val count = rowMediaOrdinalCount.getOrNull(rowId) ?: return null
        if (start < 0 || count < 0 || start + count > rowMediaOrdinals.size) return null
        return rowMediaOrdinals.copyOfRange(start, start + count)
    }

    fun headerForRow(rowId: Int): MediaGridMorphExactTargetHeader? =
        rowHeaderIndex.getOrNull(rowId)?.takeIf { it >= 0 }?.let(headers::getOrNull)

    /**
     * Returns the target row top that can actually be achieved by a
     * LazyGrid. All values are local to the LazyGrid viewport.
     */
    fun achievableRowTop(rowId: Int, desiredRowTopViewportLocal: Float): Float? {
        val rowTop = rowTopAtScrollZero(rowId) ?: return null
        return desiredRowTopViewportLocal.coerceIn(
            rowTop - maxScrollPx,
            rowTop,
        )
    }
}

/**
 * Builds the target item sequence without constructing a target frame. The
 * source frame's stable entry order is reused, while target bucket boundaries
 * and item indexes are calculated from the target column count.
 */
internal fun buildMediaGridMorphExactTargetLayoutIndex(
    frame: MediaGridFrameData,
    targetColumnCount: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    headerHeightPx: Float,
): MediaGridMorphExactTargetLayoutIndex {
    val columns = targetColumnCount.coerceAtLeast(1)
    val cellSize = (viewportWidthPx.toFloat() / columns).coerceAtLeast(1f)
    val width = viewportWidthPx.coerceAtLeast(columns)
    val baseColumnWidth = width / columns
    val extraColumnWidths = width % columns
    val columnWidths = IntArray(columns) { column ->
        baseColumnWidth + if (column < extraColumnWidths) 1 else 0
    }
    val columnLefts = IntArray(columns)
    for (column in 1 until columns) columnLefts[column] = columnLefts[column - 1] + columnWidths[column - 1]
    val rowHeight = columnWidths.firstOrNull()?.toFloat() ?: cellSize
    val height = headerHeightPx.coerceAtLeast(0f)
    val entries = frame.items
        .asSequence()
        .filterIsInstance<MediaGridCellItem>()
        .sortedBy { it.sourceIndex }
        .map { it.entry }
        .toList()

    val itemIndexByOrdinal = IntArray(entries.size) { -1 }
    val rowIdByOrdinal = IntArray(entries.size) { -1 }
    val rowFirstItemIndex = ArrayList<Int>()
    val rowMediaOrdinalStart = ArrayList<Int>()
    val rowMediaOrdinalCount = ArrayList<Int>()
    val rowTopAtScrollZero = ArrayList<Float>()
    val rowHeaderIndex = ArrayList<Int>()
    val rowMediaOrdinals = ArrayList<Int>(entries.size)
    val headers = ArrayList<MediaGridMorphExactTargetHeader>()

    var itemIndex = 0
    var mediaOrdinal = 0
    var contentY = 0f
    var previousBucketKey: String? = null
    var currentRowId = -1
    var currentRowStart = 0
    var pendingHeaderIndex = -1

    fun flushRow() {
        if (currentRowId < 0) return
        rowMediaOrdinalCount += rowMediaOrdinals.size - currentRowStart
        contentY += rowHeight
        currentRowId = -1
    }

    entries.forEach { entry ->
        val bucket = mediaGridMorphBucketSpec(
            entry.xCreatedAt,
            entry.likeCount,
            frame.key.dataKey.sort.baseOrder,
            columns,
        )
        if (bucket != null && bucket.key != previousBucketKey) {
            flushRow()
            headers += MediaGridMorphExactTargetHeader(
                itemIndex = itemIndex++,
                firstMediaOrdinal = mediaOrdinal,
                key = "media_grid_header_${bucket.safeKey}",
                title = bucket.label,
                topAtScrollZero = contentY,
                height = height,
            )
            contentY += height
            pendingHeaderIndex = headers.lastIndex
            previousBucketKey = bucket.key
        }
        if (currentRowId < 0) {
            currentRowId = rowFirstItemIndex.size
            currentRowStart = rowMediaOrdinals.size
            rowFirstItemIndex += itemIndex
            rowMediaOrdinalStart += rowMediaOrdinals.size
            rowTopAtScrollZero += contentY
            rowHeaderIndex += pendingHeaderIndex
            pendingHeaderIndex = -1
        }
        itemIndexByOrdinal[mediaOrdinal] = itemIndex
        rowIdByOrdinal[mediaOrdinal] = currentRowId
        rowMediaOrdinals += mediaOrdinal
        itemIndex++
        mediaOrdinal++
        if (rowMediaOrdinals.size - currentRowStart == columns) flushRow()
    }
    flushRow()

    val contentHeight = contentY.coerceAtLeast(0f)
    val viewportHeight = viewportHeightPx.coerceAtLeast(0)
    return MediaGridMorphExactTargetLayoutIndex(
        sourceFrameKey = frame.key,
        targetFrameKey = frame.key.copy(columnCount = columns),
        targetColumnCount = columns,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeight,
        cellSizePx = cellSize,
        columnLeftPx = columnLefts,
        columnWidthPx = columnWidths,
        rowHeightPx = rowHeight,
        assetIdByMediaOrdinal = entries.map { it.assetId }.toLongArray(),
        itemIndexByMediaOrdinal = itemIndexByOrdinal,
        rowIdByMediaOrdinal = rowIdByOrdinal,
        rowFirstItemIndex = rowFirstItemIndex.toIntArray(),
        rowMediaOrdinalStart = rowMediaOrdinalStart.toIntArray(),
        rowMediaOrdinalCount = rowMediaOrdinalCount.toIntArray(),
        rowMediaOrdinals = rowMediaOrdinals.toIntArray(),
        rowTopAtScrollZero = rowTopAtScrollZero.toFloatArray(),
        rowHeaderIndex = rowHeaderIndex.toIntArray(),
        headers = headers.toList(),
        contentHeightPx = contentHeight,
        maxScrollPx = (contentHeight - viewportHeight).coerceAtLeast(0f),
    )
}

/**
 * Pure full-viewport verification for the target LazyGrid layout. The scroll
 * value is inferred from the first visible exact row, then every visible row,
 * cell, and header is compared against the compact target index.
 */
internal fun validateMediaGridMorphExactTargetViewport(
    index: MediaGridMorphExactTargetLayoutIndex,
    visible: MediaGridMorphVisibleViewportGeometry,
    tolerancePx: Float = 1f,
): Boolean {
    val viewport = visible.viewport
    if (viewport.width <= 0f || viewport.height <= 0f) return false
    fun intersects(top: Float, bottom: Float): Boolean =
        bottom > viewport.top && top < viewport.bottom

    fun close(left: Float, right: Float): Boolean = kotlin.math.abs(left - right) <= tolerancePx
    fun rectClose(actual: Rect, expected: Rect): Boolean =
        close(actual.left, expected.left) &&
            close(actual.top, expected.top) &&
            close(actual.right, expected.right) &&
            close(actual.bottom, expected.bottom)

    val observedHeaderHeights = visible.headers.associate { it.itemIndex to it.rect.height }
    fun headerHeightDelta(header: MediaGridMorphExactTargetHeader): Float =
        observedHeaderHeights[header.itemIndex]?.minus(header.height) ?: 0f
    fun adjustedHeaderTop(header: MediaGridMorphExactTargetHeader): Float =
        header.topAtScrollZero + index.headers
            .asSequence()
            .takeWhile { it.itemIndex < header.itemIndex }
            .fold(0f) { total, previous -> total + headerHeightDelta(previous) }
    fun adjustedRowTop(rowId: Int): Float? {
        val baseTop = index.rowTopAtScrollZero(rowId) ?: return null
        val firstItemIndex = index.rowFirstItemIndex(rowId) ?: return null
        return baseTop + index.headers
            .asSequence()
            .takeWhile { it.itemIndex < firstItemIndex }
            .fold(0f) { total, previous -> total + headerHeightDelta(previous) }
    }
    val scroll = visible.rows.firstOrNull()?.let { row ->
        adjustedRowTop(row.rowIndex)?.minus(row.rowTop)
    } ?: visible.headers.firstOrNull()?.let { header ->
        index.headers.firstOrNull { it.itemIndex == header.itemIndex }
            ?.let(::adjustedHeaderTop)
            ?.minus(header.rect.top)
    } ?: 0f

    val expectedRowIds = index.rowTopAtScrollZero.indices.filter { rowId ->
        val top = (adjustedRowTop(rowId) ?: return@filter false) - scroll
        intersects(top, top + index.rowHeightPx)
    }
    val actualRows = visible.rows.sortedBy { it.rowIndex }
    if (actualRows.map { it.rowIndex } != expectedRowIds) return false
    actualRows.forEach { row ->
        val rowTop = adjustedRowTop(row.rowIndex) ?: return false
        val expectedOrdinals = index.rowMediaOrdinals(row.rowIndex) ?: return false
        if (row.mediaOrdinals != expectedOrdinals.toList()) return false
        if (!close(row.rowTop, rowTop - scroll) ||
            !close(row.cellWidth, index.cellSizePx) ||
            !close(row.cellHeight, index.cellSizePx)
        ) return false
        val cells = row.cells.sortedBy { it.mediaOrdinal }
        if (cells.size != expectedOrdinals.size) return false
        cells.forEachIndexed { column, cell ->
            val ordinal = expectedOrdinals[column]
            val expectedItemIndex = index.itemIndexByMediaOrdinal.getOrNull(ordinal)
                ?: return false
            val expectedRect = Rect(
                left = index.columnLeftPx[column].toFloat(),
                top = rowTop - scroll,
                right = (index.columnLeftPx[column] + index.columnWidthPx[column]).toFloat(),
                bottom = rowTop - scroll + index.rowHeightPx,
            )
            if (cell.mediaOrdinal != ordinal || cell.itemIndex != expectedItemIndex ||
                !rectClose(cell.rect, expectedRect)
            ) return false
        }
    }

    val expectedHeaders = index.headers.filter { header ->
        val top = adjustedHeaderTop(header) - scroll
        val height = observedHeaderHeights[header.itemIndex] ?: header.height
        intersects(top, top + height)
    }
    val actualHeaders = visible.headers.sortedBy { it.itemIndex }
    if (actualHeaders.map { it.itemIndex } != expectedHeaders.map { it.itemIndex }) return false
    actualHeaders.zip(expectedHeaders).forEach { (actual, expected) ->
        val expectedTop = adjustedHeaderTop(expected)
        val expectedHeight = observedHeaderHeights[expected.itemIndex] ?: expected.height
        val expectedRect = Rect(
            left = viewport.left,
            top = expectedTop - scroll,
            right = viewport.right,
            bottom = expectedTop - scroll + expectedHeight,
        )
        if (actual.key != expected.key || actual.title != expected.title ||
            !rectClose(actual.rect, expectedRect)
        ) return false
    }
    return true
}
