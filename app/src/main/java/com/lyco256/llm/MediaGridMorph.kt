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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

internal enum class MediaGridMorphDirection {
    IncreaseColumns,
    DecreaseColumns,
}

internal enum class MediaGridMorphPhase {
    Idle,
    Tracking,
    SettlingToCurrent,
    SettlingToTarget,
    RevealingCurrent,
    AwaitingGridHandoff,
    RevealingTarget,
    Failed,
}

internal enum class MediaGridMorphDrawMode {
    Normal,
    Morph,
    RevealCurrent,
    RevealTarget,
}

/** The only per-media input copied from the current frame for morph preparation. */
internal data class MediaGridMorphCapturedMedia(
    val assetId: Long,
    val mediaOrdinal: Int,
    val itemIndex: Int,
    val xCreatedAt: String,
    val likeCount: Long?,
    val itemKey: String? = null,
    val preparedImageIdentity: MediaGridResidentImageIdentity? = null,
)

internal data class MediaGridMorphCapturedRect(
    val mediaOrdinal: Int,
    val rect: Rect,
    val assetId: Long? = null,
    val isPartiallyVisible: Boolean = false,
    val itemIndex: Int = -1,
    val itemKey: String? = null,
)

internal data class MediaGridMorphCapturedHeaderRect(
    val firstMediaOrdinal: Int,
    val key: String,
    val rect: Rect,
    val title: String = key,
    val itemIndex: Int = -1,
)

internal data class MediaGridMorphCapturedCell(
    val column: Int,
    val mediaOrdinal: Int,
    val assetId: Long,
    val rect: Rect,
    val isPartiallyVisible: Boolean,
    val itemIndex: Int = -1,
    val itemKey: String? = null,
    val preparedImageIdentity: MediaGridResidentImageIdentity? = null,
)

/** Identity of one current-column row in the real frame item sequence. */
internal data class MediaGridMorphSourceRowKey(
    val canonicalStartItemIndex: Int,
    val canonicalStartMediaOrdinal: Int,
    val headerOrBucketKey: String?,
    val currentColumnCount: Int,
)

/** Claim-time LazyGrid scroll state used to verify the current reveal. */
internal data class MediaGridMorphSourceViewportAnchor(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

internal data class MediaGridMorphCandidateViewportState(
    val sourceViewportAnchor: MediaGridMorphSourceViewportAnchor,
    val lightweightViewportSignature: MediaGridViewportAnchorSignature,
)

/** One actual source row captured from LazyGridLayoutInfo at Morph claim. */
internal data class MediaGridMorphCapturedRow(
    val visibleRow: Int,
    val top: Float,
    val bottom: Float,
    val cells: List<MediaGridMorphCapturedCell>,
    val isPartiallyVisible: Boolean,
    val rowKey: MediaGridMorphSourceRowKey? = null,
    val isActualVisibleSourceRow: Boolean = false,
)

internal data class MediaGridMorphPreparationIdentity(
    val sourceRevision: Long,
    val frameKey: MediaGridRenderKey,
    val columnCount: Int,
    val viewportSignature: MediaGridViewportSignature,
)

internal fun MediaGridMorphPreparationIdentity.toInteractionIdentity() =
    MediaGridMorphInteractionIdentity(
        sourceRevision = sourceRevision,
        frameKey = frameKey,
        currentColumnCount = columnCount,
        viewportSignature = viewportSignature,
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
    /** Up to eleven media immediately before [mediaOrdinalRange.first]. */
    val precedingMediaWindow: List<MediaGridMorphCapturedMedia> = emptyList(),
    /** Index is columnCount - 2 for the supported 2..12 column range. */
    val startColumnOffsetByColumnCount: List<Int> = emptyList(),
    val visibleMediaRects: List<MediaGridMorphCapturedRect>,
    val visibleHeaderRects: List<MediaGridMorphCapturedHeaderRect>,
    val sourceRows: List<MediaGridMorphCapturedRow> = emptyList(),
    val totalMediaCount: Int = mediaOrdinalRange.last + 1,
    val preparedIndexVersion: Long? = null,
    val exactTargetLayoutIndexes: Map<Int, MediaGridMorphExactTargetLayoutIndex> = emptyMap(),
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

internal sealed interface MediaGridMorphSlotContent {
    data class Image(val assetId: Long) : MediaGridMorphSlotContent
    /** A dataset slot with no media; it is the only row-plan endpoint allowed to be empty. */
    data object NoMedia : MediaGridMorphSlotContent
    /** Compatibility endpoint used by the legacy pure slot tests. */
    data object Placeholder : MediaGridMorphSlotContent
}

internal fun MediaGridMorphSlotContent.assetIdOrNull(): Long? =
    (this as? MediaGridMorphSlotContent.Image)?.assetId

internal enum class MediaGridMorphSlotEdge {
    None,
    Increase,
    Decrease,
}

private fun Long?.toMorphSlotContent(): MediaGridMorphSlotContent =
    this?.let(MediaGridMorphSlotContent::Image) ?: MediaGridMorphSlotContent.Placeholder

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
    val startContent: MediaGridMorphSlotContent = startAssetId.toMorphSlotContent(),
    val endContent: MediaGridMorphSlotContent = endAssetId.toMorphSlotContent(),
    val startImageDrawingRect: Rect = startRect,
    val endImageDrawingRect: Rect = endRect,
    val edge: MediaGridMorphSlotEdge = when {
        startAssetId == null && endAssetId != null -> MediaGridMorphSlotEdge.Increase
        startAssetId != null && endAssetId == null -> MediaGridMorphSlotEdge.Decrease
        else -> MediaGridMorphSlotEdge.None
    },
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
    val viewportPlanTemplate: MediaGridMorphViewportPlanTemplate? = null,
)

internal data class MediaGridMorphAnchor(
    val assetId: Long,
    val slot: MediaGridMorphSlot,
    val focalU: Float,
    val focalV: Float,
    val initialPinchCenter: Offset,
)

/** Gesture-time wrapper. Slot and header templates are never rebuilt here. */
internal data class MediaGridMorphPlan(
    val preparedPair: MediaGridMorphPreparedPair,
    val anchor: MediaGridMorphAnchor?,
    val initialPinchCenter: Offset = preparedPair.viewport.center,
    private val selectedViewportPlan: MediaGridMorphViewportPlan? =
        preparedPair.viewportPlanTemplate?.select(anchor?.initialPinchCenter ?: initialPinchCenter),
) {
    val fromColumnCount: Int get() = preparedPair.fromColumnCount
    val toColumnCount: Int get() = preparedPair.toColumnCount
    val viewport: Rect get() = preparedPair.viewport
    val slots: List<MediaGridMorphSlot> get() = preparedPair.slots
    val headers: List<MediaGridMorphHeaderBand> get() = preparedPair.headers
    val plannedItemCount: Int get() = slots.size
    val viewportPlan: MediaGridMorphViewportPlan? get() = selectedViewportPlan

    companion object {
        fun select(preparedPair: MediaGridMorphPreparedPair, pinchCenter: Offset): MediaGridMorphPlan {
            val viewportPinchCenter = pinchCenter + preparedPair.viewport.topLeft
            var selected: MediaGridMorphSlot? = null
            var selectedDistance = Float.POSITIVE_INFINITY
            for (slot in preparedPair.slots) {
                val rect = slot.startRect
                if (slot.startAssetId == null || rect.width <= 0f || rect.height <= 0f) continue
                val containsCenter = viewportPinchCenter in rect
                val distance = distanceSquared(rect.center, viewportPinchCenter)
                if (containsCenter || selected == null || distance < selectedDistance) {
                    selected = slot
                    selectedDistance = if (containsCenter) -1f else distance
                    if (containsCenter) break
                }
            }
            val anchor = selected?.startAssetId?.let { assetId ->
                val rect = selected.startRect
                MediaGridMorphAnchor(
                    assetId = assetId,
                    slot = selected,
                    focalU = (viewportPinchCenter.x - rect.left) / rect.width,
                    focalV = (viewportPinchCenter.y - rect.top) / rect.height,
                    initialPinchCenter = pinchCenter,
                )
            }
            return MediaGridMorphPlan(
                preparedPair = preparedPair,
                anchor = anchor,
                initialPinchCenter = pinchCenter,
                selectedViewportPlan = preparedPair.viewportPlanTemplate?.select(pinchCenter),
            )
        }

        fun selectRowReflow(preparedPair: MediaGridMorphPreparedPair, pinchCenter: Offset): MediaGridMorphPlan =
            MediaGridMorphPlan(
                preparedPair = preparedPair,
                anchor = null,
                initialPinchCenter = pinchCenter,
                selectedViewportPlan = preparedPair.viewportPlanTemplate?.select(pinchCenter),
            )
    }
}

internal data class MediaGridMorphPreparationToken(
    val generation: Long,
    val identity: MediaGridMorphPreparationIdentity,
    val sourceViewportAnchor: MediaGridMorphSourceViewportAnchor? = null,
    val lightweightViewportSignature: MediaGridViewportAnchorSignature? = null,
)

internal data class MediaGridMorphResourceReadiness(
    val requiredAssetCount: Int,
    val resolvedAssetCount: Int,
    val firstMissingAssetId: Long?,
    val requiredTitleCount: Int,
    val resolvedTitleCount: Int,
    val firstMissingTitle: String?,
) {
    val isReady: Boolean
        get() = requiredAssetCount == resolvedAssetCount &&
            requiredTitleCount == resolvedTitleCount

    companion object {
        val Empty = MediaGridMorphResourceReadiness(
            requiredAssetCount = 0,
            resolvedAssetCount = 0,
            firstMissingAssetId = null,
            requiredTitleCount = 0,
            resolvedTitleCount = 0,
            firstMissingTitle = null,
        )
    }
}

/** One immutable selected plan for one distinct visible source row. */
internal data class MediaGridMorphPreparedFocalEntry(
    val sourceRowKey: MediaGridMorphSourceRowKey,
    val pinchYMinInclusive: Float,
    val pinchYMaxExclusive: Float,
    val plan: MediaGridMorphPlan,
    val requiredRenderSet: MediaGridMorphRequiredRenderSet,
    val requiredSourceAssetIds: LongArray,
    val requiredTargetAssetIds: LongArray,
    val requiredHeaderTitles: List<String>,
    val geometryComplete: Boolean,
    val sourceViewportComplete: Boolean,
    val exactTargetRowId: Int?,
    val exactTargetRowFirstItemIndex: Int?,
    val exactTargetRowMediaOrdinals: IntArray,
) {
    val geometryReady: Boolean get() = geometryComplete && sourceViewportComplete
}

internal data class MediaGridMorphStableIdleDirectionSnapshot(
    val direction: MediaGridMorphDirection,
    val pair: MediaGridMorphPreparedPair,
    val focalEntries: List<MediaGridMorphPreparedFocalEntry>,
    val focalEntryUpperBounds: FloatArray,
) {
    /** Bounded primitive lookup; no row, plan, or RequiredRenderSet is rebuilt. */
    fun focalEntryForPinchY(pinchY: Float): MediaGridMorphPreparedFocalEntry? {
        if (!pinchY.isFinite() || focalEntries.isEmpty()) return null
        var index = 0
        while (index < focalEntryUpperBounds.size) {
            if (pinchY < focalEntryUpperBounds[index]) return focalEntries.getOrNull(index)
            index++
        }
        return focalEntries.lastOrNull()
    }
}

/**
 * The one bounded, non-Compose stable-idle cache entry for the current
 * viewport. It owns immutable geometry and only copies membership state when
 * resident images or measured titles become ready.
 */
internal data class MediaGridMorphStableIdleReadySnapshot(
    val identity: MediaGridMorphPreparationIdentity,
    val sourceViewportAnchor: MediaGridMorphSourceViewportAnchor,
    val lightweightViewportSignature: MediaGridViewportAnchorSignature,
    val capture: MediaGridMorphCapture,
    val directions: Map<MediaGridMorphDirection, MediaGridMorphStableIdleDirectionSnapshot>,
    val requiredAssetIds: LongArray,
    val requiredHeaderTitles: List<String>,
    val resourceReadiness: MediaGridMorphResourceReadiness,
    val generation: Long,
) {
    val geometryReady: Boolean
        get() = directions.isNotEmpty() && directions.values.all { direction ->
            direction.focalEntries.isNotEmpty() && direction.focalEntries.all(MediaGridMorphPreparedFocalEntry::geometryReady)
        }

    val isStableIdleReady: Boolean get() = geometryReady && resourceReadiness.isReady
}

/**
 * Non-Compose cache. Publishing prepared pairs never invalidates the LazyGrid.
 * A newer identity invalidates every older in-flight calculation.
 */
internal class MediaGridMorphPreparationCache {
    private val nextGeneration = AtomicLong(0L)
    private val latestToken = AtomicReference<MediaGridMorphPreparationToken?>(null)
    private val published = AtomicReference<MediaGridMorphStableIdleReadySnapshot?>(null)
    private val _publishedVersion = MutableStateFlow(0L)
    val publishedVersion = _publishedVersion
    private val _invalidationVersion = MutableStateFlow(0L)
    val invalidationVersion = _invalidationVersion
    private var lastRequestedIdentity: MediaGridMorphPreparationIdentity? = null
    private var lastPublishedIdentity: MediaGridMorphPreparationIdentity? = null
    private var lastUrgentIdentity: MediaGridMorphPreparationIdentity? = null
    private var lastUrgentAssetIds = LongArray(0)

    @Synchronized
    fun request(
        identity: MediaGridMorphPreparationIdentity,
        isScrollInProgress: Boolean,
        isPointerInProgress: Boolean,
        sourceViewportAnchor: MediaGridMorphSourceViewportAnchor? = null,
        lightweightViewportSignature: MediaGridViewportAnchorSignature? = null,
    ): MediaGridMorphPreparationToken? {
        if (
            isScrollInProgress ||
            isPointerInProgress ||
            (
                identity == lastRequestedIdentity &&
                    (latestToken.get() != null || identity == lastPublishedIdentity)
                )
        ) return null
        val token = MediaGridMorphPreparationToken(
            generation = nextGeneration.incrementAndGet(),
            identity = identity,
            sourceViewportAnchor = sourceViewportAnchor,
            lightweightViewportSignature = lightweightViewportSignature,
        )
        lastRequestedIdentity = identity
        latestToken.set(token)
        if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordPreparationCacheMutation()
        return token
    }

    @Synchronized
    fun requestUrgentAssetsIfChanged(
        identity: MediaGridMorphPreparationIdentity,
        assetIds: LongArray,
    ): LongArray? {
        val normalized = assetIds.distinct().sorted().toLongArray()
        if (identity == lastUrgentIdentity && normalized.contentEquals(lastUrgentAssetIds)) return null
        lastUrgentIdentity = identity
        lastUrgentAssetIds = normalized
        return normalized
    }

    @Synchronized
    fun cancelInFlight() {
        latestToken.set(null)
    }

    @Synchronized
    fun invalidate() {
        latestToken.set(null)
        published.set(null)
        lastRequestedIdentity = null
        lastPublishedIdentity = null
        lastUrgentIdentity = null
        lastUrgentAssetIds = LongArray(0)
        val generation = nextGeneration.incrementAndGet()
        _publishedVersion.value = generation
        _invalidationVersion.value = generation
        if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordPreparationCacheMutation()
    }

    @Synchronized
    fun isCurrent(token: MediaGridMorphPreparationToken): Boolean = latestToken.get() == token

    @Synchronized
    fun publish(
        token: MediaGridMorphPreparationToken,
        snapshot: MediaGridMorphStableIdleReadySnapshot,
    ): Boolean {
        if (latestToken.get() != token) return false
        if (snapshot.generation != token.generation || snapshot.identity != token.identity) return false
        if (snapshot.directions.values.any { directionSnapshot ->
                val pair = directionSnapshot.pair
                pair.sourceRevision != token.identity.sourceRevision ||
                    pair.frameKey != token.identity.frameKey ||
                    pair.fromColumnCount != token.identity.columnCount ||
                    pair.viewportSignature != token.identity.viewportSignature
            }
        ) return false
        published.set(snapshot)
        lastPublishedIdentity = token.identity
        _publishedVersion.value = token.generation
        if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordPreparationCacheMutation()
        return true
    }

    @Synchronized
    fun updateResourceReadiness(
        generation: Long,
        readiness: MediaGridMorphResourceReadiness,
    ): MediaGridMorphStableIdleReadySnapshot? {
        val current = published.get()?.takeIf { it.generation == generation } ?: return null
        val updated = current.copy(resourceReadiness = readiness)
        published.set(updated)
        return updated
    }

    fun snapshot(): MediaGridMorphStableIdleReadySnapshot? = published.get()

    fun preparedPairsSnapshot(): Map<MediaGridMorphDirection, MediaGridMorphPreparedPair> =
        published.get()?.directions?.mapValues { it.value.pair }.orEmpty()
}

internal fun MediaGridMorphPreparedPair.matchesIdentity(
    identity: MediaGridMorphInteractionIdentity,
): Boolean =
    sourceRevision == identity.sourceRevision &&
        frameKey == identity.frameKey &&
        fromColumnCount == identity.currentColumnCount &&
        viewportSignature == identity.viewportSignature

/**
 * Checks only the bounded pair slots that can cross the current viewport. It
 * never touches the retained store, starts a request, decodes, or scans the
 * complete frame/resident index.
 */
internal fun isMediaGridMorphProductionReady(
    pair: MediaGridMorphPreparedPair,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
): Boolean = mediaGridMorphImageCompleteness(pair, preparedIndex).isComplete

internal fun MediaGridMorphPreparedPair.requiredMorphAssetIds(): LongArray {
    val ids = LinkedHashSet<Long>()
    viewportPlanTemplate?.let {
        mediaGridMorphPossibleFocalCenters(this).forEach { center ->
            val renderSet = MediaGridMorphPlan.selectRowReflow(this, center).requiredRenderSet()
            renderSet.protectedAssetIds.forEach(ids::add)
        }
    } ?: run {
        slots.forEach { slot ->
            slot.startContent.assetIdOrNull()?.let(ids::add)
            slot.endContent.assetIdOrNull()?.let(ids::add)
        }
    }
    return ids.toLongArray()
}

internal data class MediaGridMorphImageCompleteness(
    val requiredSourceImageCount: Int,
    val resolvedSourceImageCount: Int,
    val requiredTargetImageCount: Int,
    val resolvedTargetImageCount: Int,
    val unresolvedRequiredAssetId: Long?,
    val headerTextComplete: Boolean,
    val geometryComplete: Boolean,
    val sourceViewportComplete: Boolean = true,
    val missingHeaderTitle: String? = null,
    val optionalOffscreenCellCount: Int = 0,
    val requiredCellCount: Int = 0,
    val requiredHeaderCount: Int = 0,
) {
    val isComplete: Boolean
        get() = geometryComplete &&
            sourceViewportComplete &&
            headerTextComplete &&
            requiredSourceImageCount == resolvedSourceImageCount &&
            requiredTargetImageCount == resolvedTargetImageCount
}

/**
 * Checks dataset media endpoints separately from resident prepared images.
 * A Media endpoint with no prepared image is incomplete, never a placeholder.
 */
internal fun mediaGridMorphImageCompleteness(
    pair: MediaGridMorphPreparedPair,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
): MediaGridMorphImageCompleteness {
    val sourceIds: Set<Long>
    val targetIds: Set<Long>
    val headerTextComplete: Boolean
    val geometryComplete: Boolean
    val viewportPlan = pair.viewportPlanTemplate
    var optionalOffscreenCellCount = 0
    val requiredCellIdentities = LinkedHashSet<MediaGridMorphRequiredCellIdentity>()
    val requiredHeaderIdentities = LinkedHashSet<MediaGridMorphRequiredHeaderIdentity>()
    if (viewportPlan != null) {
        val selectedPlans = mediaGridMorphPossibleFocalCenters(pair).map(viewportPlan::select)
        val sourceAssets = LinkedHashSet<Long>()
        val targetAssets = LinkedHashSet<Long>()
        var geometry = selectedPlans.isNotEmpty()
        var missingTitle: String? = null
        selectedPlans.forEach { selected ->
            if (selected.rowPlans.isEmpty()) geometry = false
            val coordinates = HashSet<Pair<Int, Int>>()
            val renderSet = MediaGridMorphPlan.selectRowReflow(pair, selected.initialPinchCenter).requiredRenderSet()
            optionalOffscreenCellCount += renderSet.optionalCellCount
            requiredCellIdentities += renderSet.requiredCellIdentities
            requiredHeaderIdentities += renderSet.requiredHeaderIdentities
            renderSet.requiredSourceAssetIds.forEach(sourceAssets::add)
            renderSet.requiredTargetAssetIds.forEach(targetAssets::add)
            selected.rowPlans.forEach { row ->
                row.cells.forEach { cell ->
                    val identity = MediaGridMorphRequiredCellIdentity(cell.relativeRow, cell.column)
                    if (identity in renderSet.requiredCellIdentities) {
                        geometry = geometry && coordinates.add(identity.relativeRow to identity.column)
                    }
                }
            }
            missingTitle = missingTitle ?: selected.headerPlans.asSequence()
                .filter { header ->
                    MediaGridMorphRequiredHeaderIdentity(
                        header.relativeRow,
                        header.startKey,
                        header.endKey,
                    ) in renderSet.requiredHeaderIdentities
                }
                .flatMap { sequenceOf(it.startTitle, it.endTitle) }
                .firstOrNull { it.isNullOrBlank() }
        }
        sourceIds = sourceAssets
        targetIds = targetAssets
        headerTextComplete = missingTitle == null
        geometryComplete = geometry && viewportPlan.viewport.width > 0f && viewportPlan.viewport.height > 0f &&
            viewportPlan.sourceRows.isNotEmpty() && viewportPlan.targetRows.isNotEmpty() &&
            selectedPlans.isNotEmpty()
    } else {
        val visibleSlots = pair.slots.filter { slot ->
            val left = minOf(slot.startRect.left, slot.endRect.left)
            val top = minOf(slot.startRect.top, slot.endRect.top)
            val right = maxOf(slot.startRect.right, slot.endRect.right)
            val bottom = maxOf(slot.startRect.bottom, slot.endRect.bottom)
            right > pair.viewport.left && left < pair.viewport.right &&
                bottom > pair.viewport.top && top < pair.viewport.bottom
        }
        sourceIds = visibleSlots.mapNotNull { it.startContent.assetIdOrNull() }.toSet()
        targetIds = visibleSlots.mapNotNull { it.endContent.assetIdOrNull() }.toSet()
        headerTextComplete = pair.headers.all { it.startTitle == null || it.startTitle.isNotBlank() } &&
            pair.headers.all { it.endTitle == null || it.endTitle.isNotBlank() }
        geometryComplete = pair.viewport.width > 0f && pair.viewport.height > 0f && visibleSlots.isNotEmpty()
    }
    val prepared = preparedIndex.preparedImageByAssetId
    val unresolved = (sourceIds + targetIds).firstOrNull { it !in prepared }
    return MediaGridMorphImageCompleteness(
        requiredSourceImageCount = sourceIds.size,
        resolvedSourceImageCount = sourceIds.count { it in prepared },
        requiredTargetImageCount = targetIds.size,
        resolvedTargetImageCount = targetIds.count { it in prepared },
        unresolvedRequiredAssetId = unresolved,
        headerTextComplete = headerTextComplete,
        geometryComplete = geometryComplete,
        sourceViewportComplete = if (viewportPlan == null) {
            true
        } else {
            mediaGridMorphPossibleFocalCenters(pair).all { center ->
                val selected = viewportPlan.select(center)
                val renderSet = MediaGridMorphPlan.selectRowReflow(pair, center).requiredRenderSet()
                selected.rowPlans.flatMap { it.cells }
                    .filter { MediaGridMorphRequiredCellIdentity(it.relativeRow, it.column) in renderSet.requiredCellIdentities }
                    .all { cell ->
                        val sourceIdentity = cell.sourcePreparedImageIdentity ?: return@all true
                        val sourceAssetId = cell.startContent.assetIdOrNull() ?: return@all true
                        prepared[sourceAssetId]?.identity == sourceIdentity
                    }
            }
        },
        missingHeaderTitle = if (viewportPlan == null) {
            null
        } else {
            mediaGridMorphPossibleFocalCenters(pair).asSequence()
                .map(viewportPlan::select)
                .flatMap { selected ->
                    val required = MediaGridMorphPlan.selectRowReflow(pair, selected.initialPinchCenter)
                        .requiredRenderSet()
                        .requiredHeaderIdentities
                    selected.headerPlans.asSequence().filter { header ->
                        MediaGridMorphRequiredHeaderIdentity(
                            header.relativeRow,
                            header.startKey,
                            header.endKey,
                        ) in required
                    }
                }
                .flatMap { sequenceOf(it.startTitle, it.endTitle) }
                .firstOrNull { it.isNullOrBlank() }
        },
        optionalOffscreenCellCount = optionalOffscreenCellCount,
        requiredCellCount = requiredCellIdentities.size,
        requiredHeaderCount = requiredHeaderIdentities.size,
    )
}

/**
 * Bounded representative centers for every source row that can be selected
 * from the current viewport. This includes partial rows, row edges, and the
 * gaps around headers without scanning the complete dataset.
 */
internal fun mediaGridMorphPossibleFocalCenters(
    pair: MediaGridMorphPreparedPair,
): List<Offset> {
    val template = pair.viewportPlanTemplate ?: return listOf(pair.viewport.center)
    val rows = template.sourceRows
        .filter { it.isActualVisibleSourceRow && it.cells.isNotEmpty() }
    if (rows.isEmpty()) return emptyList()
    return rows
        .distinctBy { row -> row.rowKey ?: "invalid-visible-row-${row.visibleRow}" }
        .map { row ->
            Offset(
                pair.viewport.width / 2f,
                ((row.top + row.bottom) / 2f).coerceIn(pair.viewport.top, pair.viewport.bottom),
            )
        }
}

private fun Rect.intersectsViewport(viewport: Rect): Boolean =
    right > viewport.left && left < viewport.right &&
        bottom > viewport.top && top < viewport.bottom

internal object MediaGridMorphDefaults {
    const val DeadZoneScale: Float = 1.02f
    const val ReleaseThreshold: Float = 0.5f
    // Keep release handoff shorter while retaining enough frames for exact target validation.
    const val SettleDurationMillis: Long = 100L
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
    preparedIndex: MediaGridResidentCanvasPreparedIndex? = null,
): MediaGridMorphCapture? {
    if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordFullMorphCapture()
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
        viewportWidthPx = signature.viewportWidthPx,
        viewportHeightPx = signature.viewportHeightPx,
    ) ?: return null
    val startOrdinal = ordinalRange.first
    val endOrdinal = ordinalRange.last
    val capturedMedia = ArrayList<MediaGridMorphCapturedMedia>(endOrdinal - startOrdinal + 1)

    fun capturedAt(ordinal: Int): MediaGridMorphCapturedMedia? {
        val itemIndex = ordinalIndex.itemIndexByMediaOrdinal.getOrNull(ordinal) ?: return null
        val item = frame.items.getOrNull(itemIndex) as? MediaGridCellItem ?: return null
        return MediaGridMorphCapturedMedia(
            assetId = item.entry.assetId,
            mediaOrdinal = ordinal,
            itemIndex = itemIndex,
            xCreatedAt = item.entry.xCreatedAt,
            likeCount = item.entry.likeCount,
            itemKey = item.key,
            preparedImageIdentity = preparedIndex?.preparedImageByAssetId
                ?.get(item.entry.assetId)
                ?.identity,
        )
    }

    for (ordinal in startOrdinal..endOrdinal) {
        capturedAt(ordinal)?.let(capturedMedia::add)
    }
    if (capturedMedia.isEmpty()) return null

    val precedingMediaWindow = (maxOf(0, startOrdinal - (ClassifiedMediaGridMaxColumnCount - 1)) until startOrdinal)
        .mapNotNull(::capturedAt)
    val startColumnOffsets = mediaGridMorphStartColumnOffsets(
        startOrdinal = startOrdinal,
        sortBase = frame.key.dataKey.sort.baseOrder,
        currentMedia = capturedMedia.first(),
        precedingMedia = precedingMediaWindow,
    )

    val visibleMediaRects = ArrayList<MediaGridMorphCapturedRect>()
    val visibleHeaderRects = ArrayList<MediaGridMorphCapturedHeaderRect>()
    var measuredHeaderHeight = 0f
    val viewportStartOffset = layoutInfo.viewportStartOffset
    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)
    for (info in layoutInfo.visibleItemsInfo) {
        val ordinal = ordinalIndex.mediaOrdinalByItemIndex.getOrNull(info.index) ?: -1
        val rect = Rect(
            info.offset.x.toFloat(),
            (info.offset.y - viewportStartOffset).toFloat(),
            (info.offset.x + info.size.width).toFloat(),
            (info.offset.y - viewportStartOffset + info.size.height).toFloat(),
        )
        if (ordinal >= 0) {
            if (ordinal in startOrdinal..endOrdinal) {
                val itemKey = info.key as? String
                val assetId = itemKey?.let(frame.assetIdByItemKey::get)
                    ?: ordinalIndex.assetIdByMediaOrdinal[ordinal]
                visibleMediaRects += MediaGridMorphCapturedRect(
                    mediaOrdinal = ordinal,
                    rect = rect,
                    assetId = assetId,
                    isPartiallyVisible = rect.top < 0f || rect.bottom > viewportHeight,
                    itemIndex = info.index,
                    itemKey = itemKey,
                )
            }
        } else {
            val header = frame.items.getOrNull(info.index) as? MediaGridHeaderItem ?: continue
            val nextOrdinal = ordinalIndex.mediaOrdinalByItemIndex.getOrNull(info.index + 1) ?: -1
            if (nextOrdinal >= 0 && nextOrdinal in startOrdinal..endOrdinal) {
                visibleHeaderRects += MediaGridMorphCapturedHeaderRect(
                    firstMediaOrdinal = nextOrdinal,
                    key = header.key,
                    rect = rect,
                    title = header.label,
                    itemIndex = info.index,
                )
                measuredHeaderHeight = maxOf(measuredHeaderHeight, rect.height)
            }
        }
    }

    val capturedByOrdinal = capturedMedia.associateBy { it.mediaOrdinal }
    val actualCellWidth = (layoutInfo.viewportSize.width.toFloat() / columnCount.coerceAtLeast(1)).coerceAtLeast(1f)
    val sourceRows = visibleMediaRects
        .sortedWith(compareBy<MediaGridMorphCapturedRect> { it.rect.top }.thenBy { it.rect.left })
        .fold(ArrayList<MutableList<MediaGridMorphCapturedRect>>()) { rows, captured ->
            val row = rows.lastOrNull()
            if (row == null || abs(row.first().rect.top - captured.rect.top) > 1f) {
                rows += arrayListOf(captured)
            } else {
                row += captured
            }
            rows
        }
        .mapIndexed { rowIndex, rowCells ->
            val ordered = rowCells.sortedBy { it.rect.left }
            MediaGridMorphCapturedRow(
                visibleRow = rowIndex,
                top = ordered.minOf { it.rect.top },
                bottom = ordered.maxOf { it.rect.bottom },
                cells = ordered.mapIndexedNotNull { fallbackColumn, capturedRect ->
                    val assetId = capturedRect.assetId ?: capturedByOrdinal[capturedRect.mediaOrdinal]?.assetId ?: return@mapIndexedNotNull null
                    val actualColumn = (capturedRect.rect.left / actualCellWidth)
                        .roundToInt()
                        .coerceIn(0, columnCount - 1)
                    MediaGridMorphCapturedCell(
                        column = if (actualColumn >= 0) actualColumn else fallbackColumn,
                        mediaOrdinal = capturedRect.mediaOrdinal,
                        assetId = assetId,
                        rect = capturedRect.rect,
                        isPartiallyVisible = capturedRect.isPartiallyVisible,
                        itemIndex = capturedRect.itemIndex,
                        itemKey = capturedRect.itemKey,
                        preparedImageIdentity = preparedIndex?.preparedImageByAssetId
                            ?.get(assetId)
                            ?.identity,
                    )
                },
                isPartiallyVisible = ordered.any { it.isPartiallyVisible },
                rowKey = mediaGridMorphSourceRowKey(
                    frame = frame,
                    cells = ordered.mapNotNull { capturedRect ->
                        val assetId = capturedRect.assetId
                            ?: capturedByOrdinal[capturedRect.mediaOrdinal]?.assetId
                            ?: return@mapNotNull null
                        val actualColumn = (capturedRect.rect.left / actualCellWidth)
                            .roundToInt()
                            .coerceIn(0, columnCount - 1)
                        MediaGridMorphCapturedCell(
                            column = actualColumn,
                            mediaOrdinal = capturedRect.mediaOrdinal,
                            assetId = assetId,
                            rect = capturedRect.rect,
                            isPartiallyVisible = capturedRect.isPartiallyVisible,
                            itemIndex = capturedRect.itemIndex,
                            itemKey = capturedRect.itemKey,
                            preparedImageIdentity = preparedIndex?.preparedImageByAssetId
                                ?.get(assetId)
                                ?.identity,
                        )
                    },
                    columnCount = columnCount,
                    sortBase = frame.key.dataKey.sort.baseOrder,
                ),
                isActualVisibleSourceRow = ordered.any { captured ->
                    captured.rect.bottom > 0f && captured.rect.top < viewportHeight
                },
            )
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
            0f,
            layoutInfo.viewportSize.width.toFloat(),
            (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0).toFloat(),
        ),
        cellSizePx = signature.cellSizePx.coerceAtLeast(1).toFloat(),
        headerHeightPx = measuredHeaderHeight.takeIf { it > 0f }
            ?: fallbackHeaderHeightPx.coerceAtLeast(1f),
        sortBase = frame.key.dataKey.sort.baseOrder,
        mediaOrdinalRange = startOrdinal..endOrdinal,
        media = capturedMedia.toList(),
        precedingMedia = precedingMediaWindow.lastOrNull(),
        precedingMediaWindow = precedingMediaWindow,
        startColumnOffsetByColumnCount = startColumnOffsets,
        visibleMediaRects = visibleMediaRects.toList(),
        visibleHeaderRects = visibleHeaderRects.toList(),
        sourceRows = sourceRows,
        totalMediaCount = totalMedia,
        preparedIndexVersion = preparedIndex?.drawIndexVersion,
        exactTargetLayoutIndexes = listOf(columnCount - 1, columnCount, columnCount + 1)
            .filter { it in ClassifiedMediaGridMinColumnCount..ClassifiedMediaGridMaxColumnCount }
            .distinct()
            .associateWith { targetColumnCount ->
                buildMediaGridMorphExactTargetLayoutIndex(
                    frame = frame,
                    targetColumnCount = targetColumnCount,
                    viewportWidthPx = layoutInfo.viewportSize.width,
                    viewportHeightPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0),
                    headerHeightPx = measuredHeaderHeight.takeIf { it > 0f } ?: fallbackHeaderHeightPx,
                )
            },
    )
}

private fun mediaGridMorphSourceRowKey(
    frame: MediaGridFrameData,
    cells: List<MediaGridMorphCapturedCell>,
    columnCount: Int,
    sortBase: ClassifiedSortBase,
): MediaGridMorphSourceRowKey? {
    if (cells.isEmpty() || cells.any { it.itemIndex < 0 || it.column !in 0 until columnCount }) return null
    val ordered = cells.sortedBy { it.rect.left }
    val columns = ordered.map { it.column }
    if (columns != columns.distinct().sorted() ||
        columns.zipWithNext().any { (left, right) -> right != left + 1 }
    ) return null
    if (ordered.any { it.mediaOrdinal - it.column != ordered.first().mediaOrdinal - ordered.first().column }) return null
    val first = ordered.first()
    val firstItem = frame.items.getOrNull(first.itemIndex) as? MediaGridCellItem ?: return null
    val currentBucket = mediaGridMorphBucketSpec(
        firstItem.entry.xCreatedAt,
        firstItem.entry.likeCount,
        sortBase,
        columnCount,
    )?.key
    var cursor = first.itemIndex
    var precedingMediaCount = 0
    var canonicalStartItemIndex = first.itemIndex
    while (precedingMediaCount < first.column) {
        cursor--
        val previous = frame.items.getOrNull(cursor) ?: return null
        when (previous) {
            is MediaGridHeaderItem -> return null
            is MediaGridCellItem -> {
                val previousBucket = mediaGridMorphBucketSpec(
                    previous.entry.xCreatedAt,
                    previous.entry.likeCount,
                    sortBase,
                    columnCount,
                )?.key
                if (previousBucket != currentBucket) return null
                canonicalStartItemIndex = cursor
                precedingMediaCount++
            }
        }
    }
    val startOrdinal = frame.ordinalIndex.mediaOrdinalByItemIndex
        .getOrNull(canonicalStartItemIndex)
        ?: return null
    return MediaGridMorphSourceRowKey(
        canonicalStartItemIndex = canonicalStartItemIndex,
        canonicalStartMediaOrdinal = startOrdinal,
        headerOrBucketKey = currentBucket,
        currentColumnCount = columnCount,
    )
}

internal fun mediaGridMorphStartColumnOffsets(
    startOrdinal: Int,
    sortBase: ClassifiedSortBase,
    columnCounts: IntRange = ClassifiedMediaGridMinColumnCount..ClassifiedMediaGridMaxColumnCount,
    currentMedia: MediaGridMorphCapturedMedia? = null,
    precedingMedia: List<MediaGridMorphCapturedMedia> = emptyList(),
): List<Int> = columnCounts.map { columnCount ->
    if (sortBase == ClassifiedSortBase.Default) {
        startOrdinal.mod(columnCount)
        } else {
            val currentBucket = currentMedia?.let {
                mediaGridMorphBucketSpec(it.xCreatedAt, it.likeCount, sortBase, columnCount)
            }
            if (currentBucket == null) {
                0
            } else {
                precedingMedia.asReversed()
                    .asSequence()
                    .takeWhile {
                        mediaGridMorphBucketSpec(it.xCreatedAt, it.likeCount, sortBase, columnCount)?.key == currentBucket.key
                    }
                    .count()
                    .mod(columnCount)
            }
        }
    }

internal fun MediaGridMorphCapture.startColumnOffset(columnCount: Int): Int {
    val supportedIndex = columnCount - ClassifiedMediaGridMinColumnCount
    return startColumnOffsetByColumnCount.getOrNull(supportedIndex)?.coerceIn(0, columnCount - 1)
        ?: mediaGridMorphStartColumnOffsets(
            startOrdinal = mediaOrdinalRange.first,
            sortBase = sortBase,
            columnCounts = columnCount..columnCount,
            currentMedia = media.firstOrNull(),
            precedingMedia = precedingMediaWindow.ifEmpty { precedingMedia?.let(::listOf).orEmpty() },
        ).single()
}

internal fun mediaGridMorphOrdinalRange(
    totalMedia: Int,
    firstVisibleMediaOrdinal: Int,
    lastVisibleMediaOrdinal: Int,
    columnCount: Int,
    viewportWidthPx: Int = 0,
    viewportHeightPx: Int = 0,
): IntRange? {
    if (
        totalMedia <= 0 ||
        firstVisibleMediaOrdinal !in 0 until totalMedia ||
        lastVisibleMediaOrdinal < firstVisibleMediaOrdinal
    ) return null
    val widestAdjacentColumnCount = (columnCount + 1)
        .coerceAtMost(ClassifiedMediaGridMaxColumnCount)
        .coerceAtLeast(columnCount)
    val basePadding = widestAdjacentColumnCount * MediaGridMorphDefaults.OverscanRows
    val visibleMediaCount = lastVisibleMediaOrdinal - firstVisibleMediaOrdinal + 1
    val adjacentViewportCapacity = if (viewportWidthPx > 0 && viewportHeightPx > 0) {
        val adjacentCellSize = viewportWidthPx.toFloat() / widestAdjacentColumnCount
        val rowsIntersectingViewport = kotlin.math.ceil(viewportHeightPx / adjacentCellSize)
            .toInt() + 1
        rowsIntersectingViewport * widestAdjacentColumnCount
    } else {
        visibleMediaCount
    }
    // A wider target grid can expose more media at progress 1 than the
    // current LazyGrid reports as visible. Keep that complete endpoint plus
    // the same bounded overscan on both sides.
    val ordinalPadding = basePadding +
        (adjacentViewportCapacity - visibleMediaCount).coerceAtLeast(0)
    return (firstVisibleMediaOrdinal - ordinalPadding).coerceAtLeast(0)..
        (lastVisibleMediaOrdinal + ordinalPadding).coerceAtMost(totalMedia - 1)
}

/** Pure Default-dispatcher builder shared by production and tests. */
internal fun buildMediaGridMorphPreparedPairs(
    capture: MediaGridMorphCapture,
): Map<MediaGridMorphDirection, MediaGridMorphPreparedPair> {
    if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordMorphPairBuild()
    return buildMediaGridMorphPreparedPairsInternal(capture, includeLegacyGeometry = true)
}

/** TEST_HARNESS row-reflow pair builder; no generic dataset slot geometry is created. */
internal fun buildMediaGridMorphRowPreparedPairs(
    capture: MediaGridMorphCapture,
): Map<MediaGridMorphDirection, MediaGridMorphPreparedPair> {
    if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordMorphPairBuild()
    return buildMediaGridMorphPreparedPairsInternal(capture, includeLegacyGeometry = false)
}

/** Claim fallback builder. The stable-idle cache path never calls this. */
internal fun buildMediaGridMorphRowPreparedPairForClaim(
    capture: MediaGridMorphCapture,
    direction: MediaGridMorphDirection,
): MediaGridMorphPreparedPair? {
    if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordClaimPairBuild()
    return buildMediaGridMorphPreparedPairInternal(
        capture = capture,
        direction = direction,
        includeLegacyGeometry = false,
    )
}

private fun buildMediaGridMorphPreparedPairsInternal(
    capture: MediaGridMorphCapture,
    includeLegacyGeometry: Boolean,
): Map<MediaGridMorphDirection, MediaGridMorphPreparedPair> {
    if (capture.media.isEmpty()) return emptyMap()
    val pairs = LinkedHashMap<MediaGridMorphDirection, MediaGridMorphPreparedPair>(2)
    for (direction in MediaGridMorphDirection.entries) {
        buildMediaGridMorphPreparedPairInternal(capture, direction, includeLegacyGeometry)?.let {
            pairs[direction] = it
        }
    }
    return pairs.toMap()
}

private fun buildMediaGridMorphPreparedPairInternal(
    capture: MediaGridMorphCapture,
    direction: MediaGridMorphDirection,
    includeLegacyGeometry: Boolean,
): MediaGridMorphPreparedPair? {
    if (capture.media.isEmpty()) return null
    val target = mediaGridMorphTargetColumnCount(capture.identity.columnCount, direction)
    if (target == capture.identity.columnCount) return null
    val start = if (includeLegacyGeometry) {
        buildMediaGridMorphLayout(capture, capture.identity.columnCount, useCapturedGeometry = true)
    } else {
        MediaGridMorphLayoutSnapshot(
            columnCount = capture.identity.columnCount,
            viewport = capture.viewport,
            media = emptyList(),
            headers = emptyList(),
            mediaOrdinalRange = capture.mediaOrdinalRange,
        )
    }
    val end = if (includeLegacyGeometry) {
        buildMediaGridMorphLayout(capture, target, useCapturedGeometry = false)
    } else {
        MediaGridMorphLayoutSnapshot(
            columnCount = target,
            viewport = capture.viewport,
            media = emptyList(),
            headers = emptyList(),
            mediaOrdinalRange = capture.mediaOrdinalRange,
        )
    }
    val viewportPlanTemplate = capture.sourceRows.takeIf { it.isNotEmpty() }
        ?.let { buildMediaGridMorphViewportPlanTemplate(capture, target) }
    return MediaGridMorphPreparedPair(
        sourceRevision = capture.identity.sourceRevision,
        frameKey = capture.identity.frameKey,
        fromColumnCount = capture.identity.columnCount,
        toColumnCount = target,
        viewport = capture.viewport,
        viewportSignature = capture.identity.viewportSignature,
        startLayout = start,
        targetLayout = end,
        slots = if (includeLegacyGeometry) buildMediaGridMorphSlots(start, end) else emptyList(),
        headers = if (includeLegacyGeometry) buildMediaGridMorphHeaderBands(start, end) else emptyList(),
        mediaOrdinalRange = capture.mediaOrdinalRange,
        viewportPlanTemplate = viewportPlanTemplate,
    )
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
    var column = if (capture.sortBase == ClassifiedSortBase.Default) {
        capture.media.firstOrNull()?.mediaOrdinal?.mod(columnCount) ?: 0
    } else {
        0
    }
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
    val lastVisible = capture.visibleMediaRects.maxByOrNull { it.mediaOrdinal }
    val generatedEndAnchor = lastVisible?.let { actual ->
        media.firstOrNull { it.mediaOrdinal == actual.mediaOrdinal }?.rect
    }
    val capturesDatasetEnd =
        lastVisible != null &&
            generatedEndAnchor != null &&
            capture.identity.viewportSignature.firstVisibleItemIndex > 0 &&
            lastVisible.mediaOrdinal == capture.media.lastOrNull()?.mediaOrdinal &&
            abs(lastVisible.rect.bottom - capture.viewport.bottom) <= 1f
    val shiftY = when {
        capturesDatasetEnd ->
            capture.viewport.bottom - generatedEndAnchor!!.bottom
        firstVisible != null && generatedAnchor != null ->
            firstVisible.rect.top - generatedAnchor.top
        else -> capture.viewport.top
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
                startImageDrawingRect = if (startMedia == null) endRect else startRect,
                endImageDrawingRect = if (endMedia == null) startRect else endRect,
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
    val settleStartProgress: Float? = null,
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
            settleStartProgress = progress.coerceIn(0f, 1f),
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
        val releaseProgress = settleStartProgress ?: currentProgress
        val nextProgress = releaseProgress + (targetProgress - releaseProgress) * fraction
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
    releaseThreshold: Float = MediaGridMorphDefaults.ReleaseThreshold,
): Int {
    if (accumulatedScale == null || !accumulatedScale.isFinite() || releaseThreshold !in 0f..1f) {
        return currentColumnCount
    }
    val decision = mediaGridMorphCanonicalReleaseDecision(
        currentColumnCount = currentColumnCount,
        initialDistance = 1f,
        releaseDistance = 1f / accumulatedScale,
    )
    if (decision.progress < releaseThreshold) return currentColumnCount
    return decision.targetColumnCount
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
