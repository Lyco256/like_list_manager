package com.lyco256.llm

import androidx.compose.ui.geometry.Rect

internal data class MediaGridMorphRequiredCellIdentity(
    val relativeRow: Int,
    val column: Int,
)

internal data class MediaGridMorphRequiredHeaderIdentity(
    val relativeRow: Int,
    val startKey: String?,
    val endKey: String?,
)

/**
 * The single readiness/protection contract for one selected Morph plan.
 * Anything outside the swept viewport is optional and cannot make a claim
 * incomplete.
 */
internal data class MediaGridMorphRequiredRenderSet(
    val requiredSourceAssetIds: Set<Long>,
    val requiredTargetAssetIds: Set<Long>,
    val requiredSourceHeaderTitles: Set<String>,
    val requiredTargetHeaderTitles: Set<String>,
    val requiredCellIdentities: Set<MediaGridMorphRequiredCellIdentity>,
    val requiredHeaderIdentities: Set<MediaGridMorphRequiredHeaderIdentity>,
    val protectedAssetIds: Set<Long>,
    val optionalCellCount: Int,
    val optionalHeaderCount: Int,
    val requiredCellPlans: List<MediaGridMorphCellPlan> = emptyList(),
    val requiredHeaderPlanIndexes: BooleanArray = BooleanArray(0),
) {
    val requiredSourceImageCount: Int get() = requiredSourceAssetIds.size
    val requiredTargetImageCount: Int get() = requiredTargetAssetIds.size
    val requiredHeaderTitleCount: Int
        get() = (requiredSourceHeaderTitles + requiredTargetHeaderTitles).size
}

internal fun MediaGridMorphPlan.requiredRenderSet(): MediaGridMorphRequiredRenderSet {
    val selected = viewportPlan
    if (selected == null) {
        val source = LinkedHashSet<Long>()
        val target = LinkedHashSet<Long>()
        val cells = LinkedHashSet<MediaGridMorphRequiredCellIdentity>()
        slots.forEach { slot ->
            val swept = sweptRect(slot.startRect, slot.endRect)
            if (!sweptIntersectsViewport(swept, viewport)) return@forEach
            cells += MediaGridMorphRequiredCellIdentity(slot.row, slot.column)
            slot.startContent.assetIdOrNull()?.let(source::add)
            slot.endContent.assetIdOrNull()?.let(target::add)
        }
        val sourceTitles = LinkedHashSet<String>()
        val targetTitles = LinkedHashSet<String>()
        val headers = LinkedHashSet<MediaGridMorphRequiredHeaderIdentity>()
        headersForLegacyPlan().forEach { (index, header) ->
            if (!sweptIntersectsViewport(sweptRect(header.startRect, header.endRect), viewport)) return@forEach
            headers += MediaGridMorphRequiredHeaderIdentity(index, header.startKey, header.endKey)
            header.startTitle?.takeIf(String::isNotBlank)?.let(sourceTitles::add)
            header.endTitle?.takeIf(String::isNotBlank)?.let(targetTitles::add)
        }
        return MediaGridMorphRequiredRenderSet(
            requiredSourceAssetIds = source,
            requiredTargetAssetIds = target,
            requiredSourceHeaderTitles = sourceTitles,
            requiredTargetHeaderTitles = targetTitles,
            requiredCellIdentities = cells,
            requiredHeaderIdentities = headers,
            protectedAssetIds = (source + target).toSet(),
            optionalCellCount = (slots.size - cells.size).coerceAtLeast(0),
            optionalHeaderCount = (this.headers.size - headers.size).coerceAtLeast(0),
        )
    }

    val source = LinkedHashSet<Long>()
    val target = LinkedHashSet<Long>()
    val sourceTitles = LinkedHashSet<String>()
    val targetTitles = LinkedHashSet<String>()
    val cells = LinkedHashSet<MediaGridMorphRequiredCellIdentity>()
    val headers = LinkedHashSet<MediaGridMorphRequiredHeaderIdentity>()
    val requiredCellPlans = ArrayList<MediaGridMorphCellPlan>()
    val requiredHeaderPlanIndexes = BooleanArray(selected.headerPlans.size)

    selected.rowPlans.forEach { row ->
        row.cells.forEach { cell ->
            val startRect = mediaGridMorphRowCellRect(selected, cell, 0f)
            val endRect = mediaGridMorphRowCellRect(selected, cell, 1f)
            if (!sweptIntersectsViewport(sweptRect(startRect, endRect), selected.viewport)) return@forEach
            cells += MediaGridMorphRequiredCellIdentity(cell.relativeRow, cell.column)
            requiredCellPlans += cell
            cell.startContent.assetIdOrNull()?.let(source::add)
            cell.endContent.assetIdOrNull()?.let(target::add)
        }
    }
    selected.headerPlans.forEachIndexed { headerIndex, header ->
        val startRect = mediaGridMorphRowHeaderRect(selected, header, 0f)
        val endRect = mediaGridMorphRowHeaderRect(selected, header, 1f)
        if (!sweptIntersectsViewport(sweptRect(startRect, endRect), selected.viewport)) return@forEachIndexed
        requiredHeaderPlanIndexes[headerIndex] = true
        headers += MediaGridMorphRequiredHeaderIdentity(
            relativeRow = header.relativeRow,
            startKey = header.startKey,
            endKey = header.endKey,
        )
        header.startTitle?.takeIf(String::isNotBlank)?.let(sourceTitles::add)
        header.endTitle?.takeIf(String::isNotBlank)?.let(targetTitles::add)
    }
    return MediaGridMorphRequiredRenderSet(
        requiredSourceAssetIds = source,
        requiredTargetAssetIds = target,
        requiredSourceHeaderTitles = sourceTitles,
        requiredTargetHeaderTitles = targetTitles,
        requiredCellIdentities = cells,
        requiredHeaderIdentities = headers,
        protectedAssetIds = (source + target).toSet(),
        optionalCellCount = (selected.rowPlans.sumOf { it.cells.size } - cells.size).coerceAtLeast(0),
        optionalHeaderCount = (selected.headerPlans.size - headers.size).coerceAtLeast(0),
        requiredCellPlans = requiredCellPlans,
        requiredHeaderPlanIndexes = requiredHeaderPlanIndexes,
    )
}

internal fun mediaGridMorphRowHeaderRect(
    plan: MediaGridMorphViewportPlan,
    header: MediaGridMorphHeaderPlan,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    val cellSize = mediaGridMorphCurrentCellSize(plan, p)
    val focalRowTop = plan.fixedFocalCenterY - plan.focalV * cellSize
    val targetAdjustment = if (p >= 1f) {
        plan.targetAnchorRowTop - (plan.fixedFocalCenterY - plan.focalV * plan.targetCellSize)
    } else {
        0f
    }
    val top = focalRowTop +
        header.relativeRow * cellSize +
        lerpMorphHeaderOffset(header.startOffsetBeforePx, header.endOffsetBeforePx, p) +
        targetAdjustment
    val height = header.startHeightPx + (header.endHeightPx - header.startHeightPx) * p
    return Rect(plan.viewport.left, top, plan.viewport.right, top + height)
}

private fun MediaGridMorphPlan.headersForLegacyPlan(): List<MediaGridMorphHeaderBandWithIndex> =
    headers.mapIndexed { index, header -> MediaGridMorphHeaderBandWithIndex(index, header) }

private data class MediaGridMorphHeaderBandWithIndex(
    val index: Int,
    val header: MediaGridMorphHeaderBand,
) {
    val startRect: Rect get() = header.startRect
    val endRect: Rect get() = header.endRect
    val startKey: String? get() = header.startKey
    val endKey: String? get() = header.endKey
    val startTitle: String? get() = header.startTitle
    val endTitle: String? get() = header.endTitle
}

private fun sweptRect(start: Rect, end: Rect): Rect = Rect(
    left = minOf(start.left, end.left),
    top = minOf(start.top, end.top),
    right = maxOf(start.right, end.right),
    bottom = maxOf(start.bottom, end.bottom),
)

private fun sweptIntersectsViewport(swept: Rect, viewport: Rect): Boolean =
    swept.right > viewport.left && swept.left < viewport.right &&
        swept.bottom > viewport.top && swept.top < viewport.bottom

private fun lerpMorphHeaderOffset(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress
