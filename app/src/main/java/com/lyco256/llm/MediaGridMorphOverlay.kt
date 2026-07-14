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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lyco256.llm.data.MediaGridThumbnailManager
import com.lyco256.llm.data.MediaGridThumbnailState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

@Stable
internal class MediaGridMorphOverlayMotion {
    val progress = mutableFloatStateOf(0f)
    val correction = mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
}

@Stable
internal class MediaGridMorphOverlayResources(
    val thumbnailManager: MediaGridThumbnailManager,
    val thumbnailImageLoader: coil.ImageLoader,
    val thumbnailBrush: Brush,
)

private fun mediaGridThumbnailSource(entry: MediaGridEntry) =
    com.lyco256.llm.data.MediaGridThumbnailSource(
        assetId = entry.assetId,
        mediaKey = entry.mediaKey,
        localPath = entry.localPath,
        previewUrl = entry.previewUrl ?: entry.remoteUrl ?: entry.displayUrl?.takeUnless { it == entry.localPath },
        remoteUrl = entry.remoteUrl,
        downloadState = entry.downloadState,
    )

@Immutable
internal data class MediaGridMorphAssetVisual(
    val entry: MediaGridEntry,
    val thumbnailSource: com.lyco256.llm.data.MediaGridThumbnailSource,
    val selected: Boolean,
    val multiAsset: Boolean,
    val showLikeCount: Boolean,
)

/** Immutable session render data. It is created once per plan and released with the Idle phase. */
@Immutable
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

internal fun mediaGridMorphHeaderY(header: MediaGridMorphHeaderBand, progress: Float): Float =
    lerp(header.startY, header.endY, progress.coerceIn(0f, 1f))

internal fun mediaGridMorphHeaderHeight(header: MediaGridMorphHeaderBand, progress: Float): Float =
    lerp(header.startHeight, header.endHeight, progress.coerceIn(0f, 1f)).coerceAtLeast(0f)

internal fun mediaGridMorphStartTitleAlpha(header: MediaGridMorphHeaderBand, progress: Float): Float = when {
    header.startTitle.isNullOrBlank() -> 0f
    header.startTitle == header.endTitle -> 1f
    else -> (1f - progress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

internal fun mediaGridMorphEndTitleAlpha(header: MediaGridMorphHeaderBand, progress: Float): Float = when {
    header.endTitle.isNullOrBlank() -> 0f
    header.startTitle == header.endTitle -> 0f
    else -> progress.coerceIn(0f, 1f)
}

internal fun mediaGridMorphTitleLayerCount(header: MediaGridMorphHeaderBand): Int = when {
    header.startTitle.isNullOrBlank() && header.endTitle.isNullOrBlank() -> 0
    header.startTitle == header.endTitle -> 1
    else -> listOfNotNull(header.startTitle, header.endTitle).count { it.isNotBlank() }
}

private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

@Composable
internal fun MediaGridMorphOverlay(
    model: MediaGridMorphRenderModel,
    motion: MediaGridMorphOverlayMotion,
    resources: MediaGridMorphOverlayResources,
    selectionMode: Boolean,
) {
    val thumbnailStates = rememberMorphThumbnailStates(model, resources.thumbnailManager)
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("media_grid_morph_overlay"),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("media_grid_morph_surface"),
            color = MaterialTheme.colorScheme.surface,
        ) {}
        model.headers.forEach { header ->
            key("morph_header_${header.key}") {
                MorphHeaderBand(header, model.viewport.width, motion)
            }
        }
        model.slots.forEachIndexed { index, slot ->
            key("morph_slot_${slot.startItemIndex ?: -1}_${slot.endItemIndex ?: -1}_$index") {
                MorphSlot(
                    index = index,
                    slot = slot,
                    model = model,
                    thumbnailStates = thumbnailStates,
                    motion = motion,
                    resources = resources,
                    selectionMode = selectionMode,
                    density = density,
                )
            }
        }
    }
}

@Composable
private fun rememberMorphThumbnailStates(
    model: MediaGridMorphRenderModel,
    thumbnailManager: MediaGridThumbnailManager,
): Map<Long, State<MediaGridThumbnailState>?> {
    val visuals = remember(model.assetByKey, thumbnailManager) {
        model.assetByKey.values.distinctBy { it.entry.assetId }
    }
    val result = LinkedHashMap<Long, State<MediaGridThumbnailState>?>(visuals.size)
    visuals.forEach { visual ->
        key("morph_thumbnail_${visual.entry.assetId}") {
            val stateFlow: StateFlow<MediaGridThumbnailState>? = remember(visual.entry.assetId, thumbnailManager) {
                thumbnailManager.stateIfPresent(visual.entry.assetId)
            }
            result[visual.entry.assetId] = stateFlow?.collectAsState()
        }
    }
    return result
}

@Composable
private fun MorphSlot(
    index: Int,
    slot: MediaGridMorphSlot,
    model: MediaGridMorphRenderModel,
    thumbnailStates: Map<Long, State<MediaGridThumbnailState>?>,
    motion: MediaGridMorphOverlayMotion,
    resources: MediaGridMorphOverlayResources,
    selectionMode: Boolean,
    density: androidx.compose.ui.unit.Density,
) {
    val maxWidth = max(slot.startRect.width, slot.endRect.width).coerceAtLeast(0.01f)
    val maxHeight = max(slot.startRect.height, slot.endRect.height).coerceAtLeast(0.01f)
    val maxWidthDp = with(density) { maxWidth.toDp() }
    val start = slot.startAssetKey?.let(model.assetByKey::get)
    val end = slot.endAssetKey?.let(model.assetByKey::get)
    val same = start != null && start.entry.assetId == end?.entry?.assetId
    Box(
        modifier = Modifier
            .size(with(density) { maxWidth.toDp() }, with(density) { maxHeight.toDp() })
            .graphicsLayer {
                val rect = mediaGridMorphRect(slot, motion.progress.floatValue)
                translationX = rect.left + motion.correction.value.x
                translationY = rect.top + motion.correction.value.y
                scaleX = (rect.width / maxWidth).coerceIn(0f, 1f)
                scaleY = (rect.height / maxHeight).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 0f)
                clip = true
            }
            .testTag("media_grid_morph_slot_$index"),
    ) {
        Box(Modifier.fillMaxSize().background(resources.thumbnailBrush))
        if (same) {
            MorphAssetLayer(
                visual = start ?: end,
                side = MorphAssetSide.Single,
                slot = slot,
                thumbnailState = start?.let { thumbnailStates[it.entry.assetId] },
                maxSlotWidth = maxWidthDp,
                motion = motion,
                thumbnailImageLoader = resources.thumbnailImageLoader,
                selectionMode = selectionMode,
                thumbnailBrush = resources.thumbnailBrush,
            )
        } else {
            if (start != null) MorphAssetLayer(
                visual = start,
                side = MorphAssetSide.Start,
                slot = slot,
                thumbnailState = thumbnailStates[start.entry.assetId],
                maxSlotWidth = maxWidthDp,
                motion = motion,
                thumbnailImageLoader = resources.thumbnailImageLoader,
                selectionMode = selectionMode,
                thumbnailBrush = resources.thumbnailBrush,
            )
            if (end != null) MorphAssetLayer(
                visual = end,
                side = MorphAssetSide.End,
                slot = slot,
                thumbnailState = thumbnailStates[end.entry.assetId],
                maxSlotWidth = maxWidthDp,
                motion = motion,
                thumbnailImageLoader = resources.thumbnailImageLoader,
                selectionMode = selectionMode,
                thumbnailBrush = resources.thumbnailBrush,
            )
        }
    }
}

private enum class MorphAssetSide { Single, Start, End }

@Composable
private fun MorphHeaderBand(
    header: MediaGridMorphHeaderBand,
    width: Float,
    motion: MediaGridMorphOverlayMotion,
) {
    val density = LocalDensity.current
    val maxHeight = max(header.startHeight, header.endHeight).coerceAtLeast(0.01f)
    Box(
        modifier = Modifier
            .size(with(density) { width.toDp() }, with(density) { maxHeight.toDp() })
            .graphicsLayer {
                val progress = motion.progress.floatValue
                translationY = mediaGridMorphHeaderY(header, progress) + motion.correction.value.y
                scaleY = (mediaGridMorphHeaderHeight(header, progress) / maxHeight).coerceIn(0f, 1f)
                translationX = motion.correction.value.x
                transformOrigin = TransformOrigin(0f, 0f)
                clip = true
            }
            .background(MaterialTheme.colorScheme.surface)
            .testTag("media_grid_morph_header_${header.key}"),
    ) {
        val startTitle = header.startTitle
        val endTitle = header.endTitle
        if (startTitle != null && startTitle == endTitle && startTitle.isNotBlank()) {
            MorphHeaderTitle(startTitle, header, MorphAssetSide.Single, motion)
        } else {
            if (!startTitle.isNullOrBlank()) MorphHeaderTitle(startTitle, header, MorphAssetSide.Start, motion)
            if (!endTitle.isNullOrBlank()) MorphHeaderTitle(endTitle, header, MorphAssetSide.End, motion)
        }
    }
}

@Composable
private fun MorphHeaderTitle(
    title: String,
    header: MediaGridMorphHeaderBand,
    side: MorphAssetSide,
    motion: MediaGridMorphOverlayMotion,
) {
    Text(
        title,
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .graphicsLayer {
                alpha = when (side) {
                    MorphAssetSide.Single -> 1f
                    MorphAssetSide.Start -> mediaGridMorphStartTitleAlpha(header, motion.progress.floatValue)
                    MorphAssetSide.End -> mediaGridMorphEndTitleAlpha(header, motion.progress.floatValue)
                }
            }
            .testTag(
                when (side) {
                    MorphAssetSide.Single -> "media_grid_morph_title_${header.key}"
                    MorphAssetSide.Start -> "media_grid_morph_start_title_${header.key}"
                    MorphAssetSide.End -> "media_grid_morph_end_title_${header.key}"
                },
            ),
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun MorphAssetLayer(
    visual: MediaGridMorphAssetVisual,
    side: MorphAssetSide,
    slot: MediaGridMorphSlot,
    thumbnailState: State<MediaGridThumbnailState>?,
    maxSlotWidth: Dp,
    motion: MediaGridMorphOverlayMotion,
    thumbnailImageLoader: coil.ImageLoader,
    selectionMode: Boolean,
    thumbnailBrush: Brush,
) {
    val state = thumbnailState?.value
    val readyFile = (state as? MediaGridThumbnailState.Ready)?.file
    val error = state is MediaGridThumbnailState.Failed || visual.entry.downloadState == "failed" ||
        (visual.entry.localPath == null && visual.entry.previewUrl == null && visual.entry.remoteUrl == null)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = when (side) {
                    MorphAssetSide.Single -> 1f
                    MorphAssetSide.Start -> mediaGridMorphStartAlpha(slot, motion.progress.floatValue)
                    MorphAssetSide.End -> mediaGridMorphEndAlpha(slot, motion.progress.floatValue)
                }
            }
            .testTag("media_grid_morph_asset_${visual.entry.assetId}"),
    ) {
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
        MorphAssetBadges(visual, selectionMode, maxSlotWidth)
    }
}

private data class MorphBadgeMetrics(
    val padding: Dp,
    val selection: Dp,
    val video: Dp,
    val card: Dp,
    val cardVisual: Dp,
)

private fun morphBadgeMetrics(slotWidth: Dp): MorphBadgeMetrics {
    val width = slotWidth.value.coerceAtLeast(1f)
    fun scaled(fraction: Float, min: Float, max: Float) = (width * fraction).coerceIn(min, max).dp
    return MorphBadgeMetrics(
        padding = scaled(0.04f, 2f, 4f),
        selection = scaled(0.24f, 14f, 28f),
        video = scaled(0.2f, 10f, 24f),
        card = scaled(0.24f, 24f, 28f),
        cardVisual = scaled(0.18f, 12f, 20f),
    )
}

@Composable
private fun BoxScope.MorphAssetBadges(visual: MediaGridMorphAssetVisual, selectionMode: Boolean, slotWidth: Dp) {
    val metrics = morphBadgeMetrics(slotWidth)
    if (visual.showLikeCount && visual.entry.likeCount != null) {
        Surface(Modifier.align(Alignment.TopStart).padding(metrics.padding), color = Color.Black.copy(alpha = 0.68f)) {
            Text(formatLikeCount(visual.entry.likeCount), Modifier.padding(horizontal = metrics.padding * 1.5f, vertical = metrics.padding * 0.5f), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (visual.entry.type == "video_thumbnail") {
        Icon(Icons.Filled.PlayArrow, contentDescription = "再生", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(metrics.padding).size(metrics.video))
    }
    if (selectionMode) {
        Surface(Modifier.align(Alignment.TopStart).padding(metrics.padding).size(metrics.selection), color = Color.Transparent) {
            if (visual.selected) Icon(Icons.Filled.Check, contentDescription = "選択済み", tint = if (visual.multiAsset) Color.White else Color.Black, modifier = Modifier.fillMaxSize().background(mediaGridSelectionBackground(MaterialTheme.colorScheme.primary, visual.multiAsset), androidx.compose.foundation.shape.CircleShape).padding(metrics.padding))
        }
        Icon(Icons.Filled.ViewList, contentDescription = "カードを表示", tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(metrics.padding).size(metrics.cardVisual))
    }
}
