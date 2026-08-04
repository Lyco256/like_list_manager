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

internal data class MediaGridMorphRowRenderCell(
    val plan: MediaGridMorphCellPlan,
    val startImage: MediaGridResidentCanvasPreparedImage?,
    val endImage: MediaGridResidentCanvasPreparedImage?,
)

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
)

/** Immutable claim-time data consumed by the single LazyGrid draw surface. */
internal data class MediaGridMorphRowRenderModel(
    val viewport: Rect,
    val sourceCellSize: Float,
    val targetCellSize: Float,
    val fixedFocalCenterY: Float,
    val focalV: Float,
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
    additionalTitles: List<String> = emptyList(),
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
        buildList {
            additionalTitles.forEach { it.takeIf(String::isNotBlank)?.let(::add) }
            pairs.values.forEach { pair ->
                pair.viewportPlanTemplate?.sourceHeaders?.forEach { it.title.takeIf(String::isNotBlank)?.let(::add) }
                pair.viewportPlanTemplate?.targetHeaders?.forEach { it.title.takeIf(String::isNotBlank)?.let(::add) }
                pair.viewportPlanTemplate?.targetRows?.forEach { it.headerBefore?.title?.takeIf(String::isNotBlank)?.let(::add) }
                pair.headers.forEach { header ->
                    header.startTitle?.takeIf(String::isNotBlank)?.let(::add)
                    header.endTitle?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.distinct()
    }
    val textLayouts = remember(titles, style, textMeasurer, density.density, density.fontScale, viewportWidthPx) {
        titles.associateWith { title ->
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
        val plan = if (pair.viewportPlanTemplate != null) {
            MediaGridMorphPlan.selectRowReflow(pair, center)
        } else {
            MediaGridMorphPlan.select(pair, center)
        }
        val completeness = mediaGridMorphSelectedPlanCompleteness(plan, preparedIndex, textResources)
        val model = buildMediaGridMorphRowRenderModel(plan, preparedIndex, textResources, completeness)
        directions[direction] = MediaGridMorphDirectionClaimBundle(
            direction = direction,
            targetColumnCount = pair.toColumnCount,
            plan = plan,
            renderModel = model,
            completeness = completeness,
            protectedAssetIds = model.protectedAssetIds,
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
    val bundle = buildMediaGridMorphClaimBundle(
        capture = capture,
        preparedIndex = preparedIndex,
        textResources = textResources,
        generation = candidate.generation,
        firstPointerId = candidate.firstPointerId,
        secondPointerId = candidate.secondPointerId,
        firstPosition = claimFirst,
        secondPosition = claimSecond,
        sourceViewportAnchor = sourceViewportAnchor,
        initialDistanceOverride = candidate.initialDistance,
        fixedInitialCenterOverride = androidx.compose.ui.geometry.Offset(
            (claimFirst.x + claimSecond.x) / 2f,
            (claimFirst.y + claimSecond.y) / 2f,
        ),
    ) ?: return MediaGridMorphClaimPreparationResult.Unavailable(
        MediaGridMorphClaimReadinessReason.DirectionUnavailable,
        baseReport(MediaGridMorphClaimReadinessReason.DirectionUnavailable),
    )
    if (latestIdentity == null || bundle.identity != latestIdentity) {
        val selected = requestedDirection?.let(bundle.directions::get)
        return MediaGridMorphClaimPreparationResult.Unavailable(
            MediaGridMorphClaimReadinessReason.StaleIdentity,
            baseReport(
                MediaGridMorphClaimReadinessReason.StaleIdentity,
                selected?.plan,
                selected?.completeness,
            ),
        )
    }
    val selected = requestedDirection?.let(bundle.directions::get)
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
): MediaGridMorphImageCompleteness {
    val selected = plan.viewportPlan
    if (selected == null) {
        val visible = plan.slots.filter { slot ->
            val left = minOf(slot.startRect.left, slot.endRect.left)
            val top = minOf(slot.startRect.top, slot.endRect.top)
            val right = maxOf(slot.startRect.right, slot.endRect.right)
            val bottom = maxOf(slot.startRect.bottom, slot.endRect.bottom)
            right > plan.viewport.left && left < plan.viewport.right && bottom > plan.viewport.top && top < plan.viewport.bottom
        }
        val source = visible.mapNotNull { it.startContent.assetIdOrNull() }.toSet()
        val target = visible.mapNotNull { it.endContent.assetIdOrNull() }.toSet()
        val unresolved = (source + target).firstOrNull { it !in preparedIndex.preparedImageByAssetId }
        val textComplete = plan.headers.all { header ->
            (header.startTitle == null || textResources.layoutsByTitle.containsKey(header.startTitle)) &&
                (header.endTitle == null || textResources.layoutsByTitle.containsKey(header.endTitle))
        }
        val missingHeaderTitle = plan.headers.asSequence()
            .flatMap { sequenceOf(it.startTitle, it.endTitle) }
            .firstOrNull { !it.isNullOrBlank() && it !in textResources.layoutsByTitle }
        return MediaGridMorphImageCompleteness(
            source.size, source.count { it in preparedIndex.preparedImageByAssetId },
            target.size, target.count { it in preparedIndex.preparedImageByAssetId },
            unresolved, textComplete, plan.viewport.width > 0f && plan.viewport.height > 0f && visible.isNotEmpty(),
            missingHeaderTitle = missingHeaderTitle,
        )
    }
    val coordinates = HashSet<Pair<Int, Int>>()
    val source = LinkedHashSet<Long>()
    val target = LinkedHashSet<Long>()
    var geometryComplete = selected.viewport.width > 0f && selected.viewport.height > 0f && selected.rowPlans.isNotEmpty()
    var sourceViewportComplete = true
    selected.rowPlans.forEach { row ->
        row.cells.forEach { cell ->
            geometryComplete = geometryComplete && coordinates.add(cell.relativeRow to cell.column)
            (cell.startContent as? MediaGridMorphSlotContent.Image)?.assetId?.let(source::add)
            (cell.endContent as? MediaGridMorphSlotContent.Image)?.assetId?.let(target::add)
            val sourceIdentity = cell.sourcePreparedImageIdentity
            if (sourceIdentity != null) {
                val preparedIdentity = (cell.startContent as? MediaGridMorphSlotContent.Image)
                    ?.let { preparedIndex.preparedImageByAssetId[it.assetId]?.identity }
                sourceViewportComplete = sourceViewportComplete && preparedIdentity == sourceIdentity
            }
        }
    }
    val missingHeaderTitle = selected.headerPlans.asSequence()
        .flatMap { sequenceOf(it.startTitle, it.endTitle) }
        .firstOrNull { !it.isNullOrBlank() && it !in textResources.layoutsByTitle }
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
): MediaGridMorphRowRenderModel {
    val selected = plan.viewportPlan
    val cells = selected?.rowPlans?.flatMap { row ->
        row.cells.map { cell ->
            MediaGridMorphRowRenderCell(
                plan = cell,
                startImage = (cell.startContent as? MediaGridMorphSlotContent.Image)?.let { preparedIndex.preparedImageByAssetId[it.assetId] },
                endImage = (cell.endContent as? MediaGridMorphSlotContent.Image)?.let { preparedIndex.preparedImageByAssetId[it.assetId] },
            )
        }
    }.orEmpty()
    val headers = selected?.headerPlans?.map { header ->
        MediaGridMorphRowRenderHeader(
            plan = header,
            startText = header.startTitle?.let(textResources.layoutsByTitle::get),
            endText = header.endTitle?.let(textResources.layoutsByTitle::get),
        )
    }.orEmpty()
    val protected = LinkedHashSet<Long>()
    cells.forEach { cell ->
        cell.plan.startContent.assetIdOrNull()?.let(protected::add)
        cell.plan.endContent.assetIdOrNull()?.let(protected::add)
    }
    return MediaGridMorphRowRenderModel(
        viewport = selected?.viewport ?: plan.viewport,
        sourceCellSize = selected?.sourceCellSize ?: plan.viewport.width,
        targetCellSize = selected?.targetCellSize ?: plan.viewport.width,
        fixedFocalCenterY = selected?.fixedFocalCenterY ?: plan.viewport.center.y,
        focalV = selected?.focalV ?: 0.5f,
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
        isComplete = completeness.isComplete && cells.all { cell ->
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
    val gridLeft = viewport.left
    val viewportWidth = viewport.width.coerceAtMost(size.width)
    val viewportHeight = viewport.height.coerceAtMost(size.height)
    val currentCellSize = lerp(model.sourceCellSize, model.targetCellSize, p)
    val focalRowTop = model.fixedFocalCenterY - model.focalV * currentCellSize
    clipRect(0f, 0f, viewportWidth, viewportHeight) {
        var cellIndex = 0
        while (cellIndex < model.cells.size) {
            val cell = model.cells[cellIndex]
            val cellRect = cell.plan.sourceRect?.takeIf { p <= 0f } ?: Rect(
                left = gridLeft + cell.plan.column * currentCellSize,
                top = focalRowTop +
                    cell.plan.relativeRow * currentCellSize +
                    lerp(cell.plan.startHeaderOffsetPx, cell.plan.endHeaderOffsetPx, p),
                right = gridLeft + cell.plan.column * currentCellSize + currentCellSize,
                bottom = focalRowTop +
                    cell.plan.relativeRow * currentCellSize +
                    lerp(cell.plan.startHeaderOffsetPx, cell.plan.endHeaderOffsetPx, p) + currentCellSize,
            )
            val left = cellRect.left - viewport.left
            val top = cellRect.top - viewport.top
            val right = cellRect.right - viewport.left
            val bottom = cellRect.bottom - viewport.top
            val drawWidth = cellRect.width
            val drawHeight = cellRect.height
            val width = drawWidth.roundToInt().coerceAtLeast(1)
            val height = drawHeight.roundToInt().coerceAtLeast(1)
            clipRect(left, top, right, bottom) {
                val blend = mediaGridMorphCellBlend(cell.plan.startContent, cell.plan.endContent, p)
                fun drawPlaceholder() {
                    drawRect(
                        model.placeholderColor,
                        androidx.compose.ui.geometry.Offset(left, top),
                        androidx.compose.ui.geometry.Size(drawWidth, drawHeight),
                    )
                }
                fun drawPrepared(image: MediaGridResidentCanvasPreparedImage, alpha: Float) {
                        drawImage(
                            image.image,
                            image.srcOffset,
                            image.srcSize,
                            androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                            androidx.compose.ui.unit.IntSize(width, height),
                            alpha = alpha,
                        )
                }
                if (blend.drawPlaceholder) drawPlaceholder()
                blend.startAlpha?.let { alpha -> cell.startImage?.let { drawPrepared(it, alpha) } }
                blend.endAlpha?.let { alpha -> cell.endImage?.let { drawPrepared(it, alpha) } }
            }
            cellIndex++
        }
        var headerIndex = 0
        while (headerIndex < model.headers.size) {
            val header = model.headers[headerIndex]
            val height = lerp(header.plan.startHeightPx, header.plan.endHeightPx, p)
            val rowTop = focalRowTop + header.plan.relativeRow * currentCellSize
            val sourceRect = header.plan.sourceRect
            val top = if (p <= 0f && sourceRect != null) {
                sourceRect.top - viewport.top
            } else {
                rowTop +
                    lerp(header.plan.startOffsetBeforePx, header.plan.endOffsetBeforePx, p) - viewport.top
            }
            val drawHeight = if (p <= 0f && sourceRect != null) sourceRect.height else height
            val left = gridLeft - viewport.left
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
                    if (header.plan.startTitle != null && header.plan.startTitle == header.plan.endTitle) {
                        header.startText?.let {
                            drawText(it, color = model.textColor, topLeft = androidx.compose.ui.geometry.Offset(textX, textY))
                        }
                    } else {
                        header.startText?.let {
                            drawText(it, color = model.textColor, topLeft = androidx.compose.ui.geometry.Offset(textX, textY), alpha = 1f - p)
                        }
                        header.endText?.let {
                            drawText(it, color = model.textColor, topLeft = androidx.compose.ui.geometry.Offset(textX, textY), alpha = p)
                        }
                    }
                }
            }
            headerIndex++
        }
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
