package com.lyco256.llm.data

import android.content.Context
import android.graphics.BitmapFactory
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class MediaGridImageSourceKind {
    Rgb565Pack,
    PersistentPreview,
    Local,
    Preview,
    Remote,
    Display,
}

data class MediaGridPersistentPreviewMetadata(
    val filePath: String,
    val length: Long,
    val lastModified: Long,
)

data class MediaGridImageCandidate(
    val kind: MediaGridImageSourceKind,
    val data: Any,
    val sourceIdentity: String,
)

internal data class MediaGridImageCandidateInput(
    val assetId: Long,
    val mediaKey: String,
    val localPath: String?,
    val previewUrl: String?,
    val remoteUrl: String?,
    val displayUrl: String?,
    val rgb565Slot: MediaGridRgb565Slot? = null,
    val persistentPreview: MediaGridPersistentPreviewMetadata? = null,
)

/** Builds the ordered, available image sources used by both cells and prefetch. */
internal fun buildMediaGridImageCandidates(input: MediaGridImageCandidateInput): List<MediaGridImageCandidate> {
    val result = ArrayList<MediaGridImageCandidate>(6)
    val seen = HashSet<String>(6)

    input.rgb565Slot?.let { slot ->
        if (seen.add(slot.cacheKey)) {
            result += MediaGridImageCandidate(
                kind = MediaGridImageSourceKind.Rgb565Pack,
                data = slot,
                sourceIdentity = slot.cacheKey,
            )
        }
    }

    input.persistentPreview?.takeIf { it.filePath.isNotBlank() && it.length > 0L }?.let { preview ->
        val file = File(preview.filePath).absoluteFile
        if (seen.add(file.absolutePath)) {
            result += MediaGridImageCandidate(
                kind = MediaGridImageSourceKind.PersistentPreview,
                data = file.absolutePath,
                sourceIdentity = persistentPreviewSourceIdentity(input, preview),
            )
        }
    }

    input.localPath?.trim()?.takeIf(String::isNotEmpty)?.let { path ->
        val file = File(path)
        if (file.isFile) {
            val absolutePath = file.absolutePath
            if (seen.add(absolutePath)) {
                result += MediaGridImageCandidate(
                    kind = MediaGridImageSourceKind.Local,
                    data = absolutePath,
                    sourceIdentity = localSourceIdentity(input, file),
                )
            }
        }
    }

    fun addUrl(kind: MediaGridImageSourceKind, value: String?) {
        val url = value?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (!seen.add(url)) return
        result += MediaGridImageCandidate(
            kind = kind,
            data = url,
            sourceIdentity = "url|${input.assetId}|${input.mediaKey}|$url",
        )
    }

    addUrl(MediaGridImageSourceKind.Preview, input.previewUrl)
    addUrl(MediaGridImageSourceKind.Remote, input.remoteUrl)

    input.displayUrl?.trim()?.takeIf(String::isNotEmpty)?.let { display ->
        val displayFile = File(display)
        val normalized = if (displayFile.isFile) displayFile.absolutePath else display
        val isUsable = displayFile.isFile || display.startsWithAny(
            "http://",
            "https://",
            "content://",
            "file://",
        )
        if (isUsable && seen.add(normalized)) {
            result += MediaGridImageCandidate(
                kind = MediaGridImageSourceKind.Display,
                data = normalized,
                sourceIdentity = "display|${input.assetId}|${input.mediaKey}|$normalized",
            )
        }
    }
    return result
}

private fun localSourceIdentity(input: MediaGridImageCandidateInput, file: File): String =
    "local|${input.assetId}|${input.mediaKey}|${file.absolutePath}|${file.length()}|${file.lastModified()}"

internal fun persistentPreviewSourceIdentity(
    input: MediaGridImageCandidateInput,
    metadata: MediaGridPersistentPreviewMetadata,
): String = "preview-v1|${input.assetId}|${input.mediaKey}|${metadata.filePath}|${metadata.length}|${metadata.lastModified}"

private fun String.startsWithAny(vararg prefixes: String): Boolean = prefixes.any(::startsWith)

fun mediaGridImageCacheKey(candidate: MediaGridImageCandidate, width: Int, height: Int): String {
    if (candidate.kind == MediaGridImageSourceKind.Rgb565Pack) {
        return (candidate.data as MediaGridRgb565Slot).cacheKey
    }
    val namespace = if (candidate.kind == MediaGridImageSourceKind.PersistentPreview) {
        "media-grid-preview-v1"
    } else {
        "media-grid"
    }
    val input = "$namespace|${candidate.sourceIdentity}|${width.coerceAtLeast(0)}x${height.coerceAtLeast(0)}"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return if (candidate.kind == MediaGridImageSourceKind.PersistentPreview) {
        "media-grid-preview-v1|$digest"
    } else {
        digest
    }
}

internal data class MediaGridPreparedCandidate(
    val kind: MediaGridImageSourceKind,
    val requestData: Any,
    val sourceIdentity: String,
    val cacheKey: String,
    val width: Int,
    val height: Int,
    val useDiskCache: Boolean = true,
)

internal data class MediaGridPreparedImage(
    val key: com.lyco256.llm.MediaGridRenderKey,
    val assetId: Long,
    val candidates: List<MediaGridPreparedCandidate>,
    val itemIndex: Int = -1,
)

internal fun mediaGridPreparedImageMatches(
    prepared: MediaGridPreparedImage,
    currentKey: com.lyco256.llm.MediaGridRenderKey,
): Boolean = prepared.key == currentKey

/** Prepares file metadata and Coil request metadata away from composition. */
internal class MediaGridImagePreparer(
    private val previewStore: MediaGridPersistentPreviewStore,
    private val rgb565PackStore: MediaGridRgb565PackStore,
    private val rgb565RepairEnqueuer: MediaGridRgb565RepairEnqueuer,
) {
    suspend fun prepare(
        frame: com.lyco256.llm.MediaGridFrameData,
        visibleIndices: Set<Int>,
        cellSizePx: Int,
        direction: Int,
        emit: suspend (MediaGridPreparedImage) -> Unit,
    ) {
        if (visibleIndices.isEmpty() || cellSizePx <= 0) return
        val targetIndices = selectMediaGridPreparationIndices(frame.mediaCellIndices, visibleIndices, frame.key.columnCount, direction)
        prepareIndices(frame, targetIndices, cellSizePx, emit)
    }

    suspend fun prepareIndices(
        frame: com.lyco256.llm.MediaGridFrameData,
        indices: Collection<Int>,
        cellSizePx: Int,
        emit: suspend (MediaGridPreparedImage) -> Unit,
    ) {
        if (indices.isEmpty() || cellSizePx <= 0) return
        for (itemIndex in indices.distinct()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            frame.items.getOrNull(itemIndex) as? com.lyco256.llm.MediaGridCellItem ?: continue
            val prepared = prepareCell(frame, itemIndex, cellSizePx)
            if (mediaGridPreparedImageMatches(prepared, frame.key)) emit(prepared)
        }
    }

    suspend fun preparePersistentPreviews(
        frame: com.lyco256.llm.MediaGridFrameData,
        indices: Collection<Int>,
        emit: suspend (MediaGridPreparedCandidate) -> Unit,
    ) {
        for (itemIndex in indices.distinct()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val cell = frame.items.getOrNull(itemIndex) as? com.lyco256.llm.MediaGridCellItem ?: continue
            val prepared = prepareCell(frame, itemIndex, MEDIA_GRID_PREVIEW_SIZE)
            prepared.candidates.firstOrNull { it.kind == MediaGridImageSourceKind.PersistentPreview }?.let { candidate ->
                emit(candidate)
            }
        }
    }

    internal suspend fun prepareCell(
        frame: com.lyco256.llm.MediaGridFrameData,
        itemIndex: Int,
        cellSizePx: Int,
    ): MediaGridPreparedImage {
        val cell = frame.items[itemIndex] as com.lyco256.llm.MediaGridCellItem
        return withContext(Dispatchers.IO) {
            val input = MediaGridImageCandidateInput(
                assetId = cell.entry.assetId,
                mediaKey = cell.entry.mediaKey,
                localPath = cell.entry.localPath,
                previewUrl = cell.entry.previewUrl,
                remoteUrl = cell.entry.remoteUrl,
                displayUrl = cell.entry.displayUrl,
                rgb565Slot = readRgb565Slot(
                    assetId = cell.entry.assetId,
                    localPath = cell.entry.localPath,
                ),
                persistentPreview = readPersistentPreviewMetadata(
                    assetId = cell.entry.assetId,
                    mediaKey = cell.entry.mediaKey,
                    localPath = cell.entry.localPath,
                ),
            )
            val candidates = buildMediaGridImageCandidates(input).map { candidate ->
                val isFixedPreview = candidate.kind == MediaGridImageSourceKind.Rgb565Pack ||
                    candidate.kind == MediaGridImageSourceKind.PersistentPreview
                val requestSize = if (isFixedPreview) MEDIA_GRID_PREVIEW_SIZE else cellSizePx
                MediaGridPreparedCandidate(
                    kind = candidate.kind,
                    requestData = when (candidate.kind) {
                        MediaGridImageSourceKind.Rgb565Pack -> candidate.data
                        MediaGridImageSourceKind.Local,
                        MediaGridImageSourceKind.PersistentPreview,
                        -> File(candidate.data as String)
                        else -> candidate.data
                    },
                    sourceIdentity = candidate.sourceIdentity,
                    cacheKey = mediaGridImageCacheKey(candidate, requestSize, requestSize),
                    width = requestSize,
                    height = requestSize,
                    useDiskCache = !isFixedPreview,
                )
            }
            MediaGridPreparedImage(frame.key, cell.entry.assetId, candidates, itemIndex)
        }
    }

    private fun readRgb565Slot(
        assetId: Long,
        localPath: String?,
    ): MediaGridRgb565Slot? {
        val local = localPath?.trim()?.takeIf(String::isNotEmpty)?.let(::File)
        val sources = mediaGridRgb565CurrentSources(assetId, local, previewStore)
        val slot = rgb565PackStore.readSlot(assetId, sources.map { it.signature })
        if (slot == null && sources.isNotEmpty()) {
            rgb565RepairEnqueuer.enqueue(listOf(assetId))
        }
        return slot
    }

    private fun readPersistentPreviewMetadata(
        assetId: Long,
        mediaKey: String,
        localPath: String?,
    ): MediaGridPersistentPreviewMetadata? {
        val file = previewStore.previewFile(assetId)
        if (!file.isFile || file.length() <= 0L) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth != MEDIA_GRID_PREVIEW_SIZE ||
            options.outHeight != MEDIA_GRID_PREVIEW_SIZE ||
            options.outMimeType != "image/jpeg"
        ) return null
        val local = localPath?.trim()?.takeIf(String::isNotEmpty)?.let(::File)
        if (local != null && local.isFile && file.lastModified() < local.lastModified()) return null
        return MediaGridPersistentPreviewMetadata(file.absolutePath, file.length(), file.lastModified())
    }

    fun dispose() = Unit
}

internal fun buildMediaGridImageRequest(
    context: Context,
    candidate: MediaGridPreparedCandidate,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(candidate.requestData)
        .size(candidate.width, candidate.height)
        .memoryCacheKey(candidate.cacheKey)
        .crossfade(false)
    if (candidate.kind == MediaGridImageSourceKind.Rgb565Pack) {
        builder.allowRgb565(true)
    }
    if (candidate.useDiskCache) {
        builder.diskCacheKey(candidate.cacheKey)
    } else {
        builder.diskCachePolicy(CachePolicy.DISABLED)
    }
    return builder.build()
}

internal class MediaGridPreviewPreloader(
    private val context: Context,
    private val imageLoader: ImageLoader,
) {
    private val active = java.util.concurrent.ConcurrentHashMap<String, Disposable>()

    fun reconcile(candidates: Collection<MediaGridPreparedCandidate>) {
        val plan = buildMediaGridPreviewPreloadPlan(active.keys, candidates)
        plan.cancelKeys.forEach { key ->
            active.remove(key)?.dispose()
        }
        candidates.asSequence()
            .filter { it.kind == MediaGridImageSourceKind.PersistentPreview && it.cacheKey in plan.enqueueKeys }
            .distinctBy { it.cacheKey }
            .forEach { candidate ->
            if (imageLoader.memoryCache?.get(MemoryCache.Key(candidate.cacheKey)) != null) {
                active.remove(candidate.cacheKey)?.dispose()
                return@forEach
            }
            if (active.containsKey(candidate.cacheKey)) return@forEach
            active[candidate.cacheKey] = imageLoader.enqueue(buildMediaGridImageRequest(context, candidate))
            }
    }

    fun cancelAll() {
        active.values.forEach(Disposable::dispose)
        active.clear()
    }
}

internal data class MediaGridPreviewPreloadPlan(
    val enqueueKeys: Set<String>,
    val cancelKeys: Set<String>,
)

internal fun buildMediaGridPreviewPreloadPlan(
    activeKeys: Set<String>,
    candidates: Collection<MediaGridPreparedCandidate>,
): MediaGridPreviewPreloadPlan {
    val desiredKeys = candidates
        .asSequence()
        .filter { it.kind == MediaGridImageSourceKind.PersistentPreview }
        .map { it.cacheKey }
        .toSet()
    return MediaGridPreviewPreloadPlan(
        enqueueKeys = desiredKeys - activeKeys,
        cancelKeys = activeKeys - desiredKeys,
    )
}

internal class MediaGridPreviewRecoveryGate {
    private val attempted = HashSet<String>()

    fun claim(identity: String): Boolean = synchronized(attempted) { attempted.add(identity) }
}

internal fun selectMediaGridInitialPreloadIndices(
    mediaCellIndices: IntArray,
    visibleMediaIndices: Set<Int>,
    firstVisibleItemIndex: Int,
    columnCount: Int,
    layoutReady: Boolean,
): List<Int> {
    if (mediaCellIndices.isEmpty() || columnCount <= 0) return emptyList()
    if (layoutReady && visibleMediaIndices.isNotEmpty()) return visibleMediaIndices.filter { it >= 0 }.sorted().distinct()
    val start = lowerBound(mediaCellIndices, firstVisibleItemIndex.coerceAtLeast(0))
    return mediaCellIndices.copyOfRange(start, minOf(start + columnCount * 6, mediaCellIndices.size)).toList()
}

internal fun selectMediaGridAdjacentPreloadIndices(
    mediaCellIndices: IntArray,
    visibleIndices: Set<Int>,
    columnCount: Int,
    direction: Int,
): List<Int> = selectMediaGridPreparationIndices(mediaCellIndices, visibleIndices, columnCount, direction)
    .filterNot(visibleIndices::contains)

object MediaGridPreviewNotifier {
    private val events = kotlinx.coroutines.flow.MutableSharedFlow<Long>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val previewChanged = events

    fun notifyPreviewChanged(assetId: Long) {
        events.tryEmit(assetId)
    }
}

/** Selects visible cells and one adjacent row using the prebuilt media-cell index column. */
internal fun selectMediaGridPreparationIndices(
    mediaCellIndices: IntArray,
    visibleIndices: Set<Int>,
    columnCount: Int,
    direction: Int,
): List<Int> {
    if (mediaCellIndices.isEmpty() || visibleIndices.isEmpty() || columnCount <= 0) return emptyList()
    val visible = visibleIndices.asSequence().filter { it >= 0 }.sorted().toList()
    if (visible.isEmpty()) return emptyList()
    val result = ArrayList<Int>(visible.size + columnCount)
    result += visible
    if (direction > 0) {
        val start = upperBound(mediaCellIndices, visible.last())
        for (index in start until minOf(start + columnCount, mediaCellIndices.size)) result += mediaCellIndices[index]
    } else if (direction < 0) {
        var index = lowerBound(mediaCellIndices, visible.first()) - 1
        repeat(minOf(columnCount, index + 1)) { result += mediaCellIndices[index--] }
    }
    return result.distinct()
}

private fun lowerBound(values: IntArray, target: Int): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (values[mid] < target) low = mid + 1 else high = mid
    }
    return low
}

private fun upperBound(values: IntArray, target: Int): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (values[mid] <= target) low = mid + 1 else high = mid
    }
    return low
}
