package com.lyco256.llm

import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot

internal enum class MediaGridMorphDirection {
    IncreaseColumns,
    DecreaseColumns,
}

internal enum class MediaGridMorphPhase {
    Idle,
    Tracking,
    SettlingToCurrent,
    SettlingToTarget,
    AwaitingGridHandoff,
}

/** The only per-media input copied from the current frame for morph preparation. */
internal data class MediaGridMorphCapturedMedia(
    val assetId: Long,
    val mediaOrdinal: Int,
    val itemIndex: Int,
    val xCreatedAt: String,
    val likeCount: Long?,
)

internal data class MediaGridMorphCapturedRect(
    val mediaOrdinal: Int,
    val rect: Rect,
)

internal data class MediaGridMorphCapturedHeaderRect(
    val firstMediaOrdinal: Int,
    val key: String,
    val rect: Rect,
)

internal data class MediaGridMorphPreparationIdentity(
    val sourceRevision: Long,
    val frameKey: MediaGridRenderKey,
    val columnCount: Int,
    val viewportSignature: MediaGridViewportSignature,
)

/**
 * Immutable, bounded main-thread capture. It contains no frame-wide collection,
 * resident state, Bitmap, painter, text layout, IO handle, or image request.
 */
internal data class MediaGridMorphCapture(
    val identity: MediaGridMorphPreparationIdentity,
    val viewport: Rect,
    val cellSizePx: Float,
    val headerHeightPx: Float,
    val sortBase: ClassifiedSortBase,
    val mediaOrdinalRange: IntRange,
    val media: List<MediaGridMorphCapturedMedia>,
    val precedingMedia: MediaGridMorphCapturedMedia?,
    val visibleMediaRects: List<MediaGridMorphCapturedRect>,
    val visibleHeaderRects: List<MediaGridMorphCapturedHeaderRect>,
)

internal data class MediaGridMorphMedia(
    val assetId: Long,
    val mediaOrdinal: Int,
    val itemIndex: Int,
    val row: Int,
    val column: Int,
    val rect: Rect,
)

internal data class MediaGridMorphHeader(
    val key: String,
    val title: String,
    val rect: Rect,
    val firstMediaOrdinal: Int,
    val firstAssetId: Long,
)

internal data class MediaGridMorphLayoutSnapshot(
    val columnCount: Int,
    val viewport: Rect,
    val media: List<MediaGridMorphMedia>,
    val headers: List<MediaGridMorphHeader>,
    val mediaOrdinalRange: IntRange,
)

/** Position-based correspondence for one local row/column slot. */
internal data class MediaGridMorphSlot(
    val row: Int,
    val column: Int,
    val startRect: Rect,
    val endRect: Rect,
    val startAssetId: Long?,
    val endAssetId: Long?,
    val startMediaOrdinal: Int?,
    val endMediaOrdinal: Int?,
)

/** One non-duplicated header background plus its title crossfade inputs. */
internal data class MediaGridMorphHeaderBand(
    val startKey: String?,
    val endKey: String?,
    val startTitle: String?,
    val endTitle: String?,
    val startRect: Rect,
    val endRect: Rect,
    val startFirstMediaOrdinal: Int?,
    val endFirstMediaOrdinal: Int?,
    val startFirstAssetId: Long?,
    val endFirstAssetId: Long?,
)

internal data class MediaGridMorphPreparedPair(
    val sourceRevision: Long,
    val frameKey: MediaGridRenderKey,
    val fromColumnCount: Int,
    val toColumnCount: Int,
    val viewport: Rect,
    val viewportSignature: MediaGridViewportSignature,
    val startLayout: MediaGridMorphLayoutSnapshot,
    val targetLayout: MediaGridMorphLayoutSnapshot,
    val slots: List<MediaGridMorphSlot>,
    val headers: List<MediaGridMorphHeaderBand>,
    val mediaOrdinalRange: IntRange,
)

internal data class MediaGridMorphAnchor(
    val assetId: Long,
    val fromViewportCenterY: Float,
    val slot: MediaGridMorphSlot,
)

/** Gesture-time wrapper. Slot and header templates are never rebuilt here. */
internal data class MediaGridMorphPlan(
    val preparedPair: MediaGridMorphPreparedPair,
    val anchor: MediaGridMorphAnchor?,
) {
    val fromColumnCount: Int get() = preparedPair.fromColumnCount
    val toColumnCount: Int get() = preparedPair.toColumnCount
    val viewport: Rect get() = preparedPair.viewport
    val slots: List<MediaGridMorphSlot> get() = preparedPair.slots
    val headers: List<MediaGridMorphHeaderBand> get() = preparedPair.headers
    val plannedItemCount: Int get() = slots.size

    companion object {
        fun select(preparedPair: MediaGridMorphPreparedPair, pinchCenter: Offset): MediaGridMorphPlan {
            val startSlots = preparedPair.slots.filter { it.startAssetId != null }
            val selected = startSlots.firstOrNull { pinchCenter in it.startRect }
                ?: startSlots.minByOrNull { distanceSquared(it.startRect.center, pinchCenter) }
            val anchor = selected?.startAssetId?.let { assetId ->
                MediaGridMorphAnchor(
                    assetId = assetId,
                    fromViewportCenterY = selected.startRect.center.y,
                    slot = selected,
                )
            }
            return MediaGridMorphPlan(preparedPair, anchor)
        }
    }
}

internal data class MediaGridMorphPreparationToken(
    val generation: Long,
    val identity: MediaGridMorphPreparationIdentity,
)

/**
 * Non-Compose cache. Publishing prepared pairs never invalidates the LazyGrid.
 * A newer identity invalidates every older in-flight calculation.
 */
internal class MediaGridMorphPreparationCache {
    private val nextGeneration = AtomicLong(0L)
    private val latestToken = AtomicReference<MediaGridMorphPreparationToken?>(null)
    private val published = AtomicReference<Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>>(emptyMap())
    private var lastRequestedIdentity: MediaGridMorphPreparationIdentity? = null

    @Synchronized
    fun request(
        identity: MediaGridMorphPreparationIdentity,
        isScrollInProgress: Boolean,
        isPointerInProgress: Boolean,
    ): MediaGridMorphPreparationToken? {
        if (isScrollInProgress || isPointerInProgress || identity == lastRequestedIdentity) return null
        val token = MediaGridMorphPreparationToken(nextGeneration.incrementAndGet(), identity)
        lastRequestedIdentity = identity
        latestToken.set(token)
        return token
    }

    @Synchronized
    fun publish(
        token: MediaGridMorphPreparationToken,
        pairs: Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
    ): Boolean {
        if (latestToken.get() != token) return false
        if (pairs.values.any {
                it.sourceRevision != token.identity.sourceRevision ||
                    it.frameKey != token.identity.frameKey ||
                    it.fromColumnCount != token.identity.columnCount ||
                    it.viewportSignature != token.identity.viewportSignature
            }
        ) return false
        published.set(pairs.toMap())
        return true
    }

    fun snapshot(): Map<MediaGridMorphDirection, MediaGridMorphPreparedPair> = published.get()
}

internal object MediaGridMorphDefaults {
    const val DeadZoneScale: Float = 1.02f
    const val ReleaseThreshold: Float = 0.5f
    const val SettleDurationMillis: Long = 180L
    const val OverscanRows: Int = 2
}

/**
 * Captures only visible media plus two rows on each side by ordinal lookup.
 * This must be called on the main thread while layoutInfo is stable and idle.
 */
internal fun captureMediaGridMorphInput(
    frame: MediaGridFrameData,
    layoutInfo: LazyGridLayoutInfo,
    columnCount: Int,
    fallbackHeaderHeightPx: Float,
): MediaGridMorphCapture? {
    val signature = buildMediaGridViewportSignature(layoutInfo, frame, columnCount)
    if (
        signature.firstVisibleMediaOrdinal < 0 ||
        signature.lastVisibleMediaOrdinal < signature.firstVisibleMediaOrdinal ||
        signature.viewportWidthPx <= 0 ||
        signature.viewportHeightPx <= 0
    ) return null

    val ordinalIndex = frame.ordinalIndex
    val totalMedia = ordinalIndex.assetIdByMediaOrdinal.size
    if (totalMedia == 0) return null
    val ordinalRange = mediaGridMorphOrdinalRange(
        totalMedia = totalMedia,
        firstVisibleMediaOrdinal = signature.firstVisibleMediaOrdinal,
        lastVisibleMediaOrdinal = signature.lastVisibleMediaOrdinal,
        columnCount = columnCount,
    ) ?: return null
    val startOrdinal = ordinalRange.first
    val endOrdinal = ordinalRange.last
    val capturedMedia = ArrayList<MediaGridMorphCapturedMedia>(endOrdinal - startOrdinal + 1)

    fun capturedAt(ordinal: Int): MediaGridMorphCapturedMedia? {
        val itemIndex = ordinalIndex.itemIndexByMediaOrdinal.getOrNull(ordinal) ?: return null
        val item = frame.items.getOrNull(itemIndex) as? MediaGridCellItem ?: return null
        return MediaGridMorphCapturedMedia(
            assetId = ordinalIndex.assetIdByMediaOrdinal[ordinal],
            mediaOrdinal = ordinal,
            itemIndex = itemIndex,
            xCreatedAt = item.entry.xCreatedAt,
            likeCount = item.entry.likeCount,
        )
    }

    for (ordinal in startOrdinal..endOrdinal) {
        capturedAt(ordinal)?.let(capturedMedia::add)
    }
    if (capturedMedia.isEmpty()) return null

    val visibleMediaRects = ArrayList<MediaGridMorphCapturedRect>()
    val visibleHeaderRects = ArrayList<MediaGridMorphCapturedHeaderRect>()
    var measuredHeaderHeight = 0f
    for (info in layoutInfo.visibleItemsInfo) {
        val ordinal = ordinalIndex.mediaOrdinalByItemIndex.getOrNull(info.index) ?: -1
        val rect = Rect(
            info.offset.x.toFloat(),
            info.offset.y.toFloat(),
            (info.offset.x + info.size.width).toFloat(),
            (info.offset.y + info.size.height).toFloat(),
        )
        if (ordinal >= 0) {
            if (ordinal in startOrdinal..endOrdinal) {
                visibleMediaRects += MediaGridMorphCapturedRect(ordinal, rect)
            }
        } else {
            val header = frame.items.getOrNull(info.index) as? MediaGridHeaderItem ?: continue
            val nextOrdinal = ordinalIndex.mediaOrdinalByItemIndex.getOrNull(info.index + 1) ?: -1
            if (nextOrdinal >= 0 && nextOrdinal in startOrdinal..endOrdinal) {
                visibleHeaderRects += MediaGridMorphCapturedHeaderRect(nextOrdinal, header.key, rect)
                measuredHeaderHeight = maxOf(measuredHeaderHeight, rect.height)
            }
        }
    }

    return MediaGridMorphCapture(
        identity = MediaGridMorphPreparationIdentity(
            sourceRevision = frame.key.dataKey.sourceRevision,
            frameKey = frame.key,
            columnCount = columnCount,
            viewportSignature = signature,
        ),
        viewport = Rect(
            0f,
            layoutInfo.viewportStartOffset.toFloat(),
            layoutInfo.viewportSize.width.toFloat(),
            layoutInfo.viewportEndOffset.toFloat(),
        ),
        cellSizePx = signature.cellSizePx.coerceAtLeast(1).toFloat(),
        headerHeightPx = measuredHeaderHeight.takeIf { it > 0f }
            ?: fallbackHeaderHeightPx.coerceAtLeast(1f),
        sortBase = frame.key.dataKey.sort.baseOrder,
        mediaOrdinalRange = startOrdinal..endOrdinal,
        media = capturedMedia.toList(),
        precedingMedia = capturedAt(startOrdinal - 1),
        visibleMediaRects = visibleMediaRects.toList(),
        visibleHeaderRects = visibleHeaderRects.toList(),
    )
}

internal fun mediaGridMorphOrdinalRange(
    totalMedia: Int,
    firstVisibleMediaOrdinal: Int,
    lastVisibleMediaOrdinal: Int,
    columnCount: Int,
): IntRange? {
    if (
        totalMedia <= 0 ||
        firstVisibleMediaOrdinal !in 0 until totalMedia ||
        lastVisibleMediaOrdinal < firstVisibleMediaOrdinal
    ) return null
    val widestAdjacentColumnCount = (columnCount + 1)
        .coerceAtMost(ClassifiedMediaGridMaxColumnCount)
        .coerceAtLeast(columnCount)
    val ordinalPadding = widestAdjacentColumnCount * MediaGridMorphDefaults.OverscanRows
    return (firstVisibleMediaOrdinal - ordinalPadding).coerceAtLeast(0)..
        (lastVisibleMediaOrdinal + ordinalPadding).coerceAtMost(totalMedia - 1)
}

/** Pure Default-dispatcher builder shared by production and tests. */
internal fun buildMediaGridMorphPreparedPairs(
    capture: MediaGridMorphCapture,
): Map<MediaGridMorphDirection, MediaGridMorphPreparedPair> {
    if (capture.media.isEmpty()) return emptyMap()
    val pairs = LinkedHashMap<MediaGridMorphDirection, MediaGridMorphPreparedPair>(2)
    for (direction in MediaGridMorphDirection.entries) {
        val target = mediaGridMorphTargetColumnCount(capture.identity.columnCount, direction)
        if (target == capture.identity.columnCount) continue
        val start = buildMediaGridMorphLayout(capture, capture.identity.columnCount, useCapturedGeometry = true)
        val end = buildMediaGridMorphLayout(capture, target, useCapturedGeometry = false)
        pairs[direction] = MediaGridMorphPreparedPair(
            sourceRevision = capture.identity.sourceRevision,
            frameKey = capture.identity.frameKey,
            fromColumnCount = capture.identity.columnCount,
            toColumnCount = target,
            viewport = capture.viewport,
            viewportSignature = capture.identity.viewportSignature,
            startLayout = start,
            targetLayout = end,
            slots = buildMediaGridMorphSlots(start, end),
            headers = buildMediaGridMorphHeaderBands(start, end),
            mediaOrdinalRange = capture.mediaOrdinalRange,
        )
    }
    return pairs.toMap()
}

private fun buildMediaGridMorphLayout(
    capture: MediaGridMorphCapture,
    columnCount: Int,
    useCapturedGeometry: Boolean,
): MediaGridMorphLayoutSnapshot {
    val cellWidth = (capture.viewport.width / columnCount.coerceAtLeast(1)).coerceAtLeast(1f)
    val cellHeight = cellWidth
    val media = ArrayList<MediaGridMorphMedia>(capture.media.size)
    val headers = ArrayList<MediaGridMorphHeader>()
    val seenAssets = HashSet<Long>(capture.media.size)
    var previousBucket = capture.precedingMedia?.let {
        mediaGridMorphBucketSpec(it.xCreatedAt, it.likeCount, capture.sortBase, columnCount)
    }
    var row = 0
    var column = 0
    var y = 0f

    fun finishRow() {
        if (column != 0) {
            y += cellHeight
            row++
            column = 0
        }
    }

    for (item in capture.media) {
        if (!seenAssets.add(item.assetId)) continue
        val bucket = mediaGridMorphBucketSpec(item.xCreatedAt, item.likeCount, capture.sortBase, columnCount)
        if (bucket != null && bucket.key != previousBucket?.key) {
            finishRow()
            headers += MediaGridMorphHeader(
                key = "media_grid_header_${bucket.safeKey}",
                title = bucket.label,
                rect = Rect(capture.viewport.left, y, capture.viewport.right, y + capture.headerHeightPx),
                firstMediaOrdinal = item.mediaOrdinal,
                firstAssetId = item.assetId,
            )
            y += capture.headerHeightPx
            previousBucket = bucket
        }
        media += MediaGridMorphMedia(
            assetId = item.assetId,
            mediaOrdinal = item.mediaOrdinal,
            itemIndex = item.itemIndex,
            row = row,
            column = column,
            rect = Rect(
                capture.viewport.left + column * cellWidth,
                y,
                capture.viewport.left + (column + 1) * cellWidth,
                y + cellHeight,
            ),
        )
        column++
        if (column == columnCount) finishRow()
    }

    val firstVisible = capture.visibleMediaRects.minByOrNull { it.mediaOrdinal }
    val generatedAnchor = firstVisible?.let { actual ->
        media.firstOrNull { it.mediaOrdinal == actual.mediaOrdinal }?.rect
    }
    val shiftY = if (firstVisible != null && generatedAnchor != null) {
        firstVisible.rect.top - generatedAnchor.top
    } else {
        capture.viewport.top
    }
    var shiftedMedia = media.map { it.copy(rect = it.rect.translateY(shiftY)) }
    var shiftedHeaders = headers.map { it.copy(rect = it.rect.translateY(shiftY)) }
    if (useCapturedGeometry) {
        val mediaRects = capture.visibleMediaRects.associateBy { it.mediaOrdinal }
        val headerRects = capture.visibleHeaderRects.associateBy { it.firstMediaOrdinal }
        shiftedMedia = shiftedMedia.map { value ->
            mediaRects[value.mediaOrdinal]?.let { value.copy(rect = it.rect) } ?: value
        }
        shiftedHeaders = shiftedHeaders.map { value ->
            headerRects[value.firstMediaOrdinal]?.let { value.copy(rect = it.rect) } ?: value
        }
    }
    return MediaGridMorphLayoutSnapshot(
        columnCount = columnCount,
        viewport = capture.viewport,
        media = shiftedMedia,
        headers = shiftedHeaders,
        mediaOrdinalRange = capture.mediaOrdinalRange,
    )
}

private fun buildMediaGridMorphSlots(
    start: MediaGridMorphLayoutSnapshot,
    end: MediaGridMorphLayoutSnapshot,
): List<MediaGridMorphSlot> {
    val startRows = start.media.groupBy { it.row }
    val endRows = end.media.groupBy { it.row }
    val rowCount = maxOf(startRows.keys.maxOrNull() ?: -1, endRows.keys.maxOrNull() ?: -1) + 1
    val slots = ArrayList<MediaGridMorphSlot>()
    for (row in 0 until rowCount) {
        val startRow = startRows[row].orEmpty().sortedBy { it.column }
        val endRow = endRows[row].orEmpty().sortedBy { it.column }
        val columnCount = maxOf(startRow.size, endRow.size)
        for (column in 0 until columnCount) {
            val startMedia = startRow.getOrNull(column)
            val endMedia = endRow.getOrNull(column)
            if (startMedia == null && endMedia == null) continue
            val startRect = startMedia?.rect ?: rightEdgeZeroRect(start.viewport, endMedia!!.rect)
            val endRect = endMedia?.rect ?: rightEdgeZeroRect(end.viewport, startMedia!!.rect)
            slots += MediaGridMorphSlot(
                row = row,
                column = column,
                startRect = startRect,
                endRect = endRect,
                startAssetId = startMedia?.assetId,
                endAssetId = endMedia?.assetId,
                startMediaOrdinal = startMedia?.mediaOrdinal,
                endMediaOrdinal = endMedia?.mediaOrdinal,
            )
        }
    }
    return slots
}

private fun buildMediaGridMorphHeaderBands(
    start: MediaGridMorphLayoutSnapshot,
    end: MediaGridMorphLayoutSnapshot,
): List<MediaGridMorphHeaderBand> {
    val unusedStart = start.headers.toMutableList()
    val unusedEnd = end.headers.toMutableList()
    val matches = ArrayList<Pair<MediaGridMorphHeader?, MediaGridMorphHeader?>>()

    for (startHeader in start.headers) {
        val endHeader = unusedEnd.firstOrNull {
            it.firstMediaOrdinal == startHeader.firstMediaOrdinal
        } ?: continue
        unusedStart.remove(startHeader)
        unusedEnd.remove(endHeader)
        matches += startHeader to endHeader
    }
    for ((startHeader, endHeader) in matchHeadersByLocalOrder(unusedStart, unusedEnd)) {
        unusedStart.remove(startHeader)
        unusedEnd.remove(endHeader)
        matches += startHeader to endHeader
    }
    unusedStart.forEach { matches += it to null }
    unusedEnd.forEach { matches += null to it }

    return matches
        .map { (startHeader, endHeader) ->
            val startRect = startHeader?.rect ?: zeroHeightHeaderRect(start.viewport, endHeader!!.rect.top)
            val endRect = endHeader?.rect ?: zeroHeightHeaderRect(end.viewport, startHeader!!.rect.top)
            MediaGridMorphHeaderBand(
                startKey = startHeader?.key,
                endKey = endHeader?.key,
                startTitle = startHeader?.title,
                endTitle = endHeader?.title,
                startRect = startRect,
                endRect = endRect,
                startFirstMediaOrdinal = startHeader?.firstMediaOrdinal,
                endFirstMediaOrdinal = endHeader?.firstMediaOrdinal,
                startFirstAssetId = startHeader?.firstAssetId,
                endFirstAssetId = endHeader?.firstAssetId,
            )
        }
        .sortedBy { minOf(it.startRect.top, it.endRect.top) }
}

private fun matchHeadersByLocalOrder(
    startHeaders: List<MediaGridMorphHeader>,
    endHeaders: List<MediaGridMorphHeader>,
): List<Pair<MediaGridMorphHeader, MediaGridMorphHeader>> {
    if (startHeaders.isEmpty() || endHeaders.isEmpty()) return emptyList()
    if (startHeaders.size > endHeaders.size) {
        return matchHeadersByLocalOrder(endHeaders, startHeaders).map { (end, start) -> start to end }
    }
    val starts = startHeaders.sortedBy { it.rect.top }
    val ends = endHeaders.sortedBy { it.rect.top }
    val result = ArrayList<Pair<MediaGridMorphHeader, MediaGridMorphHeader>>(starts.size)
    var endCursor = 0
    starts.forEachIndexed { startIndex, start ->
        val remainingStarts = starts.size - startIndex - 1
        val lastCandidate = ends.lastIndex - remainingStarts
        var selectedIndex = endCursor
        var selectedDistance = Float.POSITIVE_INFINITY
        for (candidateIndex in endCursor..lastCandidate) {
            val distance = abs(ends[candidateIndex].rect.top - start.rect.top)
            if (distance < selectedDistance) {
                selectedDistance = distance
                selectedIndex = candidateIndex
            }
        }
        result += start to ends[selectedIndex]
        endCursor = selectedIndex + 1
    }
    return result
}

internal data class MediaGridMorphBucketSpec(
    val key: String,
    val label: String,
    val safeKey: String,
)

internal fun mediaGridMorphBucketSpec(
    xCreatedAt: String,
    likeCount: Long?,
    baseOrder: ClassifiedSortBase,
    columnCount: Int,
): MediaGridMorphBucketSpec? = when (baseOrder) {
    ClassifiedSortBase.Default -> null
    ClassifiedSortBase.PostTime -> mediaGridMorphPostTimeBucket(xCreatedAt, columnCount)
    ClassifiedSortBase.LikeCount -> mediaGridMorphLikeCountBucket(likeCount, columnCount)
}

private enum class MediaGridMorphDateGranularity { Day, Week, Month }

private fun mediaGridMorphPostTimeBucket(xCreatedAt: String, columnCount: Int): MediaGridMorphBucketSpec {
    val date = runCatching { Instant.parse(xCreatedAt).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: return MediaGridMorphBucketSpec("post_time_unknown", "日付不明", "post_time_unknown")
    return when (mediaGridMorphDateGranularity(columnCount)) {
        MediaGridMorphDateGranularity.Day -> MediaGridMorphBucketSpec(
            "post_time_day_$date",
            DateTimeFormatter.ofPattern("yyyy/MM/dd").format(date),
            "post_time_day_$date",
        )
        MediaGridMorphDateGranularity.Week -> {
            val weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            MediaGridMorphBucketSpec(
                "post_time_week_$weekStart",
                "${DateTimeFormatter.ofPattern("yyyy/MM/dd").format(weekStart)} ~ ${
                    DateTimeFormatter.ofPattern("yyyy/MM/dd").format(weekStart.plusDays(6))
                }",
                "post_time_week_$weekStart",
            )
        }
        MediaGridMorphDateGranularity.Month -> {
            val month = YearMonth.from(date)
            MediaGridMorphBucketSpec(
                "post_time_month_$month",
                DateTimeFormatter.ofPattern("yyyy/M").format(month),
                "post_time_month_$month",
            )
        }
    }
}

private fun mediaGridMorphLikeCountBucket(likeCount: Long?, columnCount: Int): MediaGridMorphBucketSpec = when {
    likeCount == null -> MediaGridMorphBucketSpec("like_count_unknown", "いいね数不明", "like_count_unknown")
    likeCount >= 100_000L -> MediaGridMorphBucketSpec("like_count_100000_plus", "10万以上", "like_count_100000_plus")
    else -> {
        val unit = when (columnCount.coerceIn(2, 12)) {
            2, 3, 4 -> 200L
            5, 6, 7, 8 -> 500L
            else -> 1_000L
        }
        val start = (likeCount / unit) * unit
        val end = start + unit - 1
        MediaGridMorphBucketSpec(
            "like_count_${unit}_$start",
            "${String.format(Locale.JAPAN, "%,d", start)}〜${String.format(Locale.JAPAN, "%,d", end)}",
            "like_count_${unit}_$start",
        )
    }
}

private fun mediaGridMorphDateGranularity(columnCount: Int): MediaGridMorphDateGranularity =
    when (columnCount.coerceIn(2, 12)) {
        2, 3, 4 -> MediaGridMorphDateGranularity.Day
        5, 6, 7, 8 -> MediaGridMorphDateGranularity.Week
        else -> MediaGridMorphDateGranularity.Month
    }

internal data class MediaGridMorphSettleResult(
    val session: MediaGridMorphSession,
    val progress: Float,
    val elapsedMillis: Long,
    val targetColumnCountToHandoff: Int? = null,
)

internal data class MediaGridMorphUiState(
    val session: MediaGridMorphSession,
    val handoffAnchor: MediaGridMorphAnchor? = null,
    val correction: Offset = Offset.Zero,
    val handoffCompleted: Boolean = false,
)

internal fun MediaGridMorphUiState.idle(currentColumnCount: Int): MediaGridMorphUiState = copy(
    session = session.cancelForSourceChange(currentColumnCount),
    handoffAnchor = null,
    correction = Offset.Zero,
    handoffCompleted = false,
)

internal data class MediaGridMorphSession(
    val phase: MediaGridMorphPhase,
    val fromColumnCount: Int,
    val toColumnCount: Int,
    val direction: MediaGridMorphDirection?,
    val pinchCenter: Offset?,
    val sourceRevision: Long?,
    val plan: MediaGridMorphPlan?,
    val targetHandoffDispatched: Boolean = false,
) {
    companion object {
        fun idle(currentColumnCount: Int): MediaGridMorphSession = MediaGridMorphSession(
            phase = MediaGridMorphPhase.Idle,
            fromColumnCount = currentColumnCount,
            toColumnCount = currentColumnCount,
            direction = null,
            pinchCenter = null,
            sourceRevision = null,
            plan = null,
        )

        fun begin(
            currentColumnCount: Int,
            scale: Float,
            pinchCenter: Offset,
            sourceRevision: Long,
            preparedPair: MediaGridMorphPreparedPair,
            deadZoneScale: Float = MediaGridMorphDefaults.DeadZoneScale,
        ): MediaGridMorphSession? {
            val direction = mediaGridMorphDirectionForScale(scale, deadZoneScale) ?: return null
            val target = mediaGridMorphTargetColumnCount(currentColumnCount, direction)
            if (
                target == currentColumnCount ||
                preparedPair.sourceRevision != sourceRevision ||
                preparedPair.fromColumnCount != currentColumnCount ||
                preparedPair.toColumnCount != target
            ) return null
            return MediaGridMorphSession(
                phase = MediaGridMorphPhase.Tracking,
                fromColumnCount = currentColumnCount,
                toColumnCount = target,
                direction = direction,
                pinchCenter = pinchCenter,
                sourceRevision = sourceRevision,
                plan = MediaGridMorphPlan.select(preparedPair, pinchCenter),
            )
        }
    }

    fun updateTracking(currentSourceRevision: Long): MediaGridMorphSession {
        if (phase != MediaGridMorphPhase.Tracking) return this
        return if (sourceRevision == currentSourceRevision) this else idle(fromColumnCount)
    }

    fun release(progress: Float): MediaGridMorphSession = when (phase) {
        MediaGridMorphPhase.Tracking -> copy(
            phase = if (progress < MediaGridMorphDefaults.ReleaseThreshold) {
                MediaGridMorphPhase.SettlingToCurrent
            } else {
                MediaGridMorphPhase.SettlingToTarget
            },
        )
        else -> this
    }

    fun advanceSettle(
        currentProgress: Float,
        elapsedMillis: Long,
        currentSourceRevision: Long,
        durationMillis: Long = MediaGridMorphDefaults.SettleDurationMillis,
    ): MediaGridMorphSettleResult {
        if (phase != MediaGridMorphPhase.SettlingToCurrent && phase != MediaGridMorphPhase.SettlingToTarget) {
            return MediaGridMorphSettleResult(this, currentProgress, 0L)
        }
        if (sourceRevision != currentSourceRevision) return MediaGridMorphSettleResult(idle(fromColumnCount), 0f, 0L)
        val duration = durationMillis.coerceAtLeast(1L)
        val nextElapsed = elapsedMillis.coerceAtLeast(0L).coerceAtMost(duration)
        val fraction = nextElapsed.toFloat() / duration
        val targetProgress = if (phase == MediaGridMorphPhase.SettlingToTarget) 1f else 0f
        val nextProgress = currentProgress + (targetProgress - currentProgress) * fraction
        if (nextElapsed < duration) return MediaGridMorphSettleResult(this, nextProgress, nextElapsed)
        if (phase == MediaGridMorphPhase.SettlingToCurrent) {
            return MediaGridMorphSettleResult(idle(fromColumnCount), 0f, nextElapsed)
        }
        val next = copy(phase = MediaGridMorphPhase.AwaitingGridHandoff, targetHandoffDispatched = true)
        return MediaGridMorphSettleResult(
            next,
            1f,
            nextElapsed,
            targetColumnCountToHandoff = toColumnCount.takeUnless { targetHandoffDispatched },
        )
    }

    fun cancelForSourceChange(currentColumnCount: Int): MediaGridMorphSession = idle(currentColumnCount)

    fun completeGridHandoff(): MediaGridMorphSession = idle(toColumnCount)
}

internal fun mediaGridMorphTargetColumnCount(
    currentColumnCount: Int,
    direction: MediaGridMorphDirection,
): Int = when (direction) {
    MediaGridMorphDirection.IncreaseColumns ->
        (currentColumnCount + 1).coerceAtMost(ClassifiedMediaGridMaxColumnCount)
    MediaGridMorphDirection.DecreaseColumns ->
        (currentColumnCount - 1).coerceAtLeast(ClassifiedMediaGridMinColumnCount)
}

internal fun mediaGridMorphDirectionForScale(
    scale: Float,
    deadZoneScale: Float = MediaGridMorphDefaults.DeadZoneScale,
): MediaGridMorphDirection? {
    if (!scale.isFinite() || scale <= 0f || deadZoneScale <= 1f) return null
    return when {
        scale >= deadZoneScale -> MediaGridMorphDirection.IncreaseColumns
        scale <= 1f / deadZoneScale -> MediaGridMorphDirection.DecreaseColumns
        else -> null
    }
}

internal fun mediaGridMorphProgressForScale(
    scale: Float,
    direction: MediaGridMorphDirection,
    deadZoneScale: Float = MediaGridMorphDefaults.DeadZoneScale,
): Float {
    if (!scale.isFinite() || scale <= 0f || deadZoneScale <= 1f) return 0f
    val inverseDeadZone = 1f / deadZoneScale
    val raw = when (direction) {
        MediaGridMorphDirection.IncreaseColumns ->
            (scale - deadZoneScale) / (deadZoneScale * (deadZoneScale - 1f))
        MediaGridMorphDirection.DecreaseColumns ->
            (inverseDeadZone - scale) / (1f - inverseDeadZone)
    }
    return raw.coerceIn(0f, 1f)
}

internal fun mediaGridColumnCountAfterPinchRelease(
    currentColumnCount: Int,
    accumulatedScale: Float?,
    deadZoneScale: Float = MediaGridMorphDefaults.DeadZoneScale,
    releaseThreshold: Float = MediaGridMorphDefaults.ReleaseThreshold,
): Int {
    if (accumulatedScale == null || !accumulatedScale.isFinite() || releaseThreshold !in 0f..1f) {
        return currentColumnCount
    }
    val direction = mediaGridMorphDirectionForScale(accumulatedScale, deadZoneScale) ?: return currentColumnCount
    val progress = mediaGridMorphProgressForScale(accumulatedScale, direction, deadZoneScale)
    if (progress < releaseThreshold) return currentColumnCount
    return mediaGridMorphTargetColumnCount(currentColumnCount, direction)
}

internal fun mediaGridMorphRect(slot: MediaGridMorphSlot, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        morphLerp(slot.startRect.left, slot.endRect.left, p),
        morphLerp(slot.startRect.top, slot.endRect.top, p),
        morphLerp(slot.startRect.right, slot.endRect.right, p),
        morphLerp(slot.startRect.bottom, slot.endRect.bottom, p),
    )
}

internal fun mediaGridMorphStartAlpha(slot: MediaGridMorphSlot, progress: Float): Float = when {
    slot.startAssetId == null -> 0f
    slot.startAssetId == slot.endAssetId -> 1f
    else -> 1f - progress.coerceIn(0f, 1f)
}

internal fun mediaGridMorphEndAlpha(slot: MediaGridMorphSlot, progress: Float): Float = when {
    slot.endAssetId == null || slot.startAssetId == slot.endAssetId -> 0f
    else -> progress.coerceIn(0f, 1f)
}

internal fun mediaGridMorphLayerCount(slot: MediaGridMorphSlot): Int = when {
    slot.startAssetId == null && slot.endAssetId == null -> 0
    slot.startAssetId == null || slot.endAssetId == null || slot.startAssetId == slot.endAssetId -> 1
    else -> 2
}

internal fun mediaGridMorphHeaderRect(header: MediaGridMorphHeaderBand, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        morphLerp(header.startRect.left, header.endRect.left, p),
        morphLerp(header.startRect.top, header.endRect.top, p),
        morphLerp(header.startRect.right, header.endRect.right, p),
        morphLerp(header.startRect.bottom, header.endRect.bottom, p),
    )
}

internal fun mediaGridMorphHeaderY(header: MediaGridMorphHeaderBand, progress: Float): Float =
    mediaGridMorphHeaderRect(header, progress).top

internal fun mediaGridMorphHeaderHeight(header: MediaGridMorphHeaderBand, progress: Float): Float =
    mediaGridMorphHeaderRect(header, progress).height.coerceAtLeast(0f)

internal fun mediaGridMorphStartTitleAlpha(header: MediaGridMorphHeaderBand, progress: Float): Float = when {
    header.startTitle == null -> 0f
    header.startTitle == header.endTitle -> 1f
    else -> 1f - progress.coerceIn(0f, 1f)
}

internal fun mediaGridMorphEndTitleAlpha(header: MediaGridMorphHeaderBand, progress: Float): Float = when {
    header.endTitle == null || header.startTitle == header.endTitle -> 0f
    else -> progress.coerceIn(0f, 1f)
}

internal fun mediaGridMorphTitleLayerCount(header: MediaGridMorphHeaderBand): Int = when {
    header.startTitle == null && header.endTitle == null -> 0
    header.startTitle == null || header.endTitle == null || header.startTitle == header.endTitle -> 1
    else -> 2
}

private fun rightEdgeZeroRect(viewport: Rect, reference: Rect): Rect =
    Rect(viewport.right, reference.top, viewport.right, reference.bottom)

private fun zeroHeightHeaderRect(viewport: Rect, top: Float): Rect =
    Rect(viewport.left, top, viewport.right, top)

private fun Rect.translateY(delta: Float): Rect =
    Rect(left, top + delta, right, bottom + delta)

private operator fun Rect.contains(point: Offset): Boolean =
    point.x in left..right && point.y in top..bottom

private fun distanceSquared(first: Offset, second: Offset): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return dx * dx + dy * dy
}

private fun morphLerp(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress

internal fun distanceBetween(first: Offset, second: Offset): Float =
    hypot(first.x - second.x, first.y - second.y)
