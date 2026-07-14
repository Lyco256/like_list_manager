package com.lyco256.llm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lyco256.llm.data.MediaGridThumbnailManager
import com.lyco256.llm.data.MediaGridThumbnailState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

private fun mediaGridThumbnailSource(entry: MediaGridEntry) =
    com.lyco256.llm.data.MediaGridThumbnailSource(
        assetId = entry.assetId,
        mediaKey = entry.mediaKey,
        localPath = entry.localPath,
        previewUrl = entry.previewUrl ?: entry.remoteUrl ?: entry.displayUrl?.takeUnless { it == entry.localPath },
        remoteUrl = entry.remoteUrl,
        downloadState = entry.downloadState,
    )

internal data class MediaGridMorphAssetVisual(
    val entry: MediaGridEntry,
    val thumbnailSource: com.lyco256.llm.data.MediaGridThumbnailSource,
    val selected: Boolean,
    val multiAsset: Boolean,
    val showLikeCount: Boolean,
)

/** Immutable session render data. It is created once per plan and released with the Idle phase. */
internal data class MediaGridMorphRenderModel(
    val slots: List<MediaGridMorphSlot>,
    val headers: List<MediaGridMorphHeaderBand>,
    val assetByKey: Map<String, MediaGridMorphAssetVisual>,
    val viewport: Rect,
) {
    companion object {
        fun create(
            plan: MediaGridMorphPlan,
            items: List<ClassifiedMediaGridItem>,
            sort: ClassifiedSortState,
            selectionMode: Boolean,
            multiAssetClipIds: Set<Long>,
            selectedClipIds: Set<Long>,
        ): MediaGridMorphRenderModel {
            val indexes = plan.slots.asSequence()
                .flatMap { sequenceOf(it.startItemIndex, it.endItemIndex) }
                .filterNotNull()
                .toSet()
            val entries = indexes.asSequence()
                .mapNotNull { items.getOrNull(it) as? MediaGridCellItem }
                .map { it.entry }
                .distinctBy { it.assetId }
                .associateBy { "asset:${it.assetId}" }
            val visuals = entries.mapValues { (_, entry) ->
                MediaGridMorphAssetVisual(
                    entry = entry,
                    thumbnailSource = mediaGridThumbnailSource(entry),
                    selected = entry.clipId in selectedClipIds,
                    multiAsset = entry.clipId in multiAssetClipIds,
                    showLikeCount = !selectionMode && sort.baseOrder == ClassifiedSortBase.LikeCount && entry.likeCount != null,
                )
            }
            return MediaGridMorphRenderModel(plan.slots, plan.headers, visuals, plan.viewport)
        }
    }
}

internal fun mediaGridMorphRect(slot: MediaGridMorphSlot, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        left = lerp(slot.startRect.left, slot.endRect.left, p),
        top = lerp(slot.startRect.top, slot.endRect.top, p),
        right = lerp(slot.startRect.right, slot.endRect.right, p),
        bottom = lerp(slot.startRect.bottom, slot.endRect.bottom, p),
    )
}

internal fun mediaGridMorphStartAlpha(slot: MediaGridMorphSlot, progress: Float): Float {
    if (!slot.hasStart) return 0f
    if (slot.startAssetKey == slot.endAssetKey && slot.hasEnd) return 1f
    return (1f - progress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

internal fun mediaGridMorphEndAlpha(slot: MediaGridMorphSlot, progress: Float): Float {
    if (!slot.hasEnd) return 0f
    if (slot.startAssetKey == slot.endAssetKey && slot.hasStart) return 0f
    return progress.coerceIn(0f, 1f)
}

internal fun mediaGridMorphLayerCount(slot: MediaGridMorphSlot): Int = when {
    slot.startAssetKey != null && slot.startAssetKey == slot.endAssetKey -> 1
    slot.hasStart && slot.hasEnd -> 2
    slot.hasStart || slot.hasEnd -> 1
    else -> 0
}

private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

@Composable
internal fun MediaGridMorphOverlay(
    model: MediaGridMorphRenderModel,
    progress: Float,
    thumbnailManager: MediaGridThumbnailManager,
    thumbnailImageLoader: coil.ImageLoader,
    thumbnailBrush: Brush,
    selectionMode: Boolean,
    correctionX: Float = 0f,
    correctionY: Float = 0f,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("media_grid_morph_overlay"),
    ) {
        model.headers.forEach { header ->
            MorphHeaderBand(header, progress, model.viewport.width, correctionX, correctionY, thumbnailBrush)
        }
        model.slots.forEachIndexed { index, slot ->
            val rect = mediaGridMorphRect(slot, progress)
            val maxWidth = max(slot.startRect.width, slot.endRect.width)
            if (maxWidth <= 0f || rect.width <= 0.01f || rect.height <= 0.01f) return@forEachIndexed
            val scale = (rect.width / maxWidth).coerceIn(0f, 1f)
            val maxSize = with(density) { maxWidth.toDp() }
            val start = slot.startAssetKey?.let(model.assetByKey::get)
            val end = slot.endAssetKey?.let(model.assetByKey::get)
            val same = start != null && start.entry.assetId == end?.entry?.assetId
            val startAlpha = mediaGridMorphStartAlpha(slot, progress)
            val endAlpha = mediaGridMorphEndAlpha(slot, progress)
            Box(
                modifier = Modifier
                    .size(maxSize)
                    .graphicsLayer {
                        translationX = rect.left + correctionX
                        translationY = rect.top + correctionY
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                        clip = true
                    }
                    .testTag("media_grid_morph_slot_$index"),
            ) {
                Box(Modifier.fillMaxSize().background(thumbnailBrush))
                if (same) {
                    MorphAssetLayer(
                        visual = start ?: end,
                        alpha = 1f,
                        thumbnailManager = thumbnailManager,
                        thumbnailImageLoader = thumbnailImageLoader,
                        selectionMode = selectionMode,
                        thumbnailBrush = thumbnailBrush,
                    )
                } else {
                    if (start != null) MorphAssetLayer(start, startAlpha, thumbnailManager, thumbnailImageLoader, selectionMode, thumbnailBrush)
                    if (end != null) MorphAssetLayer(end, endAlpha, thumbnailManager, thumbnailImageLoader, selectionMode, thumbnailBrush)
                }
            }
        }
    }
}

@Composable
private fun MorphHeaderBand(
    header: MediaGridMorphHeaderBand,
    progress: Float,
    width: Float,
    correctionX: Float,
    correctionY: Float,
    brush: Brush,
) {
    val density = LocalDensity.current
    val top = lerp(header.startY, header.endY, progress)
    val height = lerp(header.startHeight, header.endHeight, progress).coerceAtLeast(0f)
    if (height <= 0f) return
    Box(
        modifier = Modifier
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .graphicsLayer { translationX = correctionX; translationY = top + correctionY }
            .background(MaterialTheme.colorScheme.surface)
            .testTag("media_grid_morph_header_${header.key}"),
    ) {
        val title = if (progress < 0.5f) header.startTitle ?: header.endTitle else header.endTitle ?: header.startTitle
        if (!title.isNullOrBlank()) Text(title, Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun MorphAssetLayer(
    visual: MediaGridMorphAssetVisual?,
    alpha: Float,
    thumbnailManager: MediaGridThumbnailManager,
    thumbnailImageLoader: coil.ImageLoader,
    selectionMode: Boolean,
    thumbnailBrush: Brush,
) {
    if (visual == null || alpha <= 0f) return
    val stateFlow: StateFlow<MediaGridThumbnailState>? = remember(visual.entry.assetId, thumbnailManager) {
        thumbnailManager.stateIfPresent(visual.entry.assetId)
    }
    val thumbnailState = stateFlow?.collectAsState()?.value
    val readyFile = (thumbnailState as? MediaGridThumbnailState.Ready)?.file
    val error = thumbnailState is MediaGridThumbnailState.Failed || visual.entry.downloadState == "failed" ||
        (visual.entry.localPath == null && visual.entry.previewUrl == null && visual.entry.remoteUrl == null)
    Box(Modifier.fillMaxSize().alpha(alpha).testTag("media_grid_morph_asset_${visual.entry.assetId}")) {
        if (!error && readyFile != null) {
            AsyncImage(
                model = readyFile,
                contentDescription = null,
                imageLoader = thumbnailImageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (error) {
            Box(Modifier.fillMaxSize().background(thumbnailBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = "エラー", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        }
        MorphAssetBadges(visual, selectionMode)
    }
}

@Composable
private fun BoxScope.MorphAssetBadges(visual: MediaGridMorphAssetVisual, selectionMode: Boolean) {
    val columnCount = 4
    val videoIconSize = morphVideoIconSize(columnCount)
    val overlayPadding = 4.dp
    if (visual.showLikeCount && visual.entry.likeCount != null) {
        Surface(Modifier.align(Alignment.TopStart).padding(overlayPadding), color = Color.Black.copy(alpha = 0.68f)) {
            Text(formatLikeCount(visual.entry.likeCount), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (visual.entry.type == "video_thumbnail") {
        Icon(Icons.Filled.PlayArrow, contentDescription = "再生", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(overlayPadding).size(videoIconSize))
    }
    if (selectionMode) {
        Surface(Modifier.align(Alignment.TopStart).padding(overlayPadding).size(morphSelectionIndicatorSize(columnCount)), color = Color.Transparent) {
            if (visual.selected) Icon(Icons.Filled.Check, contentDescription = "選択済み", tint = if (visual.multiAsset) Color.White else Color.Black, modifier = Modifier.fillMaxSize().background(mediaGridSelectionBackground(MaterialTheme.colorScheme.primary, visual.multiAsset), androidx.compose.foundation.shape.CircleShape).padding(4.dp))
        }
        if (columnCount <= 6) Icon(Icons.Filled.ViewList, contentDescription = "カードを表示", tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(overlayPadding).size(morphCardDialogVisualSize(columnCount)))
    }
}

private fun morphSelectionIndicatorSize(columnCount: Int) = when (columnCount.coerceIn(2, 12)) {
    2, 3 -> 28.dp
    4, 5, 6 -> 24.dp
    7, 8, 9 -> 18.dp
    else -> 14.dp
}

private fun morphVideoIconSize(columnCount: Int) = when (columnCount.coerceIn(2, 12)) {
    2, 3 -> 24.dp
    4, 5, 6 -> 20.dp
    7, 8, 9 -> 14.dp
    else -> 10.dp
}

private fun morphCardDialogVisualSize(columnCount: Int) = when (columnCount.coerceIn(2, 12)) {
    2, 3 -> 20.dp
    else -> 18.dp
}
