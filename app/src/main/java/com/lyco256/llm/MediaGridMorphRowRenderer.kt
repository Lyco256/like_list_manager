package com.lyco256.llm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
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
)

@Composable
internal fun rememberMediaGridMorphRowRenderModel(
    plan: State<MediaGridMorphPlan?>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
): State<MediaGridMorphRowRenderModel?> {
    val active = plan.value
    val viewportPlan = active?.viewportPlan
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val horizontalPadding = with(density) { 12.dp.toPx() }
    val verticalPadding = with(density) { 8.dp.toPx() }
    val style = androidx.compose.material3.MaterialTheme.typography.titleSmall.copy(
        color = colors.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
    val titles = remember(viewportPlan) {
        buildList {
            viewportPlan?.headerPlans?.forEach { header ->
                header.startTitle?.let(::add)
                header.endTitle?.let(::add)
            }
        }.distinct()
    }
    val textLayouts = remember(titles, style, textMeasurer, density.density, density.fontScale) {
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
    val model = remember(viewportPlan, preparedIndex.drawIndexVersion, textLayouts, colors.surface, colors.surfaceVariant, colors.onSurface) {
        viewportPlan?.let { selected ->
            val imageByAsset = preparedIndex.preparedImageByAssetId
            MediaGridMorphRowRenderModel(
                viewport = selected.viewport,
                rows = selected.rowPlans,
                cells = selected.rowPlans.flatMap { row ->
                    row.cells.map { cell ->
                        MediaGridMorphRowRenderCell(
                            plan = cell,
                            startImage = (cell.startContent as? MediaGridMorphSlotContent.Image)?.let { imageByAsset[it.assetId] },
                            endImage = (cell.endContent as? MediaGridMorphSlotContent.Image)?.let { imageByAsset[it.assetId] },
                        )
                    }
                },
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
            )
        }
    }
    return remember(model) { androidx.compose.runtime.mutableStateOf(model) }
}

/**
 * Same-surface Morph renderer. It is attached directly to LazyVerticalGrid;
 * no second grid, zIndex overlay, or handoff translation is involved.
 */
internal fun Modifier.mediaGridMorphRowReflowCanvas(
    renderModel: State<MediaGridMorphRowRenderModel?>,
    progress: State<Float>,
    enabled: Boolean,
    testTag: String = "media_grid_morph_same_surface",
): Modifier {
    if (!enabled) return this
    return this
        .testTag(testTag)
        .drawWithCache {
            onDrawWithContent {
                val model = renderModel.value
                if (model == null) {
                    drawContent()
                    return@onDrawWithContent
                }
                // Once the claim has a bounded render model, the resident and
                // normal cell content must not leak through moving row gaps.
                // The row plan owns the complete visible Morph surface.
                val p = progress.value.coerceIn(0f, 1f)
                val viewport = model.viewport

                fun local(rect: Rect): Rect = Rect(
                    rect.left - viewport.left,
                    rect.top - viewport.top,
                    rect.right - viewport.left,
                    rect.bottom - viewport.top,
                )

                fun drawImage(image: MediaGridResidentCanvasPreparedImage, rect: Rect, alpha: Float) {
                    if (alpha <= 0f || rect.width <= 0f || rect.height <= 0f) return
                    drawImage(
                        image = image.image,
                        srcOffset = image.srcOffset,
                        srcSize = image.srcSize,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                        alpha = alpha,
                    )
                }

                clipRect(0f, 0f, size.width, size.height) {
                    model.cells.forEach { cell ->
                        val current = local(Rect(
                            lerp(cell.plan.startRect.left, cell.plan.endRect.left, p),
                            lerp(cell.plan.startRect.top, cell.plan.endRect.top, p),
                            lerp(cell.plan.startRect.right, cell.plan.endRect.right, p),
                            lerp(cell.plan.startRect.bottom, cell.plan.endRect.bottom, p),
                        ))
                        if (current.width <= 0f || current.height <= 0f) return@forEach
                        drawRect(model.placeholderColor, current.topLeft, current.size)
                        clipRect(current.left, current.top, current.right, current.bottom) {
                            val same = cell.plan.startContent is MediaGridMorphSlotContent.Image &&
                                cell.plan.startContent == cell.plan.endContent
                            if (same) {
                                cell.startImage?.let { drawImage(it, current, 1f) }
                            } else {
                                cell.startImage?.let { drawImage(it, current, 1f - p) }
                                cell.endImage?.let { drawImage(it, current, p) }
                            }
                        }
                    }
                    model.headers.forEach { header ->
                        val rect = local(Rect(
                            lerp(header.plan.startRect.left, header.plan.endRect.left, p),
                            lerp(header.plan.startRect.top, header.plan.endRect.top, p),
                            lerp(header.plan.startRect.right, header.plan.endRect.right, p),
                            lerp(header.plan.startRect.bottom, header.plan.endRect.bottom, p),
                        ))
                        if (rect.width <= 0f || rect.height <= 0f) return@forEach
                        drawRect(model.surfaceColor, rect.topLeft, rect.size)
                        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                            val textPosition = rect.topLeft + androidx.compose.ui.geometry.Offset(
                                model.horizontalTextPaddingPx,
                                model.verticalTextPaddingPx,
                            )
                            if (header.plan.startTitle != null && header.plan.startTitle == header.plan.endTitle) {
                                header.startText?.let { drawText(it, color = model.textColor, topLeft = textPosition) }
                            } else {
                                header.startText?.let { drawText(it, color = model.textColor, topLeft = textPosition, alpha = 1f - p) }
                                header.endText?.let { drawText(it, color = model.textColor, topLeft = textPosition, alpha = p) }
                            }
                        }
                    }
                }
            }
        }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
