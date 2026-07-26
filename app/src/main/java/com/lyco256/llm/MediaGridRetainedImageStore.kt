package com.lyco256.llm

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.memory.MemoryCache
import com.lyco256.llm.data.MediaGridPreparedCandidate
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class MediaGridRetainedImageKey(
    val assetId: Long,
    val memoryCacheKey: String,
    val sourceIdentity: String,
)

internal data class MediaGridRetainedImageStats(
    val entryCount: Int,
    val estimatedBytes: Long,
    val evictions: Long,
    val restores: Long,
)

/** Shared, image-only retention for all classified media-grid sessions. */
internal class MediaGridRetainedImageStore(
    private val memoryCache: MemoryCache?,
    private val targetEntryCount: Int = MEDIA_GRID_RETAINED_IMAGE_TARGET_ENTRIES,
    private val byteLimit: Long = minOf(
        MEDIA_GRID_RETAINED_IMAGE_MAX_BYTES,
        ((memoryCache?.maxSize ?: 0).toDouble() * MEDIA_GRID_RETAINED_IMAGE_MEMORY_FRACTION).toLong(),
    ),
) : ComponentCallbacks2 {
    private data class Entry(
        val key: MediaGridRetainedImageKey,
        val value: MemoryCache.Value,
        val estimatedBytes: Long,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<MediaGridRetainedImageKey, Entry>(targetEntryCount, 0.75f, true)
    private val ownerProtections = HashMap<Long, Protection>()
    private val nextOwnerToken = AtomicLong(1L)
    private var estimatedBytes = 0L
    private var evictionCount = 0L
    private var restoreCount = 0L

    private data class Protection(val visible: Set<Long>, val active: Set<Long>)

    fun newOwnerToken(): Long = nextOwnerToken.getAndIncrement()

    fun updateProtection(ownerToken: Long, visibleAssetIds: LongArray, activeAssetIds: LongArray) {
        synchronized(lock) {
            ownerProtections[ownerToken] = Protection(visibleAssetIds.toSet(), activeAssetIds.toSet())
            trimLocked()
        }
    }

    fun removeOwner(ownerToken: Long) {
        synchronized(lock) {
            ownerProtections.remove(ownerToken)
            trimLocked()
        }
    }

    fun retain(assetId: Long, candidate: MediaGridPreparedCandidate, value: MemoryCache.Value) {
        val key = MediaGridRetainedImageKey(assetId, candidate.cacheKey, candidate.sourceIdentity)
        val entry = Entry(key, value, mediaGridEstimatedBitmapBytes(candidate))
        synchronized(lock) {
            entries.keys.filter { it.assetId == assetId && it != key }.toList().forEach(::removeLocked)
            entries.remove(key)?.let { estimatedBytes -= it.estimatedBytes }
            entries[key] = entry
            estimatedBytes += entry.estimatedBytes
            trimLocked()
        }
    }

    /** Returns the retained Coil value and restores it into Coil without decoding or copying. */
    fun restore(assetId: Long, candidate: MediaGridPreparedCandidate): Boolean {
        val key = MediaGridRetainedImageKey(assetId, candidate.cacheKey, candidate.sourceIdentity)
        synchronized(lock) {
            val entry = entries[key] ?: return false
            memoryCache?.set(MemoryCache.Key(candidate.cacheKey), entry.value)
            restoreCount++
            trimLocked()
            return true
        }
    }

    fun touch(assetId: Long, candidate: MediaGridPreparedCandidate): Boolean {
        val key = MediaGridRetainedImageKey(assetId, candidate.cacheKey, candidate.sourceIdentity)
        synchronized(lock) { return entries[key] != null }
    }

    fun invalidateAsset(assetId: Long) {
        synchronized(lock) {
            entries.keys.filter { it.assetId == assetId }.toList().forEach(::removeLocked)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            ownerProtections.clear()
            estimatedBytes = 0L
        }
    }

    fun stats(): MediaGridRetainedImageStats = synchronized(lock) {
        MediaGridRetainedImageStats(entries.size, estimatedBytes, evictionCount, restoreCount)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            synchronized(lock) {
                entries.keys.filterNot(::isVisibleLocked).toList().forEach(::removeLocked)
            }
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            synchronized(lock) { trimLocked(targetEntryCount / 2, byteLimit / 2L) }
        }
    }

    private fun trimLocked(
        maxEntries: Int = targetEntryCount,
        maxBytes: Long = byteLimit,
    ) {
        while (entries.size > maxEntries || estimatedBytes > maxBytes) {
            val candidate = entries.values.firstOrNull { !isVisibleLocked(it.key) && !isActiveLocked(it.key) }
                ?: entries.values.firstOrNull { !isVisibleLocked(it.key) }
                ?: break
            removeLocked(candidate.key)
            evictionCount++
        }
    }

    private fun isVisibleLocked(key: MediaGridRetainedImageKey): Boolean =
        ownerProtections.values.any { key.assetId in it.visible }

    private fun isActiveLocked(key: MediaGridRetainedImageKey): Boolean =
        ownerProtections.values.any { key.assetId in it.active }

    private fun removeLocked(key: MediaGridRetainedImageKey) {
        entries.remove(key)?.let { estimatedBytes -= it.estimatedBytes }
    }
}

internal const val MEDIA_GRID_RETAINED_IMAGE_TARGET_ENTRIES = 300
internal const val MEDIA_GRID_RETAINED_IMAGE_MAX_BYTES = 48L * 1024L * 1024L
internal const val MEDIA_GRID_RETAINED_IMAGE_MEMORY_FRACTION = 0.75
