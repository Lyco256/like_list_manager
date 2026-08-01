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

internal data class MediaGridMorphRowRenderHeader(
    val plan: MediaGridMorphHeaderPlan,
    val startText: TextLayoutResult?,
    val endText: TextLayoutResult?,
)

/** Immutable claim-time data consumed by the single LazyGrid draw surface. */
internal data class MediaGridMorphRowRenderModel(
    val viewport: Rect,
    val rows: List<MediaGridMorphRowPlan>,
    val cells: List<MediaGridMorphRowRenderCell>,
    val headers: List<MediaGridMorphRowRenderHeader>,
    val surfaceColor: Color,
    val placeholderColor: Color,
    val textColor: Color,
    val horizontalTextPaddingPx: Float,
    val verticalTextPaddingPx: Float,
    val protectedAssetIds: LongArray,
)

/**
 * Resolves all bounded image endpoints and header layouts once for a frozen
 * plan. The draw-index version is deliberately not a key: an active Morph
 * never rebuilds its model when resident contents change.
 */
@Composable
internal fun rememberMediaGridMorphRowRenderModel(
    plan: MediaGridMorphPlan?,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
): MediaGridMorphRowRenderModel? {
    val viewportPlan = plan?.viewportPlan
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val horizontalPadding = with(density) { 12.dp.toPx() }
    val verticalPadding = with(density) { 8.dp.toPx() }
    val style = androidx.compose.material3.MaterialTheme.typography.titleSmall.copy(
        color = colors.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
    val titles = remember(plan) {
        buildList {
            viewportPlan?.headerPlans?.forEach { header ->
                header.startTitle?.let(::add)
                header.endTitle?.let(::add)
            }
        }.distinct()
    }
    val textLayouts = remember(plan, titles, style, textMeasurer, density.density, density.fontScale) {
        titles.associateWith { title ->
            textMeasurer.measure(
                text = title,
                style = style,
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = (viewportPlan?.viewport?.width ?: 0f).roundToInt().coerceAtLeast(0)),
            )
        }
    }
    return remember(plan, textLayouts, colors.surface, colors.surfaceVariant, colors.onSurface) {
        viewportPlan?.let { selected ->
            val imageByAsset = preparedIndex.preparedImageByAssetId
            val cells = selected.rowPlans.flatMap { row ->
                row.cells.map { cell ->
                    MediaGridMorphRowRenderCell(
                        plan = cell,
                        startImage = (cell.startContent as? MediaGridMorphSlotContent.Image)?.let { imageByAsset[it.assetId] },
                        endImage = (cell.endContent as? MediaGridMorphSlotContent.Image)?.let { imageByAsset[it.assetId] },
                    )
                }
            }
            val protected = LinkedHashSet<Long>()
            cells.forEach { cell ->
                cell.plan.startContent.assetIdOrNull()?.let(protected::add)
                cell.plan.endContent.assetIdOrNull()?.let(protected::add)
            }
            MediaGridMorphRowRenderModel(
                viewport = selected.viewport,
                rows = selected.rowPlans,
                cells = cells,
                headers = selected.headerPlans.map { header ->
                    MediaGridMorphRowRenderHeader(
                        plan = header,
                        startText = header.startTitle?.let(textLayouts::get),
                        endText = header.endTitle?.let(textLayouts::get),
                    )
                },
                surfaceColor = colors.surface.copy(alpha = 1f),
                placeholderColor = colors.surfaceVariant.copy(alpha = 1f),
                textColor = colors.onSurface,
                horizontalTextPaddingPx = horizontalPadding,
                verticalTextPaddingPx = verticalPadding,
                protectedAssetIds = protected.toLongArray(),
            )
        }
    }
}

/** Pure draw function used by the unified resident/Morph surface. */
internal fun DrawScope.drawMediaGridMorphRow(
    model: MediaGridMorphRowRenderModel,
    progress: Float,
) {
    val p = progress.coerceIn(0f, 1f)
    val viewport = model.viewport
    clipRect(0f, 0f, size.width, size.height) {
        var cellIndex = 0
        while (cellIndex < model.cells.size) {
            val cell = model.cells[cellIndex]
            val start = cell.plan.startRect
            val end = cell.plan.endRect
            val left = lerp(start.left, end.left, p) - viewport.left
            val top = lerp(start.top, end.top, p) - viewport.top
            val right = lerp(start.right, end.right, p) - viewport.left
            val bottom = lerp(start.bottom, end.bottom, p) - viewport.top
            if (right > left && bottom > top) {
                val width = (right - left).roundToInt()
                val height = (bottom - top).roundToInt()
                drawRect(
                    model.placeholderColor,
                    androidx.compose.ui.geometry.Offset(left, top),
                    androidx.compose.ui.geometry.Size(right - left, bottom - top),
                )
                clipRect(left, top, right, bottom) {
                    val same = cell.plan.startContent is MediaGridMorphSlotContent.Image &&
                        cell.plan.startContent == cell.plan.endContent
                    if (same) {
                        cell.startImage?.let { image ->
                            drawImage(
                                image.image,
                                image.srcOffset,
                                image.srcSize,
                                androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                                androidx.compose.ui.unit.IntSize(width, height),
                                alpha = 1f - p,
                            )
                        }
                        cell.endImage?.let { image ->
                            drawImage(
                                image.image,
                                image.srcOffset,
                                image.srcSize,
                                androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                                androidx.compose.ui.unit.IntSize(width, height),
                                alpha = p,
                            )
                        }
                    } else {
                        cell.startImage?.let { image ->
                            drawImage(
                                image.image,
                                image.srcOffset,
                                image.srcSize,
                                androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                                androidx.compose.ui.unit.IntSize(width, height),
                                alpha = 1f - p,
                            )
                        }
                        cell.endImage?.let { image ->
                            drawImage(
                                image.image,
                                image.srcOffset,
                                image.srcSize,
                                androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                                androidx.compose.ui.unit.IntSize(width, height),
                                alpha = p,
                            )
                        }
                    }
                }
            }
            cellIndex++
        }
        var headerIndex = 0
        while (headerIndex < model.headers.size) {
            val header = model.headers[headerIndex]
            val start = header.plan.startRect
            val end = header.plan.endRect
            val left = lerp(start.left, end.left, p) - viewport.left
            val top = lerp(start.top, end.top, p) - viewport.top
            val right = lerp(start.right, end.right, p) - viewport.left
            val bottom = lerp(start.bottom, end.bottom, p) - viewport.top
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
