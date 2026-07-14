package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Direction selected once for one two-finger gesture. */
internal enum class MediaGridMorphDirection {
    IncreaseColumns,
    DecreaseColumns,
}

/** State of the gesture and the handoff to the normal grid. */
internal enum class MediaGridMorphPhase {
    Idle,
    Tracking,
    SettlingToCurrent,
    SettlingToTarget,
    AwaitingGridHandoff,
}

/** A render-independent media item used while preparing a local morph plan. */
internal data class MediaGridMorphMedia(
    val assetKey: String,
    val itemIndex: Int,
    val rect: Rect,
)

/** A render-independent header item used while preparing a local morph plan. */
internal data class MediaGridMorphHeader(
    val key: String,
    val title: String,
    val rect: Rect,
)

internal sealed interface MediaGridMorphLayoutItem {
    val rect: Rect

    data class Media(
        val media: MediaGridMorphMedia,
    ) : MediaGridMorphLayoutItem {
        override val rect: Rect get() = media.rect
    }

    data class Header(
        val header: MediaGridMorphHeader,
    ) : MediaGridMorphLayoutItem {
        override val rect: Rect get() = header.rect
    }
}

/** One layout snapshot. Snapshots are made once at gesture start and never rebuilt per progress update. */
internal data class MediaGridMorphLayoutSnapshot(
    val columnCount: Int,
    val viewport: Rect,
    val items: List<MediaGridMorphLayoutItem>,
)

/** Start/end correspondence for one screen slot. */
internal data class MediaGridMorphSlot(
    val startRect: Rect,
    val endRect: Rect,
    val startAssetKey: String?,
    val endAssetKey: String?,
    val startItemIndex: Int?,
    val endItemIndex: Int?,
    val hasStart: Boolean,
    val hasEnd: Boolean,
)

/** Start/end correspondence for one subtitle band. */
internal data class MediaGridMorphHeaderBand(
    val key: String,
    val startTitle: String?,
    val endTitle: String?,
    val startY: Float,
    val endY: Float,
    val startHeight: Float,
    val endHeight: Float,
    val hasStart: Boolean,
    val hasEnd: Boolean,
)

/** The media asset kept near the pinch center while the two plans are interpolated. */
internal data class MediaGridMorphAnchor(
    val assetKey: String,
    val fromViewportCenterY: Float,
    val fromSlot: MediaGridMorphSlot,
    val toSlot: MediaGridMorphSlot,
)

/** The bounded work product retained for one morph session. */
internal data class MediaGridMorphPlan(
    val fromColumnCount: Int,
    val toColumnCount: Int,
    val viewport: Rect,
    val slots: List<MediaGridMorphSlot>,
    val headers: List<MediaGridMorphHeaderBand>,
    val anchor: MediaGridMorphAnchor?,
) {
    val plannedItemCount: Int get() = slots.count { it.hasStart || it.hasEnd }

    companion object {
        fun create(
            from: MediaGridMorphLayoutSnapshot,
            to: MediaGridMorphLayoutSnapshot,
            pinchCenter: Offset,
            overscanRows: Int = MediaGridMorphDefaults.OverscanRows,
        ): MediaGridMorphPlan {
            val fromMedia = from.mediaItems()
            val toMedia = to.mediaItems()
            val rowHeight = listOf(fromMedia, toMedia)
                .flatten()
                .map { it.rect.height }
                .filter { it > 0f }
                .maxOrNull()
                ?.coerceAtLeast(1f)
                ?: 1f
            val fromScope = scoped(from, rowHeight, overscanRows)
            val toScope = scoped(to, rowHeight, overscanRows)
            val fromRows = rowMedia(fromScope)
            val toRows = rowMedia(toScope)
            val rowCount = max(fromRows.size, toRows.size)
            val slotCountPerRow = max(from.columnCount, to.columnCount).coerceAtLeast(1)
            val usedStart = HashSet<String>()
            val usedEnd = HashSet<String>()
            val slots = ArrayList<MediaGridMorphSlot>(rowCount * slotCountPerRow)

            repeat(rowCount) { rowIndex ->
                val startRow = fromRows.getOrNull(rowIndex).orEmpty()
                val endRow = toRows.getOrNull(rowIndex).orEmpty()
                repeat(slotCountPerRow) { slotIndex ->
                    val start = startRow.getOrNull(slotIndex)?.takeUnless { !usedStart.add(it.assetKey) }
                    val end = endRow.getOrNull(slotIndex)?.takeUnless { !usedEnd.add(it.assetKey) }
                    if (start == null && end == null) return@repeat
                    val startRect = start?.rect ?: zeroWidthRectAtRight(to.viewport, end?.rect ?: Rect.Zero)
                    val endRect = end?.rect ?: zeroWidthRectAtRight(from.viewport, start?.rect ?: Rect.Zero)
                    slots += MediaGridMorphSlot(
                        startRect = startRect,
                        endRect = endRect,
                        startAssetKey = start?.assetKey,
                        endAssetKey = end?.assetKey,
                        startItemIndex = start?.itemIndex,
                        endItemIndex = end?.itemIndex,
                        hasStart = start != null,
                        hasEnd = end != null,
                    )
                }
            }

            val headerKeys = linkedSetOf<String>()
            scopedHeaders(from, rowHeight, overscanRows).forEach { headerKeys += it.header.key }
            scopedHeaders(to, rowHeight, overscanRows).forEach { headerKeys += it.header.key }
            val fromHeaders = from.headers().associateBy { it.key }
            val toHeaders = to.headers().associateBy { it.key }
            val rawHeaders = headerKeys.map { key ->
                val start = fromHeaders[key]
                val end = toHeaders[key]
                MediaGridMorphHeaderBand(
                    key = key,
                    startTitle = start?.title,
                    endTitle = end?.title,
                    startY = start?.rect?.top ?: end?.rect?.top ?: from.viewport.top,
                    endY = end?.rect?.top ?: start?.rect?.top ?: to.viewport.top,
                    startHeight = start?.rect?.height ?: 0f,
                    endHeight = end?.rect?.height ?: 0f,
                    hasStart = start != null,
                    hasEnd = end != null,
                )
            }

            val preliminary = MediaGridMorphPlan(from.columnCount, to.columnCount, from.viewport, slots, rawHeaders, null)
            val anchor = chooseAnchor(preliminary, pinchCenter, fromMedia, toMedia)
            if (anchor == null) return preliminary
            val shiftY = anchor.fromViewportCenterY - slotCenterY(anchor.toSlot.endRect)
            if (abs(shiftY) < 0.01f) return preliminary.copy(anchor = anchor)
            val shiftedSlots = slots.map { it.copy(endRect = it.endRect.translateY(shiftY)) }
            val shiftedHeaders = rawHeaders.map { it.copy(endY = it.endY + shiftY) }
            val shiftedPlan = preliminary.copy(slots = shiftedSlots, headers = shiftedHeaders)
            return shiftedPlan.copy(anchor = chooseAnchor(shiftedPlan, pinchCenter, fromMedia, toMedia))
        }
    }
}

internal data class MediaGridMorphSettleResult(
    val session: MediaGridMorphSession,
    val targetColumnCountToHandoff: Int? = null,
)

/** Immutable state machine. It has no Compose, image, file, or data-source work. */
internal data class MediaGridMorphSession(
    val phase: MediaGridMorphPhase,
    val fromColumnCount: Int,
    val toColumnCount: Int,
    val direction: MediaGridMorphDirection?,
    val progress: Float,
    val pinchCenter: Offset?,
    val sourceRevision: Long?,
    val plan: MediaGridMorphPlan?,
    val settleElapsedMillis: Long = 0L,
    val targetHandoffDispatched: Boolean = false,
) {
    companion object {
        fun idle(currentColumnCount: Int): MediaGridMorphSession = MediaGridMorphSession(
            phase = MediaGridMorphPhase.Idle,
            fromColumnCount = currentColumnCount,
            toColumnCount = currentColumnCount,
            direction = null,
            progress = 0f,
            pinchCenter = null,
            sourceRevision = null,
            plan = null,
        )

        fun begin(
            currentColumnCount: Int,
            scale: Float,
            pinchCenter: Offset,
            sourceRevision: Long,
            fromPlan: MediaGridMorphLayoutSnapshot,
            toPlan: MediaGridMorphLayoutSnapshot,
            deadZoneScale: Float = MediaGridMorphDefaults.DeadZoneScale,
        ): MediaGridMorphSession? {
            val direction = mediaGridMorphDirectionForScale(scale, deadZoneScale) ?: return null
            val target = mediaGridMorphTargetColumnCount(currentColumnCount, direction)
            if (target == currentColumnCount) return null
            val plan = MediaGridMorphPlan.create(fromPlan, toPlan, pinchCenter)
            return MediaGridMorphSession(
                phase = MediaGridMorphPhase.Tracking,
                fromColumnCount = currentColumnCount,
                toColumnCount = target,
                direction = direction,
                progress = mediaGridMorphProgressForScale(scale, direction, deadZoneScale),
                pinchCenter = pinchCenter,
                sourceRevision = sourceRevision,
                plan = plan,
            )
        }
    }

    fun updateTracking(
        scale: Float,
        currentSourceRevision: Long,
        deadZoneScale: Float = MediaGridMorphDefaults.DeadZoneScale,
    ): MediaGridMorphSession {
        if (phase != MediaGridMorphPhase.Tracking) return this
        if (sourceRevision != currentSourceRevision) return idle(fromColumnCount)
        return copy(progress = mediaGridMorphProgressForScale(scale, direction!!, deadZoneScale))
    }

    fun release(): MediaGridMorphSession = when (phase) {
        MediaGridMorphPhase.Tracking -> copy(
            phase = if (progress < MediaGridMorphDefaults.ReleaseThreshold) {
                MediaGridMorphPhase.SettlingToCurrent
            } else {
                MediaGridMorphPhase.SettlingToTarget
            },
            settleElapsedMillis = 0L,
        )
        else -> this
    }

    fun advanceSettle(
        elapsedMillis: Long,
        currentSourceRevision: Long,
        durationMillis: Long = MediaGridMorphDefaults.SettleDurationMillis,
    ): MediaGridMorphSettleResult {
        if (phase != MediaGridMorphPhase.SettlingToCurrent && phase != MediaGridMorphPhase.SettlingToTarget) {
            return MediaGridMorphSettleResult(this)
        }
        if (sourceRevision != currentSourceRevision) return MediaGridMorphSettleResult(idle(fromColumnCount))
        val duration = durationMillis.coerceAtLeast(1L)
        val nextElapsed = (settleElapsedMillis + elapsedMillis.coerceAtLeast(0L)).coerceAtMost(duration)
        val fraction = nextElapsed.toFloat() / duration
        val targetProgress = if (phase == MediaGridMorphPhase.SettlingToTarget) 1f else 0f
        val nextProgress = progress + (targetProgress - progress) * fraction
        val reached = nextElapsed >= duration
        if (!reached) return MediaGridMorphSettleResult(copy(progress = nextProgress, settleElapsedMillis = nextElapsed))
        if (phase == MediaGridMorphPhase.SettlingToCurrent) {
            return MediaGridMorphSettleResult(idle(fromColumnCount))
        }
        val next = copy(
            phase = MediaGridMorphPhase.AwaitingGridHandoff,
            progress = 1f,
            settleElapsedMillis = nextElapsed,
            targetHandoffDispatched = true,
        )
        return MediaGridMorphSettleResult(next, targetColumnCountToHandoff = toColumnCount.takeUnless { targetHandoffDispatched })
    }

    fun cancelForSourceChange(currentColumnCount: Int): MediaGridMorphSession = idle(currentColumnCount)

    fun completeGridHandoff(): MediaGridMorphSession = idle(toColumnCount)
}

internal object MediaGridMorphDefaults {
    const val DeadZoneScale: Float = 1.12f
    const val ReleaseThreshold: Float = 0.5f
    const val SettleDurationMillis: Long = 180L
    const val OverscanRows: Int = 2
}

internal fun mediaGridMorphTargetColumnCount(
    currentColumnCount: Int,
    direction: MediaGridMorphDirection,
): Int = when (direction) {
    MediaGridMorphDirection.IncreaseColumns -> (currentColumnCount + 1).coerceAtMost(ClassifiedMediaGridMaxColumnCount)
    MediaGridMorphDirection.DecreaseColumns -> (currentColumnCount - 1).coerceAtLeast(ClassifiedMediaGridMinColumnCount)
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

/** Converts pinch excursion to a reversible bounded progress without changing the real column count. */
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

private fun MediaGridMorphLayoutSnapshot.mediaItems(): List<MediaGridMorphMedia> = items
    .asSequence()
    .mapNotNull { (it as? MediaGridMorphLayoutItem.Media)?.media }
    .sortedWith(compareBy<MediaGridMorphMedia> { it.rect.top }.thenBy { it.rect.left })
    .toList()

private fun MediaGridMorphLayoutSnapshot.headers(): List<MediaGridMorphHeader> = items
    .asSequence()
    .mapNotNull { (it as? MediaGridMorphLayoutItem.Header)?.header }
    .toList()

private fun scoped(
    snapshot: MediaGridMorphLayoutSnapshot,
    rowHeight: Float,
    overscanRows: Int,
): List<MediaGridMorphLayoutItem> {
    val extra = rowHeight * overscanRows.coerceAtLeast(0)
    val top = snapshot.viewport.top - extra
    val bottom = snapshot.viewport.bottom + extra
    val media = snapshot.mediaItems().filter { it.rect.bottom >= top && it.rect.top <= bottom }
    val firstMediaTop = media.minOfOrNull { it.rect.top }
    val relatedHeaders = snapshot.headers().filter { header ->
        header.rect.bottom >= top && header.rect.top <= bottom ||
            firstMediaTop != null && header.rect.top <= firstMediaTop
    }
    val precedingHeader = firstMediaTop?.let { mediaTop ->
        snapshot.headers().filter { it.rect.top <= mediaTop }.maxByOrNull { it.rect.top }
    }
    val headerItems = (relatedHeaders + listOfNotNull(precedingHeader)).distinctBy { it.key }
    val mediaItems = media.map { MediaGridMorphLayoutItem.Media(it) }
    return headerItems.map { MediaGridMorphLayoutItem.Header(it) } + mediaItems
}

private fun scopedHeaders(
    snapshot: MediaGridMorphLayoutSnapshot,
    rowHeight: Float,
    overscanRows: Int,
): List<MediaGridMorphLayoutItem.Header> = scoped(snapshot, rowHeight, overscanRows)
    .mapNotNull { it as? MediaGridMorphLayoutItem.Header }

private fun rowMedia(items: List<MediaGridMorphLayoutItem>): List<List<MediaGridMorphMedia>> {
    val media = items.mapNotNull { (it as? MediaGridMorphLayoutItem.Media)?.media }
        .sortedWith(compareBy<MediaGridMorphMedia> { it.rect.top }.thenBy { it.rect.left })
    if (media.isEmpty()) return emptyList()
    val rows = ArrayList<MutableList<MediaGridMorphMedia>>()
    media.forEach { item ->
        val row = rows.lastOrNull()
        if (row == null || abs(row.first().rect.top - item.rect.top) > max(2f, item.rect.height * 0.2f)) {
            rows.add(ArrayList())
        }
        rows.last().add(item)
    }
    return rows
}

private fun chooseAnchor(
    plan: MediaGridMorphPlan,
    pinchCenter: Offset,
    fromMedia: List<MediaGridMorphMedia>,
    toMedia: List<MediaGridMorphMedia>,
): MediaGridMorphAnchor? {
    val from = fromMedia.minByOrNull { distanceSquared(it.rect.center, pinchCenter) } ?: return null
    val fromSlot = plan.slots.firstOrNull { it.startAssetKey == from.assetKey } ?: return null
    val same = toMedia.firstOrNull { it.assetKey == from.assetKey }
    val target = same ?: toMedia.minByOrNull { distanceSquared(it.rect.center, pinchCenter) } ?: return null
    val toSlot = plan.slots.firstOrNull { it.endAssetKey == target.assetKey } ?: fromSlot
    return MediaGridMorphAnchor(
        assetKey = from.assetKey,
        fromViewportCenterY = slotCenterY(fromSlot.startRect),
        fromSlot = fromSlot,
        toSlot = toSlot,
    )
}

private fun distanceSquared(first: Offset, second: Offset): Float = hypot(first.x - second.x, first.y - second.y)
    .let { it * it }

private fun slotCenterY(rect: Rect): Float = (rect.top + rect.bottom) / 2f

private fun zeroWidthRectAtRight(viewport: Rect, reference: Rect): Rect = Rect(
    left = viewport.right,
    top = reference.top,
    right = viewport.right,
    bottom = reference.bottom,
)

private fun Rect.translateY(delta: Float): Rect = Rect(left, top + delta, right, bottom + delta)
