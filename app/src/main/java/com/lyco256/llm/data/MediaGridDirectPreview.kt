package com.lyco256.llm.data

import android.content.Context
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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

data class MediaGridPrefetchEntry(
    val itemIndex: Int,
    val assetId: Long,
    val candidates: List<MediaGridImageCandidate>,
)

fun mediaGridPrefetchRequestId(sourceRevision: Long, assetId: Long, cacheKey: String): String =
    "$sourceRevision|$assetId|$cacheKey"

/** Returns only the next/previous media row, never more than columnCount cells. */
fun selectMediaGridPrefetchEntries(
    items: List<MediaGridPrefetchEntry>,
    visibleItemIndices: Set<Int>,
    columnCount: Int,
    direction: Int,
): List<MediaGridPrefetchEntry> {
    if (items.isEmpty() || visibleItemIndices.isEmpty() || columnCount <= 0 || direction == 0) return emptyList()
    val ordered = if (direction > 0) {
        items.asSequence().filter { it.itemIndex > visibleItemIndices.maxOrNull()!! }.sortedBy { it.itemIndex }
    } else {
        items.asSequence().filter { it.itemIndex < visibleItemIndices.minOrNull()!! }.sortedByDescending { it.itemIndex }
    }
    return ordered.take(columnCount).toList()
}

/** Owns only targetless Coil prefetch requests for the current adjacent row. */
class MediaGridPrefetchController(
    context: Context,
    private val imageLoader: ImageLoader,
) {
    private val requestContext = context.applicationContext
    private val active = ConcurrentHashMap<String, Disposable>()

    fun update(
        sourceRevision: Long,
        columnCount: Int,
        visibleAssetIds: Set<Long>,
        items: List<MediaGridPrefetchEntry>,
        visibleItemIndices: Set<Int>,
        cellSizePx: Int,
        direction: Int,
    ) {
        val targets = selectMediaGridPrefetchEntries(items, visibleItemIndices, columnCount, direction)
            .asSequence()
            .filter { it.assetId !in visibleAssetIds }
            .mapNotNull { entry ->
                val candidate = entry.candidates.firstOrNull() ?: return@mapNotNull null
                val cacheKey = mediaGridImageCacheKey(candidate, cellSizePx, cellSizePx)
                PrefetchTarget(
                    requestId = mediaGridPrefetchRequestId(sourceRevision, entry.assetId, cacheKey),
                    cacheKey = cacheKey,
                    candidate = candidate,
                )
            }
            .toList()
        val keep = targets.mapTo(HashSet(targets.size)) { it.requestId }
        active.entries.removeIf { (requestId, disposable) ->
            if (requestId in keep) false else {
                disposable.dispose()
                true
            }
        }
        if (cellSizePx <= 0 || direction == 0) return

        targets.forEach { target ->
            if (active.containsKey(target.requestId) || imageLoader.memoryCache?.get(MemoryCache.Key(target.cacheKey)) != null) return@forEach
            val request = ImageRequest.Builder(requestContext)
                .data(target.candidate.data)
                .size(cellSizePx, cellSizePx)
                .memoryCacheKey(target.cacheKey)
                .diskCacheKey(target.cacheKey)
                .crossfade(false)
                .listener(object : ImageRequest.Listener {
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        active.remove(target.requestId)
                    }

                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        active.remove(target.requestId)
                    }

                    override fun onCancel(request: ImageRequest) {
                        active.remove(target.requestId)
                    }
                })
                .build()
            active[target.requestId] = imageLoader.enqueue(request)
        }
    }

    fun dispose() {
        active.values.forEach(Disposable::dispose)
        active.clear()
    }

    private data class PrefetchTarget(
        val requestId: String,
        val cacheKey: String,
        val candidate: MediaGridImageCandidate,
    )
}
