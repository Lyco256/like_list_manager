package com.lyco256.llm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import coil.memory.MemoryCache
import kotlin.math.roundToInt
import java.util.LinkedHashMap

internal enum class MediaGridResidentCanvasMode {
    Disabled,
    TestVisible,
}

internal data class MediaGridVisibleCanvasItem(
    val assetId: Long,
    val itemKey: String,
    val destination: Rect,
    val visibleOrder: Int,
    val intersectsViewport: Boolean,
)

internal data class MediaGridVisibleCanvasSnapshot(
    val frameKey: MediaGridRenderKey,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val layoutGeneration: Long,
    val items: List<MediaGridVisibleCanvasItem>,
)

internal data class MediaGridResidentCanvasImage(
    val identity: MediaGridResidentImageIdentity,
    val imageBitmap: ImageBitmap,
    val width: Int,
    val height: Int,
)

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
): Rect? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0 || destinationHeight <= 0) return null
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val destinationAspect = destinationWidth.toDouble() / destinationHeight
    return if (sourceAspect > destinationAspect) {
        val cropWidth = (sourceHeight * destinationAspect).coerceIn(1.0, sourceWidth.toDouble())
        val left = (sourceWidth - cropWidth) / 2.0
        Rect(left.toFloat(), 0f, (left + cropWidth).toFloat(), sourceHeight.toFloat())
    } else {
        val cropHeight = (sourceWidth / destinationAspect).coerceIn(1.0, sourceHeight.toDouble())
        val top = (sourceHeight - cropHeight) / 2.0
        Rect(0f, top.toFloat(), sourceWidth.toFloat(), (top + cropHeight).toFloat())
    }
}

internal data class MediaGridResidentCanvasDrawCommand(
    val assetId: Long,
    val identity: MediaGridResidentImageIdentity,
    val image: MediaGridResidentCanvasImage,
    val sourceCrop: Rect,
    val destination: Rect,
    val visibleOrder: Int,
)

internal fun buildMediaGridVisibleCanvasSnapshot(
    frame: MediaGridFrameData,
    layout: LazyGridLayoutInfo,
): MediaGridVisibleCanvasSnapshot {
    val viewportWidth = layout.viewportSize.width.coerceAtLeast(0)
    val viewportHeight = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
    val items = ArrayList<MediaGridVisibleCanvasItem>(layout.visibleItemsInfo.size)
    val seenAssets = HashSet<Long>()
    layout.visibleItemsInfo.forEachIndexed { order, info ->
        val item = (info.key as? String)?.let(frame.itemByKey::get) as? MediaGridCellItem ?: return@forEachIndexed
        if (!seenAssets.add(item.entry.assetId)) return@forEachIndexed
        val destination = mediaGridCanvasDestinationRect(info.offset, info.size, layout.viewportStartOffset)
        val viewport = Rect(0f, 0f, viewportWidth.toFloat(), viewportHeight.toFloat())
        if (destination.intersect(viewport).isEmpty) return@forEachIndexed
        items += MediaGridVisibleCanvasItem(item.entry.assetId, item.key, destination, order, true)
    }
    val generation = items.fold(17L) { acc, item ->
        acc * 31 + item.itemKey.hashCode() * 31L + item.destination.hashCode()
    }
    return MediaGridVisibleCanvasSnapshot(frame.key, viewportWidth, viewportHeight, generation, items.toList())
}

internal fun mediaGridCanvasDestinationRect(
    offset: IntOffset,
    size: IntSize,
    viewportStartOffset: Int,
): Rect = Rect(
    offset.x.toFloat(),
    (offset.y - viewportStartOffset).toFloat(),
    (offset.x + size.width).toFloat(),
    (offset.y - viewportStartOffset + size.height).toFloat(),
)

internal fun buildMediaGridResidentCanvasCommands(
    snapshot: MediaGridVisibleCanvasSnapshot,
    index: MediaGridResidentDrawIndex,
    adapter: MediaGridResidentCanvasImageAdapter,
): List<MediaGridResidentCanvasDrawCommand> {
    adapter.sync(index)
    return snapshot.items.mapNotNull { item ->
        val identity = index.identityByAssetId[item.assetId] ?: return@mapNotNull null
        val handle = index.handlesByIdentity[identity] ?: return@mapNotNull null
        val image = adapter.resolve(handle) ?: return@mapNotNull null
        val source = mediaGridCropSourceRect(image.width, image.height, item.destination.width.roundToInt(), item.destination.height.roundToInt())
            ?: return@mapNotNull null
        MediaGridResidentCanvasDrawCommand(item.assetId, identity, image, source, item.destination, item.visibleOrder)
    }.sortedBy { it.visibleOrder }
}

@Composable
internal fun MediaGridResidentCanvasLayer(
    frame: MediaGridFrameData,
    state: LazyGridState,
    retainedImageStore: MediaGridRetainedImageStore,
    mode: MediaGridResidentCanvasMode = MediaGridResidentCanvasMode.Disabled,
) {
    if (mode != MediaGridResidentCanvasMode.TestVisible) return
    val version by retainedImageStore.drawIndexVersionFlow.collectAsState()
    var geometry by remember(frame.key) { mutableStateOf<MediaGridVisibleCanvasSnapshot?>(null) }
    val adapter = remember { MediaGridResidentCanvasImageAdapter() }
    LaunchedEffect(state, frame.key) {
        snapshotFlow { buildMediaGridVisibleCanvasSnapshot(frame, state.layoutInfo) }
            .collect { next -> if (next != geometry) geometry = next }
    }
    val snapshot = geometry ?: return
    val index = retainedImageStore.drawIndexSnapshot().takeIf { it.version == version } ?: return
    val commands = remember(snapshot, version) {
        buildMediaGridResidentCanvasCommands(snapshot, index, adapter)
    }
    Canvas(Modifier.fillMaxSize().testTag("media_grid_resident_canvas")) {
        drawContext.canvas.save()
        drawContext.canvas.clipRect(0f, 0f, size.width, size.height)
        try {
            commands.forEach { command ->
                drawImage(
                    image = command.image.imageBitmap,
                    srcOffset = IntOffset(command.sourceCrop.left.roundToInt(), command.sourceCrop.top.roundToInt()),
                    srcSize = IntSize(command.sourceCrop.width.roundToInt(), command.sourceCrop.height.roundToInt()),
                    dstOffset = IntOffset(command.destination.left.roundToInt(), command.destination.top.roundToInt()),
                    dstSize = IntSize(command.destination.width.roundToInt(), command.destination.height.roundToInt()),
                )
            }
        } finally {
            drawContext.canvas.restore()
        }
    }
}
