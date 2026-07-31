package com.lyco256.llm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Collections
import kotlin.math.roundToInt

internal enum class MediaGridMorphCanvasMode {
    Disabled,
    TestVisible,
    ProductionVisible,
}

internal data class MediaGridMorphRenderSlot(
    val row: Int,
    val column: Int,
    val startRect: Rect,
    val endRect: Rect,
    val startContent: MediaGridMorphSlotContent,
    val endContent: MediaGridMorphSlotContent,
    val startImageDrawingRect: Rect,
    val endImageDrawingRect: Rect,
    val edge: MediaGridMorphSlotEdge,
    val startImage: MediaGridResidentCanvasPreparedImage?,
    val endImage: MediaGridResidentCanvasPreparedImage?,
)

internal data class MediaGridMorphRenderHeader(
    val startRect: Rect,
    val endRect: Rect,
    val startTitle: String?,
    val endTitle: String?,
    val startTextLayout: TextLayoutResult?,
    val endTextLayout: TextLayoutResult?,
)

internal data class MediaGridMorphRenderModel(
    val sourceRevision: Long,
    val frameKey: MediaGridRenderKey,
    val preparedIndexVersion: Long,
    val viewport: Rect,
    val localViewportRect: Rect,
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
    val slots: List<MediaGridMorphRenderSlot>,
    val headers: List<MediaGridMorphRenderHeader>,
    val surfaceColor: Color,
    val textColor: Color,
    val placeholderColor: Color,
    val fillViewportBackground: Boolean,
    val horizontalTextPaddingPx: Float,
    val verticalTextPaddingPx: Float,
)

internal fun buildMediaGridMorphRenderModel(
    plan: MediaGridMorphPlan,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textLayoutsByTitle: Map<String, TextLayoutResult>,
    surfaceColor: Color,
    textColor: Color,
    placeholderColor: Color,
    fillViewportBackground: Boolean = false,
    horizontalTextPaddingPx: Float,
    verticalTextPaddingPx: Float,
    onImageResolved: ((Long) -> Unit)? = null,
): MediaGridMorphRenderModel {
    val pair = plan.preparedPair
    val resolvedByAssetId = HashMap<Long, MediaGridResidentCanvasPreparedImage?>()

    fun resolve(assetId: Long?): MediaGridResidentCanvasPreparedImage? {
        assetId ?: return null
        if (resolvedByAssetId.containsKey(assetId)) return resolvedByAssetId[assetId]
        onImageResolved?.invoke(assetId)
        return preparedIndex.preparedImageByAssetId[assetId].also {
            resolvedByAssetId[assetId] = it
        }
    }

    fun resolveContent(content: MediaGridMorphSlotContent): Pair<MediaGridMorphSlotContent, MediaGridResidentCanvasPreparedImage?> =
        when (content) {
            MediaGridMorphSlotContent.Placeholder -> content to null
            is MediaGridMorphSlotContent.Image -> {
                val image = resolve(content.assetId)
                if (image == null) MediaGridMorphSlotContent.Placeholder to null else content to image
            }
        }

    val slots = ArrayList<MediaGridMorphRenderSlot>(pair.slots.size)
    for (slot in pair.slots) {
        val (startContent, startImage) = resolveContent(slot.startContent)
        val (endContent, endImage) = resolveContent(slot.endContent)
        slots += MediaGridMorphRenderSlot(
            row = slot.row,
            column = slot.column,
            startRect = slot.startRect,
            endRect = slot.endRect,
            startContent = startContent,
            endContent = endContent,
            startImageDrawingRect = slot.startImageDrawingRect,
            endImageDrawingRect = slot.endImageDrawingRect,
            edge = slot.edge,
            startImage = startImage,
            endImage = endImage,
        )
    }
    val headers = ArrayList<MediaGridMorphRenderHeader>(pair.headers.size)
    for (header in pair.headers) {
        headers += MediaGridMorphRenderHeader(
            startRect = header.startRect,
            endRect = header.endRect,
            startTitle = header.startTitle,
            endTitle = header.endTitle,
            startTextLayout = header.startTitle?.let(textLayoutsByTitle::get),
            endTextLayout = header.endTitle?.let(textLayoutsByTitle::get),
        )
    }
    return MediaGridMorphRenderModel(
        sourceRevision = pair.sourceRevision,
        frameKey = pair.frameKey,
        preparedIndexVersion = preparedIndex.drawIndexVersion,
        viewport = pair.viewport,
        localViewportRect = Rect(0f, 0f, pair.viewport.width, pair.viewport.height),
        canvasWidthPx = pair.viewport.width.roundToInt(),
        canvasHeightPx = pair.viewport.height.roundToInt(),
        slots = Collections.unmodifiableList(slots),
        headers = Collections.unmodifiableList(headers),
        surfaceColor = surfaceColor.copy(alpha = 1f),
        textColor = textColor,
        placeholderColor = placeholderColor.copy(alpha = 1f),
        fillViewportBackground = fillViewportBackground,
        horizontalTextPaddingPx = horizontalTextPaddingPx,
        verticalTextPaddingPx = verticalTextPaddingPx,
    )
}

@Composable
internal fun MediaGridMorphCanvasLayer(
    plan: MediaGridMorphPlan,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    progress: State<Float>,
    correction: State<Offset>,
    mode: MediaGridMorphCanvasMode = MediaGridMorphCanvasMode.Disabled,
    modifier: Modifier = Modifier,
    onRenderModelBuilt: (() -> Unit)? = null,
    onImageResolved: ((Long) -> Unit)? = null,
    onTextMeasured: ((String) -> Unit)? = null,
) {
    if (mode == MediaGridMorphCanvasMode.Disabled) return
    check(mode != MediaGridMorphCanvasMode.TestVisible || BuildConfig.TEST_HARNESS) {
        "MediaGridMorphCanvasLayer is restricted to TEST_HARNESS"
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val style = androidx.compose.material3.MaterialTheme.typography.titleSmall.copy(
        color = colors.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
    val textMeasurer = rememberTextMeasurer()
    val horizontalPaddingPx = with(density) { 12.dp.toPx() }
    val verticalPaddingPx = with(density) { 8.dp.toPx() }
    val maxTextWidth = (plan.viewport.width - horizontalPaddingPx * 2f).roundToInt().coerceAtLeast(0)
    val titles = remember(plan.preparedPair) {
        val unique = LinkedHashSet<String>()
        for (header in plan.headers) {
            header.startTitle?.let(unique::add)
            header.endTitle?.let(unique::add)
        }
        Collections.unmodifiableList(unique.toList())
    }
    val textLayouts = remember(
        titles,
        maxTextWidth,
        density.density,
        density.fontScale,
        layoutDirection,
        style,
        textMeasurer,
    ) {
        val measured = LinkedHashMap<String, TextLayoutResult>(titles.size)
        for (title in titles) {
            onTextMeasured?.invoke(title)
            measured[title] = textMeasurer.measure(
                text = title,
                style = style,
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = maxTextWidth),
                layoutDirection = layoutDirection,
                density = density,
            )
        }
        Collections.unmodifiableMap(measured)
    }
    val renderModel = remember(
        plan.preparedPair,
        preparedIndex.drawIndexVersion,
        textLayouts,
        colors.surface,
        colors.onSurface,
        mode,
        horizontalPaddingPx,
        verticalPaddingPx,
    ) {
        buildMediaGridMorphRenderModel(
            plan = plan,
            preparedIndex = preparedIndex,
            textLayoutsByTitle = textLayouts,
            surfaceColor = colors.surface,
            textColor = colors.onSurface,
            placeholderColor = colors.surfaceVariant,
            fillViewportBackground = mode == MediaGridMorphCanvasMode.ProductionVisible,
            horizontalTextPaddingPx = horizontalPaddingPx,
            verticalTextPaddingPx = verticalPaddingPx,
            onImageResolved = onImageResolved,
        ).also { onRenderModelBuilt?.invoke() }
    }
    MediaGridMorphCanvas(
        renderModel = renderModel,
        progress = progress,
        correction = correction,
        modifier = modifier,
    )
}

@Composable
private fun MediaGridMorphCanvas(
    renderModel: MediaGridMorphRenderModel,
    progress: State<Float>,
    correction: State<Offset>,
    modifier: Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("media_grid_morph_canvas"),
    ) {
        if (
            size.width.roundToInt() != renderModel.canvasWidthPx ||
            size.height.roundToInt() != renderModel.canvasHeightPx
        ) return@Canvas
        val p = progress.value.coerceIn(0f, 1f)
        val currentCorrection = correction.value
        val viewportLeft = renderModel.viewport.left
        val viewportTop = renderModel.viewport.top

        fun DrawScope.drawPreparedImage(
            image: MediaGridResidentCanvasPreparedImage,
            rect: Rect,
            alpha: Float,
            blendMode: BlendMode = BlendMode.SrcOver,
        ) {
            val width = rect.width.roundToInt()
            val height = rect.height.roundToInt()
            if (width <= 0 || height <= 0 || alpha <= 0f) return
            drawImage(
                image = image.image,
                srcOffset = image.srcOffset,
                srcSize = image.srcSize,
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(width, height),
                alpha = alpha,
                blendMode = blendMode,
            )
        }

        fun toCanvasRect(rect: Rect): Rect = Rect(
            rect.left - viewportLeft + currentCorrection.x,
            rect.top - viewportTop + currentCorrection.y,
            rect.right - viewportLeft + currentCorrection.x,
            rect.bottom - viewportTop + currentCorrection.y,
        )

        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            if (renderModel.fillViewportBackground) {
                drawRect(
                    color = renderModel.surfaceColor,
                    topLeft = renderModel.localViewportRect.topLeft,
                    size = renderModel.localViewportRect.size,
                )
            }
            for (slot in renderModel.slots) {
                val currentRect = Rect(
                    lerpMorphEdge(slot.startRect.left, slot.endRect.left, p) - viewportLeft + currentCorrection.x,
                    lerpMorphEdge(slot.startRect.top, slot.endRect.top, p) - viewportTop + currentCorrection.y,
                    lerpMorphEdge(slot.startRect.right, slot.endRect.right, p) - viewportLeft + currentCorrection.x,
                    lerpMorphEdge(slot.startRect.bottom, slot.endRect.bottom, p) - viewportTop + currentCorrection.y,
                )
                if (currentRect.width <= 0f || currentRect.height <= 0f) continue
                val hasPlaceholderEndpoint =
                    slot.startContent is MediaGridMorphSlotContent.Placeholder ||
                        slot.endContent is MediaGridMorphSlotContent.Placeholder
                if (hasPlaceholderEndpoint) {
                    drawRect(renderModel.placeholderColor, currentRect.topLeft, currentRect.size)
                }
                clipRect(
                    left = currentRect.left,
                    top = currentRect.top,
                    right = currentRect.right,
                    bottom = currentRect.bottom,
                ) {
                    val sameImage = slot.startContent is MediaGridMorphSlotContent.Image &&
                        slot.endContent is MediaGridMorphSlotContent.Image &&
                        slot.startContent == slot.endContent
                    if (sameImage) {
                        slot.startImage?.let { drawPreparedImage(it, currentRect, 1f) }
                    } else if (!hasPlaceholderEndpoint) {
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(currentRect, Paint())
                        }
                        if (p < 1f) {
                            slot.startImage?.let {
                                drawPreparedImage(it, currentRect, 1f - p, BlendMode.Plus)
                            }
                        }
                        if (p > 0f) {
                            slot.endImage?.let {
                                drawPreparedImage(it, currentRect, p, BlendMode.Plus)
                            }
                        }
                        drawIntoCanvas { it.restore() }
                    } else {
                        val startRect = when (slot.edge) {
                            MediaGridMorphSlotEdge.Decrease -> toCanvasRect(slot.startImageDrawingRect)
                            else -> currentRect
                        }
                        val endRect = when (slot.edge) {
                            MediaGridMorphSlotEdge.Increase -> toCanvasRect(slot.endImageDrawingRect)
                            else -> currentRect
                        }
                        if (p < 1f) slot.startImage?.let { drawPreparedImage(it, startRect, 1f - p) }
                        if (p > 0f) slot.endImage?.let { drawPreparedImage(it, endRect, p) }
                    }
                }
            }

            for (header in renderModel.headers) {
                val left = lerpMorphEdge(header.startRect.left, header.endRect.left, p) -
                    viewportLeft + currentCorrection.x
                val top = lerpMorphEdge(header.startRect.top, header.endRect.top, p) -
                    viewportTop + currentCorrection.y
                val right = lerpMorphEdge(header.startRect.right, header.endRect.right, p) -
                    viewportLeft + currentCorrection.x
                val bottom = lerpMorphEdge(header.startRect.bottom, header.endRect.bottom, p) -
                    viewportTop + currentCorrection.y
                if (right <= left || bottom <= top) continue
                drawRect(
                    color = renderModel.surfaceColor,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                )
                clipRect(left = left, top = top, right = right, bottom = bottom) {
                    val textPosition = Offset(
                        left + renderModel.horizontalTextPaddingPx,
                        top + renderModel.verticalTextPaddingPx,
                    )
                    if (header.startTitle != null && header.startTitle == header.endTitle) {
                        header.startTextLayout?.let {
                            drawText(it, color = renderModel.textColor, topLeft = textPosition, alpha = 1f)
                        }
                    } else {
                        if (p < 1f) {
                            header.startTextLayout?.let {
                                drawText(it, color = renderModel.textColor, topLeft = textPosition, alpha = 1f - p)
                            }
                        }
                        if (p > 0f) {
                            header.endTextLayout?.let {
                                drawText(it, color = renderModel.textColor, topLeft = textPosition, alpha = p)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun lerpMorphEdge(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress

internal fun mediaGridMorphCanvasRect(
    startRect: Rect,
    endRect: Rect,
    viewport: Rect,
    correction: Offset,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        lerpMorphEdge(startRect.left, endRect.left, p) - viewport.left + correction.x,
        lerpMorphEdge(startRect.top, endRect.top, p) - viewport.top + correction.y,
        lerpMorphEdge(startRect.right, endRect.right, p) - viewport.left + correction.x,
        lerpMorphEdge(startRect.bottom, endRect.bottom, p) - viewport.top + correction.y,
    )
}
