package com.lyco256.llm

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.memory.MemoryCache
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.roundToInt

internal enum class MediaGridResidentCanvasMode {
    Disabled,
    Enabled,
    TestVisible,
}

/** One exclusive draw surface for Normal, Morph, and the two reveal frames. */
internal enum class MediaGridSingleSurfaceMode {
    Normal,
    Morph,
    RevealCurrent,
    RevealTarget,
}

internal data class MediaGridResidentCanvasImage(
    val identity: MediaGridResidentImageIdentity,
    val imageBitmap: ImageBitmap,
    val width: Int,
    val height: Int,
)

internal data class MediaGridResidentCanvasPreparedImage(
    val assetId: Long,
    val identity: MediaGridResidentImageIdentity,
    val image: ImageBitmap,
    val srcOffset: IntOffset,
    val srcSize: IntSize,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

internal data class MediaGridResidentCanvasPreparedIndex(
    val drawIndexVersion: Long,
    val preparedImageByAssetId: Map<Long, MediaGridResidentCanvasPreparedImage>,
) {
    val entryCount: Int get() = preparedImageByAssetId.size
}

private data class CachedResidentImage(
    val value: MemoryCache.Value,
    val image: MediaGridResidentCanvasImage,
)

/** Identity/value keyed adapter cache. It never owns or recycles the source Bitmap. */
internal class MediaGridResidentCanvasImageAdapter {
    private val entries = LinkedHashMap<MediaGridResidentImageIdentity, CachedResidentImage>()

    @Synchronized
    fun sync(index: MediaGridResidentDrawIndex) {
        entries.keys.retainAll(index.handlesByIdentity.keys)
    }

    @Synchronized
    fun resolve(handle: MediaGridResidentDrawHandle): MediaGridResidentCanvasImage? {
        val cached = entries[handle.identity]
        if (cached != null && cached.value === handle.value) return cached.image
        val bitmap = handle.value.bitmap
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            entries.remove(handle.identity)
            return null
        }
        val image = runCatching { bitmap.asImageBitmap() }.getOrNull() ?: run {
            entries.remove(handle.identity)
            return null
        }
        return MediaGridResidentCanvasImage(handle.identity, image, bitmap.width, bitmap.height).also {
            entries[handle.identity] = CachedResidentImage(handle.value, it)
        }
    }

    @Synchronized
    fun size(): Int = entries.size
}

internal fun mediaGridCropSourceRect(
    sourceWidth: Int,
    sourceHeight: Int,
    destinationWidth: Int,
    destinationHeight: Int,
): androidx.compose.ui.geometry.Rect? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0 || destinationHeight <= 0) return null
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val destinationAspect = destinationWidth.toDouble() / destinationHeight
    return if (sourceAspect > destinationAspect) {
        val cropWidth = (sourceHeight * destinationAspect).coerceIn(1.0, sourceWidth.toDouble())
        val left = (sourceWidth - cropWidth) / 2.0
        androidx.compose.ui.geometry.Rect(left.toFloat(), 0f, (left + cropWidth).toFloat(), sourceHeight.toFloat())
    } else {
        val cropHeight = (sourceWidth / destinationAspect).coerceIn(1.0, sourceHeight.toDouble())
        val top = (sourceHeight - cropHeight) / 2.0
        androidx.compose.ui.geometry.Rect(0f, top.toFloat(), sourceWidth.toFloat(), (top + cropHeight).toFloat())
    }
}

internal fun mediaGridResidentCanvasViewportRect(width: Int, height: Int): androidx.compose.ui.geometry.Rect =
    androidx.compose.ui.geometry.Rect(0f, 0f, width.coerceAtLeast(0).toFloat(), height.coerceAtLeast(0).toFloat())

internal fun mediaGridCanvasDestinationRect(
    offset: IntOffset,
    size: IntSize,
    viewportStartOffset: Int,
): androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect(
    offset.x.toFloat(),
    (offset.y - viewportStartOffset).toFloat(),
    (offset.x + size.width).toFloat(),
    (offset.y - viewportStartOffset + size.height).toFloat(),
)

internal fun buildMediaGridResidentCanvasPreparedIndex(
    drawIndex: MediaGridResidentDrawIndex,
    adapter: MediaGridResidentCanvasImageAdapter,
): MediaGridResidentCanvasPreparedIndex {
    adapter.sync(drawIndex)
    val prepared = LinkedHashMap<Long, MediaGridResidentCanvasPreparedImage>(drawIndex.handlesByIdentity.size)
    drawIndex.handlesByIdentity.values.forEach { handle ->
        if (prepared.size >= MEDIA_GRID_RETAINED_IMAGE_TARGET_ENTRIES || !handle.directDrawEligible) return@forEach
        val image = adapter.resolve(handle) ?: return@forEach
        val crop = mediaGridCropSourceRect(image.width, image.height, 1, 1) ?: return@forEach
        val srcOffset = IntOffset(crop.left.roundToInt(), crop.top.roundToInt())
        val srcSize = IntSize(crop.width.roundToInt(), crop.height.roundToInt())
        if (srcOffset.x < 0 || srcOffset.y < 0 || srcOffset.x + srcSize.width > image.width || srcOffset.y + srcSize.height > image.height) return@forEach
        prepared[handle.identity.assetId] = MediaGridResidentCanvasPreparedImage(
            assetId = handle.identity.assetId,
            identity = handle.identity,
            image = image.imageBitmap,
            srcOffset = srcOffset,
            srcSize = srcSize,
            sourceWidth = image.width,
            sourceHeight = image.height,
        )
    }
    return MediaGridResidentCanvasPreparedIndex(
        drawIndexVersion = drawIndex.version,
        preparedImageByAssetId = Collections.unmodifiableMap(prepared),
    )
}

/** The draw modifier only applies current LazyGrid coordinates to an immutable prepared index. */
internal fun Modifier.mediaGridResidentCanvas(
    state: LazyGridState,
    assetIdByItemKey: Map<String, Long>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    mode: MediaGridResidentCanvasMode = MediaGridResidentCanvasMode.Disabled,
): Modifier {
    if (mode == MediaGridResidentCanvasMode.Disabled) return this
    val tagged = if (mode == MediaGridResidentCanvasMode.TestVisible) testTag("media_grid_resident_canvas") else this
    return tagged.drawWithCache {
        onDrawWithContent {
            val layout = state.layoutInfo
            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                layout.visibleItemsInfo.forEach { info ->
                    val assetId = (info.key as? String)?.let(assetIdByItemKey::get) ?: return@forEach
                    val image = preparedIndex.preparedImageByAssetId[assetId] ?: return@forEach
                    val left = info.offset.x
                    val top = info.offset.y - layout.viewportStartOffset
                    val width = info.size.width
                    val height = info.size.height
                    val right = left + width
                    val bottom = top + height
                    if (right <= 0 || bottom <= 0 || left >= size.width || top >= size.height || width <= 0 || height <= 0) return@forEach
                    drawImage(
                        image = image.image,
                        srcOffset = image.srcOffset,
                        srcSize = image.srcSize,
                        dstOffset = IntOffset(left, top),
                        dstSize = IntSize(width, height),
                    )
                }
            }
            drawContent()
        }
    }
}

private data class MediaGridResidentDrawCommand(
    val image: MediaGridResidentCanvasPreparedImage,
    val offset: IntOffset,
    val size: IntSize,
)

/**
 * Unified production surface. Resident commands are prepared in the
 * drawWithCache phase; the draw phase only iterates immutable commands or the
 * frozen Morph model. There is no overlay, second grid, zIndex, or translation.
 */
internal fun Modifier.mediaGridSingleSurface(
    state: LazyGridState,
    assetIdByItemKey: Map<String, Long>,
    preparedIndex: MediaGridResidentCanvasPreparedIndex,
    mode: State<MediaGridSingleSurfaceMode>,
    morphModel: MediaGridMorphRowRenderModel?,
    progress: State<Float>,
): Modifier = drawWithCache {
    val layout = state.layoutInfo
    val commands = ArrayList<MediaGridResidentDrawCommand>(layout.visibleItemsInfo.size)
    for (info in layout.visibleItemsInfo) {
        val key = info.key as? String ?: continue
        val assetId = assetIdByItemKey[key] ?: continue
        val image = preparedIndex.preparedImageByAssetId[assetId] ?: continue
        if (info.size.width <= 0 || info.size.height <= 0) continue
        val left = info.offset.x
        val top = info.offset.y - layout.viewportStartOffset
        if (left + info.size.width <= 0 || top + info.size.height <= 0 || left >= size.width || top >= size.height) continue
        commands += MediaGridResidentDrawCommand(
            image = image,
            offset = IntOffset(left, top),
            size = IntSize(info.size.width, info.size.height),
        )
    }
    onDrawWithContent {
        when (mode.value) {
            MediaGridSingleSurfaceMode.Morph -> {
                val model = morphModel
                if (model == null) {
                    drawContent()
                } else {
                    drawRect(model.surfaceColor)
                    drawMediaGridMorphRow(model, progress.value)
                }
            }
            MediaGridSingleSurfaceMode.Normal,
            MediaGridSingleSurfaceMode.RevealCurrent,
            MediaGridSingleSurfaceMode.RevealTarget,
            -> {
                var index = 0
                while (index < commands.size) {
                    val command = commands[index]
                    drawImage(
                        image = command.image.image,
                        srcOffset = command.image.srcOffset,
                        srcSize = command.image.srcSize,
                        dstOffset = command.offset,
                        dstSize = command.size,
                    )
                    index++
                }
                drawContent()
            }
        }
    }
}
