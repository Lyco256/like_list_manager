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
}

internal data class MediaGridMorphRenderSlot(
    val row: Int,
    val column: Int,
    val startRect: Rect,
    val endRect: Rect,
    val startAssetId: Long?,
    val endAssetId: Long?,
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
    val horizontalTextPaddingPx: Float,
    val verticalTextPaddingPx: Float,
)

internal fun buildMediaGridMorphRenderModel(
    plan: MediaGridMorphPlan,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    textLayoutsByTitle: Map<String, TextLayoutResult>,
    surfaceColor: Color,
    textColor: Color,
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

    val slots = ArrayList<MediaGridMorphRenderSlot>(pair.slots.size)
    for (slot in pair.slots) {
        slots += MediaGridMorphRenderSlot(
            row = slot.row,
            column = slot.column,
            startRect = slot.startRect,
            endRect = slot.endRect,
            startAssetId = slot.startAssetId,
            endAssetId = slot.endAssetId,
            startImage = resolve(slot.startAssetId),
            endImage = if (slot.endAssetId == slot.startAssetId) {
                resolve(slot.startAssetId)
            } else {
                resolve(slot.endAssetId)
            },
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
    check(BuildConfig.TEST_HARNESS) {
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
        horizontalPaddingPx,
        verticalPaddingPx,
    ) {
        buildMediaGridMorphRenderModel(
            plan = plan,
            preparedIndex = preparedIndex,
            textLayoutsByTitle = textLayouts,
            surfaceColor = colors.surface,
            textColor = colors.onSurface,
            horizontalTextPaddingPx = horizontalPaddingPx,
            verticalTextPaddingPx = verticalPaddingPx,
            onImageResolved = onImageResolved,
        ).also { onRenderModelBuilt?.invoke() }
    }
    val imageLayerPaint = remember { Paint() }

    MediaGridMorphCanvas(
        renderModel = renderModel,
        progress = progress,
        correction = correction,
        imageLayerPaint = imageLayerPaint,
        modifier = modifier,
    )
}

@Composable
private fun MediaGridMorphCanvas(
    renderModel: MediaGridMorphRenderModel,
    progress: State<Float>,
    correction: State<Offset>,
    imageLayerPaint: Paint,
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
        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(
                    renderModel.localViewportRect,
                    imageLayerPaint,
                )
            }
            for (slot in renderModel.slots) {
                val left = lerpMorphEdge(slot.startRect.left, slot.endRect.left, p) -
                    viewportLeft + currentCorrection.x
                val top = lerpMorphEdge(slot.startRect.top, slot.endRect.top, p) -
                    viewportTop + currentCorrection.y
                val right = lerpMorphEdge(slot.startRect.right, slot.endRect.right, p) -
                    viewportLeft + currentCorrection.x
                val bottom = lerpMorphEdge(slot.startRect.bottom, slot.endRect.bottom, p) -
                    viewportTop + currentCorrection.y
                if (right <= left || bottom <= top) continue
                val dstLeft = left.roundToInt()
                val dstTop = top.roundToInt()
                val dstRight = right.roundToInt()
                val dstBottom = bottom.roundToInt()
                val dstWidth = dstRight - dstLeft
                val dstHeight = dstBottom - dstTop
                if (dstWidth <= 0 || dstHeight <= 0) continue
                clipRect(left = left, top = top, right = right, bottom = bottom) {
                    val sameAsset = slot.startAssetId != null && slot.startAssetId == slot.endAssetId
                    if (sameAsset) {
                        slot.startImage?.let { image ->
                            drawImage(
                                image = image.image,
                                srcOffset = image.srcOffset,
                                srcSize = image.srcSize,
                                dstOffset = IntOffset(dstLeft, dstTop),
                                dstSize = IntSize(dstWidth, dstHeight),
                                alpha = 1f,
                                blendMode = BlendMode.Plus,
                            )
                        }
                    } else {
                        val startAlpha = 1f - p
                        if (startAlpha > 0f) {
                            slot.startImage?.let { image ->
                                drawImage(
                                    image = image.image,
                                    srcOffset = image.srcOffset,
                                    srcSize = image.srcSize,
                                    dstOffset = IntOffset(dstLeft, dstTop),
                                    dstSize = IntSize(dstWidth, dstHeight),
                                    alpha = startAlpha,
                                    blendMode = BlendMode.Plus,
                                )
                            }
                        }
                        if (p > 0f) {
                            slot.endImage?.let { image ->
                                drawImage(
                                    image = image.image,
                                    srcOffset = image.srcOffset,
                                    srcSize = image.srcSize,
                                    dstOffset = IntOffset(dstLeft, dstTop),
                                    dstSize = IntSize(dstWidth, dstHeight),
                                    alpha = p,
                                    blendMode = BlendMode.Plus,
                                )
                            }
                        }
                    }
                }
            }
            drawIntoCanvas { it.restore() }

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
