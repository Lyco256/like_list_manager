package com.lyco256.llm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal enum class MediaGridMorphCellTransitionType {
    SameImage,
    ImageToImage,
    ImageToPlaceholder,
    PlaceholderToImage,
    PlaceholderToPlaceholder,
}

internal data class MediaGridMorphRowRenderCell(
    val plan: MediaGridMorphCellPlan,
    val startImage: MediaGridResidentCanvasPreparedImage?,
    val endImage: MediaGridResidentCanvasPreparedImage?,
    val transitionType: MediaGridMorphCellTransitionType = mediaGridMorphCellTransitionType(
        plan.startContent,
        plan.endContent,
    ),
)

internal fun mediaGridMorphCellTransitionType(
    start: MediaGridMorphSlotContent,
    end: MediaGridMorphSlotContent,
): MediaGridMorphCellTransitionType {
    val startImage = start as? MediaGridMorphSlotContent.Image
    val endImage = end as? MediaGridMorphSlotContent.Image
    return when {
        startImage != null && endImage != null && startImage.assetId == endImage.assetId ->
            MediaGridMorphCellTransitionType.SameImage
        startImage != null && endImage != null -> MediaGridMorphCellTransitionType.ImageToImage
        startImage != null -> MediaGridMorphCellTransitionType.ImageToPlaceholder
        endImage != null -> MediaGridMorphCellTransitionType.PlaceholderToImage
        else -> MediaGridMorphCellTransitionType.PlaceholderToPlaceholder
    }
}

internal data class MediaGridMorphCellBlend(
    val drawPlaceholder: Boolean,
    val startAlpha: Float?,
    val endAlpha: Float?,
)

internal fun mediaGridMorphCellBlend(
    start: MediaGridMorphSlotContent,
    end: MediaGridMorphSlotContent,
    progress: Float,
): MediaGridMorphCellBlend {
    val p = progress.coerceIn(0f, 1f)
    val startImage = start is MediaGridMorphSlotContent.Image
    val endImage = end is MediaGridMorphSlotContent.Image
    if (startImage && endImage && start == end) return MediaGridMorphCellBlend(false, 1f, null)
    if (startImage && endImage) return MediaGridMorphCellBlend(false, 1f, p)
    if (startImage) return MediaGridMorphCellBlend(true, 1f - p, null)
    if (endImage) return MediaGridMorphCellBlend(true, null, p)
    return MediaGridMorphCellBlend(true, null, null)
}

internal data class MediaGridMorphRowRenderHeader(
    val plan: MediaGridMorphHeaderPlan,
    val startText: TextLayoutResult?,
    val endText: TextLayoutResult?,
    val transitionType: MediaGridMorphHeaderTransitionType = mediaGridMorphHeaderTransitionType(
        plan.startTitle,
        plan.endTitle,
    ),
)

internal enum class MediaGridMorphHeaderTransitionType {
    SameTitle,
    Crossfade,
    StartOnly,
    EndOnly,
    NoText,
}

internal fun mediaGridMorphHeaderTransitionType(
    startTitle: String?,
    endTitle: String?,
): MediaGridMorphHeaderTransitionType = when {
    startTitle != null && startTitle == endTitle -> MediaGridMorphHeaderTransitionType.SameTitle
    startTitle != null && endTitle != null -> MediaGridMorphHeaderTransitionType.Crossfade
    startTitle != null -> MediaGridMorphHeaderTransitionType.StartOnly
    endTitle != null -> MediaGridMorphHeaderTransitionType.EndOnly
    else -> MediaGridMorphHeaderTransitionType.NoText
}

internal data class MediaGridMorphHeaderBlend(
    val heightPx: Float,
    val startTextAlpha: Float?,
    val endTextAlpha: Float?,
)

internal fun mediaGridMorphHeaderBlend(
    header: MediaGridMorphHeaderPlan,
    progress: Float,
): MediaGridMorphHeaderBlend {
    val p = progress.coerceIn(0f, 1f)
    val sameTitle = header.startTitle != null && header.startTitle == header.endTitle
    return MediaGridMorphHeaderBlend(
        heightPx = lerp(header.startHeightPx, header.endHeightPx, p),
        startTextAlpha = when {
            header.startTitle == null -> null
            sameTitle -> 1f
            else -> 1f - p
        },
        endTextAlpha = when {
            header.endTitle == null || sameTitle -> null
            else -> p
        },
    )
}

internal fun mediaGridMorphHeaderTransitions(
    model: MediaGridMorphRowRenderModel,
    progress: Float,
): List<MediaGridMorphHeaderTransitionObservation> = model.headers.map { header ->
    val blend = mediaGridMorphHeaderBlend(header.plan, progress)
    MediaGridMorphHeaderTransitionObservation(
        relativeRow = header.plan.relativeRow,
        startKey = header.plan.startKey,
        endKey = header.plan.endKey,
        startHeightPx = header.plan.startHeightPx,
        endHeightPx = header.plan.endHeightPx,
        currentHeightPx = blend.heightPx,
        startTextAlpha = blend.startTextAlpha,
        endTextAlpha = blend.endTextAlpha,
    )
}

/** Immutable claim-time data consumed by the single LazyGrid draw surface. */
internal data class MediaGridMorphRowRenderModel(
    val viewport: Rect,
    val sourceCellSize: Float,
    val targetCellSize: Float,
    val fixedFocalCenterY: Float,
    val focalV: Float,
    val targetRowTopAdjustment: Float = 0f,
    val rows: List<MediaGridMorphRowPlan>,
    val cells: List<MediaGridMorphRowRenderCell>,
    val headers: List<MediaGridMorphRowRenderHeader>,
    val surfaceColor: Color,
    val placeholderColor: Color,
    val textColor: Color,
    val horizontalTextPaddingPx: Float,
    val verticalTextPaddingPx: Float,
    val protectedAssetIds: LongArray,
    val requiredSourceImageCount: Int,
    val resolvedSourceImageCount: Int,
    val requiredTargetImageCount: Int,
    val resolvedTargetImageCount: Int,
    val unresolvedRequiredAssetId: Long?,
    val headerTextComplete: Boolean,
    val isComplete: Boolean,
)

internal fun mediaGridMorphSourceVisualItems(
    model: MediaGridMorphRowRenderModel,
): List<MediaGridMorphVisualItemObservation> = buildList {
    model.cells.forEach { cell ->
        val assetId = cell.plan.startContent.assetIdOrNull() ?: return@forEach
        val rect = mediaGridMorphRenderCellRect(model, cell.plan, 0f)
        if (rect.intersectsMorphViewport(model.viewport)) {
            add(MediaGridMorphVisualItemObservation("asset:$assetId", rect))
        }
    }
    model.headers.forEach { header ->
        val key = header.plan.startKey ?: return@forEach
        val rect = mediaGridMorphRenderHeaderRect(model, header.plan, 0f)
        if (rect.intersectsMorphViewport(model.viewport)) {
            add(MediaGridMorphVisualItemObservation("header:$key", rect))
        }
    }
}.sortedBy { it.key }

internal fun mediaGridMorphTargetVisualItems(
    model: MediaGridMorphRowRenderModel,
): List<MediaGridMorphVisualItemObservation> = buildList {
    model.cells.forEach { cell ->
        val assetId = cell.plan.endContent.assetIdOrNull() ?: return@forEach
        val rect = mediaGridMorphRenderCellRect(model, cell.plan, 1f)
        if (rect.intersectsMorphViewport(model.viewport)) {
            add(MediaGridMorphVisualItemObservation("asset:$assetId", rect))
        }
    }
    model.headers.forEach { header ->
        val key = header.plan.endKey ?: return@forEach
        val rect = mediaGridMorphRenderHeaderRect(model, header.plan, 1f)
        if (rect.intersectsMorphViewport(model.viewport)) {
            add(MediaGridMorphVisualItemObservation("header:$key", rect))
        }
    }
}.sortedBy { it.key }

private fun Rect.intersectsMorphViewport(viewport: Rect): Boolean =
    minOf(right, viewport.right) - maxOf(left, viewport.left) > 1f &&
        minOf(bottom, viewport.bottom) - maxOf(top, viewport.top) > 1f

internal fun mediaGridMorphRenderCellRect(
    model: MediaGridMorphRowRenderModel,
    cell: MediaGridMorphCellPlan,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f && cell.sourceRect != null) return cell.sourceRect
    val currentCellSize = lerp(model.sourceCellSize, model.targetCellSize, p)
    val focalRowTop = model.fixedFocalCenterY - model.focalV * currentCellSize
    val targetAdjustment = model.targetRowTopAdjustment * p
    val top = focalRowTop + cell.relativeRow * currentCellSize +
        lerp(cell.startHeaderOffsetPx, cell.endHeaderOffsetPx, p) + targetAdjustment
    return Rect(
        left = model.viewport.left + cell.column * currentCellSize,
        top = top,
        right = model.viewport.left + (cell.column + 1) * currentCellSize,
        bottom = top + currentCellSize,
    )
}

internal fun mediaGridMorphRenderHeaderRect(
    model: MediaGridMorphRowRenderModel,
    header: MediaGridMorphHeaderPlan,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    header.sourceRect?.takeIf { p <= 0f }?.let { return it }
    val currentCellSize = lerp(model.sourceCellSize, model.targetCellSize, p)
    val focalRowTop = model.fixedFocalCenterY - model.focalV * currentCellSize
    val targetAdjustment = model.targetRowTopAdjustment * p
    val top = focalRowTop + header.relativeRow * currentCellSize +
        lerp(header.startOffsetBeforePx, header.endOffsetBeforePx, p) + targetAdjustment
    val height = lerp(header.startHeightPx, header.endHeightPx, p)
    return Rect(model.viewport.left, top, model.viewport.right, top + height)
}

internal data class MediaGridMorphTextResourceIdentity(
    val titles: List<String>,
    val density: Float,
    val fontScale: Float,
    val viewportWidthPx: Int,
)

/** Text has to be measured before pointer input can claim the gesture. */
internal data class MediaGridMorphTextResourceIndex(
    val identity: MediaGridMorphTextResourceIdentity,
    val layoutsByTitle: Map<String, TextLayoutResult>,
    val surfaceColor: Color,
    val placeholderColor: Color,
    val textColor: Color,
    val horizontalTextPaddingPx: Float,
    val verticalTextPaddingPx: Float,
)

internal enum class MediaGridMorphClaimReadinessReason {
    CaptureUnavailable,
    DirectionUnavailable,
    StaleIdentity,
    SourceViewportMismatch,
    MissingSourceImage,
    MissingTargetImage,
    MissingHeaderText,
    GeometryIncomplete,
    PreparedIndexChanged,
}

internal data class MediaGridMorphClaimReadinessReport(
    val generation: Long = 0L,
    val sourceFrameKey: MediaGridRenderKey?,
    val sourceDataKey: MediaGridDataKey?,
    val currentColumnCount: Int,
    val requestedDirection: MediaGridMorphDirection?,
    val firstVisibleItemIndex: Int = -1,
    val firstVisibleItemScrollOffset: Int = 0,
    val firstVisibleMediaOrdinal: Int = -1,
    val lastVisibleMediaOrdinal: Int = -1,
    val sourceFocalRowKey: MediaGridMorphSourceRowKey? = null,
    val fixedPinchCenterY: Float = Float.NaN,
    val selectedTargetRowIndex: Int? = null,
    val selectedTargetMediaOrdinal: Int? = null,
    val requiredSourceImageCount: Int = 0,
    val resolvedSourceImageCount: Int = 0,
    val requiredTargetImageCount: Int = 0,
    val resolvedTargetImageCount: Int = 0,
    val unresolvedRequiredAssetId: Long? = null,
    val missingHeaderTitle: String? = null,
    val sourceViewportComplete: Boolean = false,
    val geometryComplete: Boolean = false,
    val preparedIndexVersion: Long? = null,
    val optionalOffscreenCellCount: Int = 0,
    val requiredCellCount: Int = 0,
    val requiredHeaderCount: Int = 0,
    val exactTargetRowId: Int? = null,
    val exactTargetRowFirstItemIndex: Int? = null,
    val exactTargetRowMediaOrdinals: List<Int> = emptyList(),
    val visibleSourceRowsValidated: Boolean = false,
    val requiredHeadersValidated: Boolean = false,
    val finalReason: MediaGridMorphClaimReadinessReason? = null,
)

internal sealed interface MediaGridMorphClaimPreparationResult {
    val report: MediaGridMorphClaimReadinessReport

    data class Ready(
        val bundle: MediaGridMorphClaimBundle,
        override val report: MediaGridMorphClaimReadinessReport,
    ) : MediaGridMorphClaimPreparationResult

    data class Unavailable(
        val reason: MediaGridMorphClaimReadinessReason,
        override val report: MediaGridMorphClaimReadinessReport,
    ) : MediaGridMorphClaimPreparationResult
}

@Composable
internal fun rememberMediaGridMorphTextResourceIndex(
    pairs: Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
    viewportWidthPx: Int,
): MediaGridMorphTextResourceIndex {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val horizontalPadding = with(density) { 12.dp.toPx() }
    val verticalPadding = with(density) { 8.dp.toPx() }
    val style = androidx.compose.material3.MaterialTheme.typography.titleSmall.copy(
        color = colors.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
    val titles = remember(pairs, viewportWidthPx) {
        val boundedTitles = LinkedHashSet<String>()
        pairs.values.forEach { pair ->
            pair.viewportPlanTemplate?.sourceHeaders?.forEach {
                it.title.takeIf(String::isNotBlank)?.let(boundedTitles::add)
            }
            pair.viewportPlanTemplate?.targetHeaders?.forEach {
                it.title.takeIf(String::isNotBlank)?.let(boundedTitles::add)
            }
            pair.headers.forEach { header ->
                header.startTitle?.takeIf(String::isNotBlank)?.let(boundedTitles::add)
                header.endTitle?.takeIf(String::isNotBlank)?.let(boundedTitles::add)
            }
        }
        boundedTitles.toList()
    }
    val textLayouts = remember(titles, style, textMeasurer, density.density, density.fontScale, viewportWidthPx) {
        titles.associateWith { title ->
            if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordTextLayoutMeasure()
            textMeasurer.measure(
                text = title,
                style = style,
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = viewportWidthPx.coerceAtLeast(0)),
            )
        }
    }
    val identity = remember(titles, density.density, density.fontScale, viewportWidthPx) {
        MediaGridMorphTextResourceIdentity(titles, density.density, density.fontScale, viewportWidthPx)
    }
    return remember(identity, textLayouts, colors.surface, colors.surfaceVariant, colors.onSurface) {
        MediaGridMorphTextResourceIndex(
            identity = identity,
            layoutsByTitle = textLayouts,
            surfaceColor = colors.surface.copy(alpha = 1f),
            placeholderColor = colors.surfaceVariant.copy(alpha = 1f),
            textColor = colors.onSurface,
            horizontalTextPaddingPx = horizontalPadding,
            verticalTextPaddingPx = verticalPadding,
        )
    }
}

internal data class MediaGridMorphDirectionClaimBundle(
    val direction: MediaGridMorphDirection,
    val targetColumnCount: Int,
    val plan: MediaGridMorphPlan,
    val renderModel: MediaGridMorphRowRenderModel,
    val completeness: MediaGridMorphImageCompleteness,
    val protectedAssetIds: LongArray,
    val requiredRenderSet: MediaGridMorphRequiredRenderSet = MediaGridMorphRequiredRenderSet(
        requiredSourceAssetIds = emptySet(),
        requiredTargetAssetIds = emptySet(),
        requiredSourceHeaderTitles = emptySet(),
        requiredTargetHeaderTitles = emptySet(),
        requiredCellIdentities = emptySet(),
        requiredHeaderIdentities = emptySet(),
        protectedAssetIds = emptySet(),
        optionalCellCount = 0,
        optionalHeaderCount = 0,
    ),
) {
    val isComplete: Boolean get() = renderModel.isComplete && completeness.isComplete
}

internal data class MediaGridMorphClaimBundle(
    val generation: Long,
    val identity: MediaGridMorphInteractionIdentity,
    val firstPointerId: Long,
    val secondPointerId: Long,
    val initialDistance: Float,
    val fixedInitialCenter: androidx.compose.ui.geometry.Offset,
    val preparedIndexIdentity: Long,
    val textResourceIdentity: MediaGridMorphTextResourceIdentity,
    val directions: Map<MediaGridMorphDirection, MediaGridMorphDirectionClaimBundle>,
    val protectedAssetUnion: LongArray,
    val sourceViewportAnchor: MediaGridMorphSourceViewportAnchor? = null,
) {
    fun isCompleteFor(direction: MediaGridMorphDirection): Boolean =
        directions[direction]?.isComplete == true

    fun isCompleteForCurrentColumns(): Boolean {
        return directions.values.any(MediaGridMorphDirectionClaimBundle::isComplete)
    }
}

private fun buildMediaGridMorphDirectionClaimBundle(
    pair: MediaGridMorphPreparedPair,
    direction: MediaGridMorphDirection,
    center: androidx.compose.ui.geometry.Offset,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textResources: MediaGridMorphTextResourceIndex,
): MediaGridMorphDirectionClaimBundle {
    if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordSelectedPlanBuild(direction)
    val plan = if (pair.viewportPlanTemplate != null) {
        MediaGridMorphPlan.selectRowReflow(pair, center)
    } else {
        MediaGridMorphPlan.select(pair, center)
    }
    if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordRequiredRenderSetBuild()
    val requiredRenderSet = plan.requiredRenderSet()
    val completeness = mediaGridMorphSelectedPlanCompleteness(
        plan = plan,
        preparedIndex = preparedIndex,
        textResources = textResources,
        requiredRenderSet = requiredRenderSet,
    )
    if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordRenderModelBuild(direction)
    val model = buildMediaGridMorphRowRenderModel(
        plan = plan,
        preparedIndex = preparedIndex,
        textResources = textResources,
        completeness = completeness,
        requiredRenderSet = requiredRenderSet,
    )
    return MediaGridMorphDirectionClaimBundle(
        direction = direction,
        targetColumnCount = pair.toColumnCount,
        plan = plan,
        renderModel = model,
        completeness = completeness,
        protectedAssetIds = model.protectedAssetIds,
        requiredRenderSet = requiredRenderSet,
    )
}

/** Production builder: one locked direction, one plan, one model, one protection set. */
private fun buildMediaGridMorphSelectedClaimBundle(
    identity: MediaGridMorphInteractionIdentity,
    pair: MediaGridMorphPreparedPair,
    direction: MediaGridMorphDirection,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textResources: MediaGridMorphTextResourceIndex,
    generation: Long,
    firstPointerId: Long,
    secondPointerId: Long,
    firstPosition: androidx.compose.ui.geometry.Offset,
    secondPosition: androidx.compose.ui.geometry.Offset,
    sourceViewportAnchor: MediaGridMorphSourceViewportAnchor?,
    initialDistance: Float,
    fixedInitialCenter: androidx.compose.ui.geometry.Offset,
): MediaGridMorphClaimBundle? {
    val claimDistance = distanceBetween(firstPosition, secondPosition)
    if (!claimDistance.isFinite() || claimDistance <= 0f || !initialDistance.isFinite() || initialDistance <= 0f) return null
    if (!pair.matchesIdentity(identity)) return null
    val selected = buildMediaGridMorphDirectionClaimBundle(
        pair = pair,
        direction = direction,
        center = fixedInitialCenter,
        preparedIndex = preparedIndex,
        textResources = textResources,
    )
    return MediaGridMorphClaimBundle(
        generation = generation,
        identity = identity,
        firstPointerId = firstPointerId,
        secondPointerId = secondPointerId,
        initialDistance = initialDistance,
        fixedInitialCenter = fixedInitialCenter,
        preparedIndexIdentity = preparedIndex.drawIndexVersion,
        textResourceIdentity = textResources.identity,
        directions = mapOf(direction to selected),
        protectedAssetUnion = selected.protectedAssetIds,
        sourceViewportAnchor = sourceViewportAnchor,
    )
}

/** Pure, bounded claim-time builder. It performs no IO, decode, or text measurement. */
internal fun buildMediaGridMorphClaimBundle(
    capture: MediaGridMorphCapture,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textResources: MediaGridMorphTextResourceIndex,
    generation: Long,
    firstPointerId: Long,
    secondPointerId: Long,
    firstPosition: androidx.compose.ui.geometry.Offset,
    secondPosition: androidx.compose.ui.geometry.Offset,
    sourceViewportAnchor: MediaGridMorphSourceViewportAnchor? = null,
    initialDistanceOverride: Float? = null,
    fixedInitialCenterOverride: androidx.compose.ui.geometry.Offset? = null,
): MediaGridMorphClaimBundle? {
    val identity = capture.identity.toInteractionIdentity()
    val claimDistance = distanceBetween(firstPosition, secondPosition)
    val initialDistance = initialDistanceOverride ?: claimDistance
    if (!claimDistance.isFinite() || claimDistance <= 0f || !initialDistance.isFinite() || initialDistance <= 0f) return null
    val center = androidx.compose.ui.geometry.Offset(
        (firstPosition.x + secondPosition.x) / 2f,
        (firstPosition.y + secondPosition.y) / 2f,
    )
    val pairs = buildMediaGridMorphRowPreparedPairs(capture)
    val directions = LinkedHashMap<MediaGridMorphDirection, MediaGridMorphDirectionClaimBundle>(pairs.size)
    pairs.forEach { (direction, pair) ->
        if (!pair.matchesIdentity(identity)) return@forEach
        directions[direction] = buildMediaGridMorphDirectionClaimBundle(
            pair = pair,
            direction = direction,
            center = center,
            preparedIndex = preparedIndex,
            textResources = textResources,
        )
    }
    val union = LinkedHashSet<Long>()
    directions.values.forEach { bundle -> bundle.protectedAssetIds.forEach(union::add) }
    return MediaGridMorphClaimBundle(
        generation = generation,
        identity = identity,
        firstPointerId = firstPointerId,
        secondPointerId = secondPointerId,
        initialDistance = initialDistance,
        fixedInitialCenter = fixedInitialCenterOverride ?: center,
        preparedIndexIdentity = preparedIndex.drawIndexVersion,
        textResourceIdentity = textResources.identity,
        directions = directions.toMap(),
        protectedAssetUnion = union.toLongArray(),
        sourceViewportAnchor = sourceViewportAnchor,
    )
}

/**
 * Production claim preparation. The requested direction and actual current
 * pointer positions select the plan used for the report; cached pair
 * completeness is never promoted to a claim failure reason.
 */
internal fun prepareMediaGridMorphClaim(
    capture: MediaGridMorphCapture?,
    preparedPairsSnapshot: Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textResources: MediaGridMorphTextResourceIndex,
    candidate: MediaGridMorphCandidate,
    requestedDirection: MediaGridMorphDirection?,
    latestIdentity: MediaGridMorphInteractionIdentity?,
    sourceViewportAnchor: MediaGridMorphSourceViewportAnchor? = null,
): MediaGridMorphClaimPreparationResult {
    val claimFirst = candidate.claimFirstPosition ?: candidate.firstInitialPosition
    val claimSecond = candidate.claimSecondPosition ?: candidate.secondInitialPosition
    val baseIdentity = capture?.identity?.toInteractionIdentity() ?: latestIdentity
    fun baseReport(
        reason: MediaGridMorphClaimReadinessReason?,
        plan: MediaGridMorphPlan? = null,
        completeness: MediaGridMorphImageCompleteness? = null,
    ): MediaGridMorphClaimReadinessReport {
        val identity = baseIdentity
        val signature = identity?.viewportSignature
        val sourceRow = plan?.viewportPlan?.focalMediaOrdinal?.let { ordinal ->
            capture?.sourceRows?.firstOrNull { row -> row.cells.any { it.mediaOrdinal == ordinal } }?.rowKey
        }
        return MediaGridMorphClaimReadinessReport(
            generation = candidate.generation,
            sourceFrameKey = identity?.frameKey,
            sourceDataKey = identity?.frameKey?.dataKey,
            currentColumnCount = identity?.currentColumnCount ?: 0,
            requestedDirection = requestedDirection,
            firstVisibleItemIndex = sourceViewportAnchor?.firstVisibleItemIndex
                ?: signature?.firstVisibleItemIndex ?: -1,
            firstVisibleItemScrollOffset = sourceViewportAnchor?.firstVisibleItemScrollOffset ?: 0,
            firstVisibleMediaOrdinal = signature?.firstVisibleMediaOrdinal ?: -1,
            lastVisibleMediaOrdinal = signature?.lastVisibleMediaOrdinal ?: -1,
            sourceFocalRowKey = sourceRow,
            fixedPinchCenterY = (candidate.claimFirstPosition ?: candidate.firstInitialPosition).let { first ->
                (first.y + (candidate.claimSecondPosition ?: candidate.secondInitialPosition).y) / 2f
            },
            selectedTargetRowIndex = plan?.viewportPlan?.targetAnchorRowIndex,
            selectedTargetMediaOrdinal = plan?.viewportPlan?.targetFocalMediaOrdinal,
            requiredSourceImageCount = completeness?.requiredSourceImageCount ?: 0,
            resolvedSourceImageCount = completeness?.resolvedSourceImageCount ?: 0,
            requiredTargetImageCount = completeness?.requiredTargetImageCount ?: 0,
            resolvedTargetImageCount = completeness?.resolvedTargetImageCount ?: 0,
            unresolvedRequiredAssetId = completeness?.unresolvedRequiredAssetId,
            missingHeaderTitle = completeness?.missingHeaderTitle,
            sourceViewportComplete = completeness?.sourceViewportComplete == true,
            geometryComplete = completeness?.geometryComplete == true,
            preparedIndexVersion = preparedIndex.drawIndexVersion,
            optionalOffscreenCellCount = completeness?.optionalOffscreenCellCount ?: 0,
            requiredCellCount = completeness?.requiredCellCount ?: 0,
            requiredHeaderCount = completeness?.requiredHeaderCount ?: 0,
            exactTargetRowId = plan?.viewportPlan?.targetAnchorRowId,
            exactTargetRowFirstItemIndex = plan?.viewportPlan?.targetAnchorRowFirstItemIndex,
            exactTargetRowMediaOrdinals = plan?.viewportPlan?.targetAnchorRowMediaOrdinals?.toList().orEmpty(),
            visibleSourceRowsValidated = completeness?.sourceViewportComplete == true,
            requiredHeadersValidated = completeness?.headerTextComplete == true,
            finalReason = reason,
        )
    }

    if (capture == null) {
        return MediaGridMorphClaimPreparationResult.Unavailable(
            MediaGridMorphClaimReadinessReason.CaptureUnavailable,
            baseReport(MediaGridMorphClaimReadinessReason.CaptureUnavailable),
        )
    }
    if (capture.preparedIndexVersion != null && capture.preparedIndexVersion != preparedIndex.drawIndexVersion) {
        return MediaGridMorphClaimPreparationResult.Unavailable(
            MediaGridMorphClaimReadinessReason.PreparedIndexChanged,
            baseReport(MediaGridMorphClaimReadinessReason.PreparedIndexChanged),
        )
    }
    val direction = requestedDirection ?: return MediaGridMorphClaimPreparationResult.Unavailable(
        MediaGridMorphClaimReadinessReason.DirectionUnavailable,
        baseReport(MediaGridMorphClaimReadinessReason.DirectionUnavailable),
    )
    val liveIdentity = capture.identity.toInteractionIdentity()
    if (latestIdentity == null || liveIdentity != latestIdentity) {
        return MediaGridMorphClaimPreparationResult.Unavailable(
            MediaGridMorphClaimReadinessReason.StaleIdentity,
            baseReport(MediaGridMorphClaimReadinessReason.StaleIdentity),
        )
    }
    val pair = preparedPairsSnapshot[direction]
        ?.takeIf { it.matchesIdentity(liveIdentity) }
        ?: buildMediaGridMorphRowPreparedPairForClaim(capture, direction)
        ?: return MediaGridMorphClaimPreparationResult.Unavailable(
            MediaGridMorphClaimReadinessReason.DirectionUnavailable,
            baseReport(MediaGridMorphClaimReadinessReason.DirectionUnavailable),
        )
    val fixedCenter = androidx.compose.ui.geometry.Offset(
        (claimFirst.x + claimSecond.x) / 2f,
        (claimFirst.y + claimSecond.y) / 2f,
    )
    val bundle = buildMediaGridMorphSelectedClaimBundle(
        identity = liveIdentity,
        pair = pair,
        direction = direction,
        preparedIndex = preparedIndex,
        textResources = textResources,
        generation = candidate.generation,
        firstPointerId = candidate.firstPointerId,
        secondPointerId = candidate.secondPointerId,
        firstPosition = claimFirst,
        secondPosition = claimSecond,
        sourceViewportAnchor = sourceViewportAnchor,
        initialDistance = candidate.initialDistance,
        fixedInitialCenter = fixedCenter,
    ) ?: return MediaGridMorphClaimPreparationResult.Unavailable(
        MediaGridMorphClaimReadinessReason.DirectionUnavailable,
        baseReport(MediaGridMorphClaimReadinessReason.DirectionUnavailable),
    )
    val selected = bundle.directions[direction]
        ?: return MediaGridMorphClaimPreparationResult.Unavailable(
            MediaGridMorphClaimReadinessReason.DirectionUnavailable,
            baseReport(MediaGridMorphClaimReadinessReason.DirectionUnavailable),
        )
    val completeness = selected.completeness
    val reason = when {
        !completeness.sourceViewportComplete -> MediaGridMorphClaimReadinessReason.SourceViewportMismatch
        completeness.requiredSourceImageCount != completeness.resolvedSourceImageCount ->
            MediaGridMorphClaimReadinessReason.MissingSourceImage
        completeness.requiredTargetImageCount != completeness.resolvedTargetImageCount ->
            MediaGridMorphClaimReadinessReason.MissingTargetImage
        !completeness.headerTextComplete -> MediaGridMorphClaimReadinessReason.MissingHeaderText
        !completeness.geometryComplete || !selected.renderModel.isComplete ->
            MediaGridMorphClaimReadinessReason.GeometryIncomplete
        else -> null
    }
    val report = baseReport(reason, selected.plan, completeness)
    return if (reason == null && selected.isComplete) {
        MediaGridMorphClaimPreparationResult.Ready(bundle, report)
    } else {
        MediaGridMorphClaimPreparationResult.Unavailable(
            reason ?: MediaGridMorphClaimReadinessReason.GeometryIncomplete,
            report.copy(finalReason = reason ?: MediaGridMorphClaimReadinessReason.GeometryIncomplete),
        )
    }
}

/**
 * Stable-idle gate used by TEST_HARNESS diagnostics and the production
 * preparation contract. Every focal center that can select a distinct source
 * row must have a complete selected plan before the viewport is reported
 * ready. This is bounded by the current pair's viewport rows and headers.
 */
internal fun mediaGridMorphStableIdleReady(
    pair: MediaGridMorphPreparedPair,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textResources: MediaGridMorphTextResourceIndex,
): Boolean = mediaGridMorphStableIdleFailureReason(pair, preparedIndex, textResources) == null

internal fun mediaGridMorphStableIdleFailureReason(
    pair: MediaGridMorphPreparedPair,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textResources: MediaGridMorphTextResourceIndex,
): String? {
    val centers = mediaGridMorphPossibleFocalCenters(pair)
    if (centers.isEmpty()) return "no-focal-centers"
    centers.forEach { center ->
        val plan = if (pair.viewportPlanTemplate != null) {
            MediaGridMorphPlan.selectRowReflow(pair, center)
        } else {
            MediaGridMorphPlan.select(pair, center)
        }
        val completeness = mediaGridMorphSelectedPlanCompleteness(plan, preparedIndex, textResources)
        if (!completeness.isComplete) {
            val reason = when {
                completeness.resolvedSourceImageCount != completeness.requiredSourceImageCount ->
                    MediaGridMorphClaimReadinessReason.MissingSourceImage
                completeness.resolvedTargetImageCount != completeness.requiredTargetImageCount ->
                    MediaGridMorphClaimReadinessReason.MissingTargetImage
                !completeness.sourceViewportComplete ->
                    MediaGridMorphClaimReadinessReason.SourceViewportMismatch
                !completeness.headerTextComplete ->
                    MediaGridMorphClaimReadinessReason.MissingHeaderText
                else -> MediaGridMorphClaimReadinessReason.GeometryIncomplete
            }
            return "$reason:center=$center source=${completeness.resolvedSourceImageCount}/${completeness.requiredSourceImageCount} " +
                "target=${completeness.resolvedTargetImageCount}/${completeness.requiredTargetImageCount} " +
                "sourceViewport=${completeness.sourceViewportComplete} " +
                "geometry=${completeness.geometryComplete} " +
                "headerText=${completeness.headerTextComplete} " +
                "unresolvedAsset=${completeness.unresolvedRequiredAssetId} " +
                "missingHeader=${completeness.missingHeaderTitle}"
        }
    }
    return null
}

internal fun mediaGridMorphSelectedPlanCompleteness(
    plan: MediaGridMorphPlan,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textResources: MediaGridMorphTextResourceIndex,
    requiredRenderSet: MediaGridMorphRequiredRenderSet = plan.requiredRenderSet(),
): MediaGridMorphImageCompleteness {
    val selected = plan.viewportPlan
    if (selected == null) {
        val required = requiredRenderSet
        val source = required.requiredSourceAssetIds
        val target = required.requiredTargetAssetIds
        val unresolved = (source + target).firstOrNull { it !in preparedIndex.preparedImageByAssetId }
        val missingHeaderTitle = (required.requiredSourceHeaderTitles + required.requiredTargetHeaderTitles)
            .firstOrNull { it !in textResources.layoutsByTitle }
        return MediaGridMorphImageCompleteness(
            requiredSourceImageCount = source.size,
            resolvedSourceImageCount = source.count { it in preparedIndex.preparedImageByAssetId },
            requiredTargetImageCount = target.size,
            resolvedTargetImageCount = target.count { it in preparedIndex.preparedImageByAssetId },
            unresolvedRequiredAssetId = unresolved,
            headerTextComplete = missingHeaderTitle == null,
            geometryComplete = plan.viewport.width > 0f && plan.viewport.height > 0f &&
                required.requiredCellIdentities.isNotEmpty(),
            optionalOffscreenCellCount = required.optionalCellCount,
            requiredCellCount = required.requiredCellIdentities.size,
            requiredHeaderCount = required.requiredHeaderIdentities.size,
            missingHeaderTitle = missingHeaderTitle,
        )
    }
    val required = requiredRenderSet
    val requiredCells = required.requiredCellPlans
    val source = required.requiredSourceAssetIds
    val target = required.requiredTargetAssetIds
    val geometryComplete = selected.viewport.width > 0f && selected.viewport.height > 0f &&
        requiredCells.size == required.requiredCellIdentities.size
    var sourceViewportComplete = true
    requiredCells.forEach { cell ->
        val sourceIdentity = cell.sourcePreparedImageIdentity
        if (sourceIdentity != null) {
            val preparedIdentity = (cell.startContent as? MediaGridMorphSlotContent.Image)
                ?.let { preparedIndex.preparedImageByAssetId[it.assetId]?.identity }
            // The pair geometry is intentionally stable across resident draw-index
            // publications. Re-readiness is asset-based here; a newer prepared
            // image identity for the same asset must not force geometry rebuild.
            sourceViewportComplete = sourceViewportComplete && preparedIdentity != null
        }
    }
    val missingHeaderTitle = (required.requiredSourceHeaderTitles + required.requiredTargetHeaderTitles)
        .firstOrNull { it !in textResources.layoutsByTitle }
    val headersComplete = missingHeaderTitle == null
    val unresolved = (source + target).firstOrNull { it !in preparedIndex.preparedImageByAssetId }
    return MediaGridMorphImageCompleteness(
        requiredSourceImageCount = source.size,
        resolvedSourceImageCount = source.count { it in preparedIndex.preparedImageByAssetId },
        requiredTargetImageCount = target.size,
        resolvedTargetImageCount = target.count { it in preparedIndex.preparedImageByAssetId },
        unresolvedRequiredAssetId = unresolved,
        headerTextComplete = headersComplete,
        geometryComplete = geometryComplete,
        sourceViewportComplete = sourceViewportComplete,
        optionalOffscreenCellCount = required.optionalCellCount,
        requiredCellCount = required.requiredCellIdentities.size,
        requiredHeaderCount = required.requiredHeaderIdentities.size,
        missingHeaderTitle = missingHeaderTitle,
    )
}

internal fun buildMediaGridMorphRowRenderModel(
    plan: MediaGridMorphPlan,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textResources: MediaGridMorphTextResourceIndex,
    completeness: MediaGridMorphImageCompleteness = mediaGridMorphSelectedPlanCompleteness(
        plan,
        preparedIndex,
        textResources,
    ),
    requiredRenderSet: MediaGridMorphRequiredRenderSet = plan.requiredRenderSet(),
): MediaGridMorphRowRenderModel {
    val selected = plan.viewportPlan
    val required = requiredRenderSet
    val cells = selected?.let {
        required.requiredCellPlans.map { cell ->
            MediaGridMorphRowRenderCell(
                plan = cell,
                startImage = (cell.startContent as? MediaGridMorphSlotContent.Image)?.let { preparedIndex.preparedImageByAssetId[it.assetId] },
                endImage = (cell.endContent as? MediaGridMorphSlotContent.Image)?.let { preparedIndex.preparedImageByAssetId[it.assetId] },
                transitionType = mediaGridMorphCellTransitionType(cell.startContent, cell.endContent),
            )
        }
    }.orEmpty()
    val headers = selected?.headerPlans?.mapIndexed { headerIndex, header ->
        val requiredHeader = required.requiredHeaderPlanIndexes.getOrElse(headerIndex) { false }
        MediaGridMorphRowRenderHeader(
            plan = header,
            // Keep optional header geometry/background in the frozen model,
            // while only required swept headers consume text layouts.
            startText = header.startTitle
                ?.takeIf { requiredHeader }
                ?.let(textResources.layoutsByTitle::get),
            endText = header.endTitle
                ?.takeIf { requiredHeader }
                ?.let(textResources.layoutsByTitle::get),
            transitionType = mediaGridMorphHeaderTransitionType(header.startTitle, header.endTitle),
        )
    }.orEmpty()
    val protected = required.protectedAssetIds
    val protectedEndpointsComplete = cells.all { cell ->
        val sourceAssetId = cell.plan.startContent.assetIdOrNull()
        val targetAssetId = cell.plan.endContent.assetIdOrNull()
        (sourceAssetId == null || sourceAssetId in protected) &&
            (targetAssetId == null || targetAssetId in protected)
    }
    return MediaGridMorphRowRenderModel(
        viewport = selected?.viewport ?: plan.viewport,
        sourceCellSize = selected?.sourceCellSize ?: plan.viewport.width,
        targetCellSize = selected?.targetCellSize ?: plan.viewport.width,
        fixedFocalCenterY = selected?.fixedFocalCenterY ?: plan.viewport.center.y,
        focalV = selected?.focalV ?: 0.5f,
        targetRowTopAdjustment = selected?.targetRowTopAdjustment ?: 0f,
        rows = selected?.rowPlans.orEmpty(),
        cells = cells,
        headers = headers,
        surfaceColor = textResources.surfaceColor,
        placeholderColor = textResources.placeholderColor,
        textColor = textResources.textColor,
        horizontalTextPaddingPx = textResources.horizontalTextPaddingPx,
        verticalTextPaddingPx = textResources.verticalTextPaddingPx,
        protectedAssetIds = protected.toLongArray(),
        requiredSourceImageCount = completeness.requiredSourceImageCount,
        resolvedSourceImageCount = completeness.resolvedSourceImageCount,
        requiredTargetImageCount = completeness.requiredTargetImageCount,
        resolvedTargetImageCount = completeness.resolvedTargetImageCount,
        unresolvedRequiredAssetId = completeness.unresolvedRequiredAssetId,
        headerTextComplete = completeness.headerTextComplete,
        isComplete = completeness.isComplete && protectedEndpointsComplete && cells.all { cell ->
            (cell.plan.startContent !is MediaGridMorphSlotContent.Image || cell.startImage != null) &&
                (cell.plan.endContent !is MediaGridMorphSlotContent.Image || cell.endImage != null)
        },
    )
}

/** Pure draw function used by the unified resident/Morph surface. */
internal fun DrawScope.drawMediaGridMorphRow(
    model: MediaGridMorphRowRenderModel,
    progress: Float,
) {
    val p = progress.coerceIn(0f, 1f)
    val viewport = model.viewport
    val viewportWidth = viewport.width.coerceAtMost(size.width)
    val viewportHeight = viewport.height.coerceAtMost(size.height)
    val currentCellSize = lerp(model.sourceCellSize, model.targetCellSize, p)
    val focalRowTop = model.fixedFocalCenterY - model.focalV * currentCellSize
    val targetAdjustment = model.targetRowTopAdjustment * p
    clipRect(0f, 0f, viewportWidth, viewportHeight) {
        var cellIndex = 0
        while (cellIndex < model.cells.size) {
            val cell = model.cells[cellIndex]
            val cellPlan = cell.plan
            val sourceRect = cellPlan.sourceRect
            val left: Float
            val top: Float
            val drawWidth: Float
            val drawHeight: Float
            if (p <= 0f && sourceRect != null) {
                left = sourceRect.left - viewport.left
                top = sourceRect.top - viewport.top
                drawWidth = sourceRect.width
                drawHeight = sourceRect.height
            } else {
                left = cellPlan.column * currentCellSize
                top = focalRowTop +
                    cellPlan.relativeRow * currentCellSize +
                    lerp(cellPlan.startHeaderOffsetPx, cellPlan.endHeaderOffsetPx, p) +
                    targetAdjustment - viewport.top
                drawWidth = currentCellSize
                drawHeight = currentCellSize
            }
            val right = left + drawWidth
            val bottom = top + drawHeight
            val width = drawWidth.roundToInt().coerceAtLeast(1)
            val height = drawHeight.roundToInt().coerceAtLeast(1)
            clipRect(left, top, right, bottom) {
                val drawPlaceholder = when (cell.transitionType) {
                    MediaGridMorphCellTransitionType.ImageToPlaceholder,
                    MediaGridMorphCellTransitionType.PlaceholderToImage,
                    MediaGridMorphCellTransitionType.PlaceholderToPlaceholder,
                    -> true
                    MediaGridMorphCellTransitionType.SameImage,
                    MediaGridMorphCellTransitionType.ImageToImage,
                    -> false
                }
                if (drawPlaceholder) {
                    drawRect(
                        model.placeholderColor,
                        androidx.compose.ui.geometry.Offset(left, top),
                        androidx.compose.ui.geometry.Size(drawWidth, drawHeight),
                    )
                }
                val startAlpha = when (cell.transitionType) {
                    MediaGridMorphCellTransitionType.SameImage,
                    MediaGridMorphCellTransitionType.ImageToImage,
                    -> 1f
                    MediaGridMorphCellTransitionType.ImageToPlaceholder -> 1f - p
                    MediaGridMorphCellTransitionType.PlaceholderToImage,
                    MediaGridMorphCellTransitionType.PlaceholderToPlaceholder,
                    -> -1f
                }
                if (startAlpha >= 0f) {
                    cell.startImage?.let { image ->
                        drawImage(
                            image.image,
                            image.srcOffset,
                            image.srcSize,
                            androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                            androidx.compose.ui.unit.IntSize(width, height),
                            alpha = startAlpha,
                        )
                    }
                }
                val endAlpha = when (cell.transitionType) {
                    MediaGridMorphCellTransitionType.ImageToImage,
                    MediaGridMorphCellTransitionType.PlaceholderToImage,
                    -> p
                    MediaGridMorphCellTransitionType.SameImage,
                    MediaGridMorphCellTransitionType.ImageToPlaceholder,
                    MediaGridMorphCellTransitionType.PlaceholderToPlaceholder,
                    -> -1f
                }
                if (endAlpha >= 0f) {
                    cell.endImage?.let { image ->
                        drawImage(
                            image.image,
                            image.srcOffset,
                            image.srcSize,
                            androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                            androidx.compose.ui.unit.IntSize(width, height),
                            alpha = endAlpha,
                        )
                    }
                }
            }
            cellIndex++
        }
        var headerIndex = 0
        while (headerIndex < model.headers.size) {
            val header = model.headers[headerIndex]
            val headerPlan = header.plan
            val sourceRect = headerPlan.sourceRect
            val top = if (p <= 0f && sourceRect != null) {
                sourceRect.top - viewport.top
            } else {
                focalRowTop +
                    headerPlan.relativeRow * currentCellSize +
                    lerp(headerPlan.startOffsetBeforePx, headerPlan.endOffsetBeforePx, p) +
                    targetAdjustment - viewport.top
            }
            val drawHeight = if (p <= 0f && sourceRect != null) {
                sourceRect.height
            } else {
                lerp(headerPlan.startHeightPx, headerPlan.endHeightPx, p)
            }
            val left = 0f
            val right = left + viewportWidth
            val bottom = top + drawHeight
            if (right > left && bottom > top) {
                drawRect(
                    model.surfaceColor,
                    androidx.compose.ui.geometry.Offset(left, top),
                    androidx.compose.ui.geometry.Size(right - left, bottom - top),
                )
                clipRect(left, top, right, bottom) {
                    val textX = left + model.horizontalTextPaddingPx
                    val textY = top + model.verticalTextPaddingPx
                    val startTextAlpha = when (header.transitionType) {
                        MediaGridMorphHeaderTransitionType.SameTitle -> 1f
                        MediaGridMorphHeaderTransitionType.Crossfade,
                        MediaGridMorphHeaderTransitionType.StartOnly,
                        -> 1f - p
                        MediaGridMorphHeaderTransitionType.EndOnly,
                        MediaGridMorphHeaderTransitionType.NoText,
                        -> -1f
                    }
                    if (startTextAlpha >= 0f) {
                        header.startText?.let {
                            drawText(
                                it,
                                color = model.textColor,
                                topLeft = androidx.compose.ui.geometry.Offset(textX, textY),
                                alpha = startTextAlpha,
                            )
                        }
                    }
                    val endTextAlpha = when (header.transitionType) {
                        MediaGridMorphHeaderTransitionType.Crossfade,
                        MediaGridMorphHeaderTransitionType.EndOnly,
                        -> p
                        MediaGridMorphHeaderTransitionType.SameTitle,
                        MediaGridMorphHeaderTransitionType.StartOnly,
                        MediaGridMorphHeaderTransitionType.NoText,
                        -> -1f
                    }
                    if (endTextAlpha >= 0f) {
                        header.endText?.let {
                            drawText(
                                it,
                                color = model.textColor,
                                topLeft = androidx.compose.ui.geometry.Offset(textX, textY),
                                alpha = endTextAlpha,
                            )
                        }
                    }
                }
            }
            headerIndex++
        }
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
