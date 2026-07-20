package com.lyco256.llm.data

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class MediaGridImageSourceKind {
    Local,
    Preview,
    Remote,
    Display,
}

data class MediaGridImageCandidate(
    val kind: MediaGridImageSourceKind,
    val data: String,
    val sourceIdentity: String,
)

data class MediaGridImageCandidateInput(
    val assetId: Long,
    val mediaKey: String,
    val localPath: String?,
    val previewUrl: String?,
    val remoteUrl: String?,
    val displayUrl: String?,
)

/** Builds the ordered, available image sources used by both cells and prefetch. */
fun buildMediaGridImageCandidates(input: MediaGridImageCandidateInput): List<MediaGridImageCandidate> {
    val result = ArrayList<MediaGridImageCandidate>(4)
    val seen = HashSet<String>(4)

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

private fun String.startsWithAny(vararg prefixes: String): Boolean = prefixes.any(::startsWith)

fun mediaGridImageCacheKey(candidate: MediaGridImageCandidate, width: Int, height: Int): String {
    val input = "media-grid|${candidate.sourceIdentity}|${width.coerceAtLeast(0)}x${height.coerceAtLeast(0)}"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal data class MediaGridPreparedCandidate(
    val kind: MediaGridImageSourceKind,
    val requestData: Any,
    val sourceIdentity: String,
    val cacheKey: String,
    val width: Int,
    val height: Int,
)

internal data class MediaGridPreparedImage(
    val key: com.lyco256.llm.MediaGridRenderKey,
    val assetId: Long,
    val candidates: List<MediaGridPreparedCandidate>,
)

internal fun mediaGridPreparedImageMatches(
    prepared: MediaGridPreparedImage,
    currentKey: com.lyco256.llm.MediaGridRenderKey,
): Boolean = prepared.key == currentKey

/** Prepares file metadata and Coil request metadata away from composition. */
internal class MediaGridImagePreparer {
    private data class CacheKey(val key: com.lyco256.llm.MediaGridRenderKey, val assetId: Long, val size: Int)
    private val cache = ConcurrentHashMap<CacheKey, MediaGridPreparedImage>()

    suspend fun prepare(
        frame: com.lyco256.llm.MediaGridFrameData,
        visibleIndices: Set<Int>,
        cellSizePx: Int,
        direction: Int,
        emit: suspend (MediaGridPreparedImage) -> Unit,
    ) {
        if (visibleIndices.isEmpty() || cellSizePx <= 0) return
        val targetIndices = selectMediaGridPreparationIndices(frame.mediaCellIndices, visibleIndices, frame.key.columnCount, direction)
        for (itemIndex in targetIndices) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val cell = frame.items.getOrNull(itemIndex) as? com.lyco256.llm.MediaGridCellItem ?: continue
            val cacheKey = CacheKey(frame.key, cell.entry.assetId, cellSizePx)
            val prepared = cache[cacheKey] ?: withContext(Dispatchers.IO) {
                val candidates = buildMediaGridImageCandidates(
                    MediaGridImageCandidateInput(
                        assetId = cell.entry.assetId,
                        mediaKey = cell.entry.mediaKey,
                        localPath = cell.entry.localPath,
                        previewUrl = cell.entry.previewUrl,
                        remoteUrl = cell.entry.remoteUrl,
                        displayUrl = cell.entry.displayUrl,
                    ),
                ).map { candidate ->
                    MediaGridPreparedCandidate(
                        kind = candidate.kind,
                        requestData = if (candidate.kind == MediaGridImageSourceKind.Local) File(candidate.data) else candidate.data,
                        sourceIdentity = candidate.sourceIdentity,
                        cacheKey = mediaGridImageCacheKey(candidate, cellSizePx, cellSizePx),
                        width = cellSizePx,
                        height = cellSizePx,
                    )
                }
                MediaGridPreparedImage(frame.key, cell.entry.assetId, candidates)
            }.also { cache[cacheKey] = it }
            if (mediaGridPreparedImageMatches(prepared, frame.key)) emit(prepared)
        }
    }

    fun dispose() = cache.clear()
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
