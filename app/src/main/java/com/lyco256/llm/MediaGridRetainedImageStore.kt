package com.lyco256.llm

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.memory.MemoryCache
import com.lyco256.llm.data.MediaGridPreparedCandidate
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MediaGridResidentImageIdentity(
    val assetId: Long,
    val memoryCacheKey: String,
    val sourceIdentity: String,
)

internal typealias MediaGridRetainedImageKey = MediaGridResidentImageIdentity

/** Immutable reference to one retained Coil value, suitable for a later draw layer. */
internal data class MediaGridResidentDrawHandle(
    val identity: MediaGridResidentImageIdentity,
    val value: MemoryCache.Value,
    val estimatedBytes: Long,
)

/** Immutable, lock-free read snapshot for resident drawing. */
internal data class MediaGridResidentDrawIndex(
    val version: Long,
    val handlesByIdentity: Map<MediaGridResidentImageIdentity, MediaGridResidentDrawHandle>,
    val identityByAssetId: Map<Long, MediaGridResidentImageIdentity>,
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
    private val keyByAssetId = HashMap<Long, MediaGridRetainedImageKey>(targetEntryCount)
    private val ownerProtections = HashMap<Long, Protection>()
    private val nextOwnerToken = AtomicLong(1L)
    private val lockAcquisitionCount = AtomicLong(0L)
    private val drawIndex = AtomicReference(MediaGridResidentDrawIndex(0L, emptyMap(), emptyMap()))
    private val _drawIndexVersion = MutableStateFlow(0L)
    /** Latest draw-index version only; viewport/protection/restore do not publish here. */
    val drawIndexVersionFlow: StateFlow<Long> = _drawIndexVersion.asStateFlow()
    private var estimatedBytes = 0L
    private var evictionCount = 0L
    private var restoreCount = 0L
    private var drawIndexVersion = 0L
    private var closed = false

    private data class Protection(val visible: Set<Long>, val active: Set<Long>)

    fun newOwnerToken(): Long = nextOwnerToken.getAndIncrement()

    fun updateProtection(ownerToken: Long, visibleAssetIds: LongArray, activeAssetIds: LongArray) {
        lockAcquisitionCount.incrementAndGet()
        synchronized(lock) {
            if (closed) return
            ownerProtections[ownerToken] = Protection(visibleAssetIds.toSet(), activeAssetIds.toSet())
            visibleAssetIds.forEach { assetId ->
                keyByAssetId[assetId]?.let { key -> entries[key] }
            }
            if (trimLocked()) publishDrawIndexLocked()
        }
    }

    fun removeOwner(ownerToken: Long) {
        lockAcquisitionCount.incrementAndGet()
        synchronized(lock) {
            ownerProtections.remove(ownerToken)
            if (trimLocked()) publishDrawIndexLocked()
        }
    }

    fun retain(assetId: Long, candidate: MediaGridPreparedCandidate, value: MemoryCache.Value) {
        val key = MediaGridRetainedImageKey(assetId, candidate.cacheKey, candidate.sourceIdentity)
        val entry = Entry(key, value, mediaGridEstimatedBitmapBytes(candidate))
        lockAcquisitionCount.incrementAndGet()
        synchronized(lock) {
            var changed = false
            if (closed) return
            keyByAssetId[assetId]?.takeIf { it != key }?.let { changed = removeLocked(it) || changed }
            val previous = entries.remove(key)
            if (previous != null) {
                estimatedBytes -= previous.estimatedBytes
                changed = changed || previous.value !== value || previous.estimatedBytes != entry.estimatedBytes
            } else {
                changed = true
            }
            entries[key] = entry
            keyByAssetId[assetId] = key
            estimatedBytes += entry.estimatedBytes
            val trimmed = trimLocked()
            changed = trimmed || changed
            if (changed) publishDrawIndexLocked()
        }
    }

    /** Returns the retained Coil value and restores it into Coil without decoding or copying. */
    fun restore(assetId: Long, candidate: MediaGridPreparedCandidate): Boolean {
        val key = MediaGridRetainedImageKey(assetId, candidate.cacheKey, candidate.sourceIdentity)
        lockAcquisitionCount.incrementAndGet()
        synchronized(lock) {
            if (closed) return false
            val entry = entries[key] ?: return false
            memoryCache?.set(MemoryCache.Key(candidate.cacheKey), entry.value)
            restoreCount++
            if (trimLocked()) publishDrawIndexLocked()
            return true
        }
    }

    /** Reads only the latest immutable index; it never enters the store lock or touches Coil. */
    fun lookupDrawHandle(assetId: Long, candidate: MediaGridPreparedCandidate): MediaGridResidentDrawHandle? {
        val snapshot = drawIndex.get()
        val identity = snapshot.identityByAssetId[assetId] ?: return null
        if (identity.memoryCacheKey != candidate.cacheKey || identity.sourceIdentity != candidate.sourceIdentity) return null
        return snapshot.handlesByIdentity[identity]
    }

    internal fun drawIndexSnapshot(): MediaGridResidentDrawIndex = drawIndex.get()

    internal fun lockAcquisitionCount(): Long = lockAcquisitionCount.get()

    internal fun drawIndexIsConsistent(): Boolean {
        lockAcquisitionCount.incrementAndGet()
        return synchronized(lock) {
            if (keyByAssetId.any { (assetId, key) -> key.assetId != assetId || entries[key] == null }) return false
            if (entries.keys.groupBy { it.assetId }.any { it.value.size > 1 }) return false
            val snapshot = drawIndex.get()
            if (snapshot.handlesByIdentity.size != entries.size || snapshot.identityByAssetId.size != keyByAssetId.size) return false
            entries.forEach { (key, entry) ->
                val handle = snapshot.handlesByIdentity[key] ?: return false
                if (handle.value !== entry.value || handle.estimatedBytes != entry.estimatedBytes) return false
            }
            keyByAssetId.all { (assetId, key) -> snapshot.identityByAssetId[assetId] == key }
        }
    }

    fun invalidateAsset(assetId: Long) {
        lockAcquisitionCount.incrementAndGet()
        synchronized(lock) {
            if (closed) return
            val key = keyByAssetId[assetId] ?: return
            removeLocked(key)
            publishDrawIndexLocked()
        }
    }

    fun clear() {
        lockAcquisitionCount.incrementAndGet()
        synchronized(lock) {
            clearLocked()
        }
    }

    fun close() {
        lockAcquisitionCount.incrementAndGet()
        synchronized(lock) {
            closed = true
            clearLocked()
        }
    }

    fun stats(): MediaGridRetainedImageStats {
        lockAcquisitionCount.incrementAndGet()
        return synchronized(lock) {
            MediaGridRetainedImageStats(entries.size, estimatedBytes, evictionCount, restoreCount)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

    override fun onTrimMemory(level: Int) {
        if (closed) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            lockAcquisitionCount.incrementAndGet()
            synchronized(lock) {
                var changed = false
                entries.keys.filterNot(::isVisibleLocked).toList().forEach { changed = removeLocked(it) || changed }
                if (changed) publishDrawIndexLocked()
            }
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            lockAcquisitionCount.incrementAndGet()
            synchronized(lock) { if (trimLocked(targetEntryCount / 2, byteLimit / 2L)) publishDrawIndexLocked() }
        }
    }

    private fun trimLocked(
        maxEntries: Int = targetEntryCount,
        maxBytes: Long = byteLimit,
    ): Boolean {
        var changed = false
        while (entries.size > maxEntries || estimatedBytes > maxBytes) {
            val candidate = entries.values.firstOrNull { !isVisibleLocked(it.key) && !isActiveLocked(it.key) }
                ?: entries.values.firstOrNull { !isVisibleLocked(it.key) }
                ?: break
            changed = removeLocked(candidate.key) || changed
            evictionCount++
        }
        return changed
    }

    private fun isVisibleLocked(key: MediaGridRetainedImageKey): Boolean =
        ownerProtections.values.any { key.assetId in it.visible }

    private fun isActiveLocked(key: MediaGridRetainedImageKey): Boolean =
        ownerProtections.values.any { key.assetId in it.active }

    private fun removeLocked(key: MediaGridRetainedImageKey): Boolean {
        val removed = entries.remove(key) ?: return false
        estimatedBytes -= removed.estimatedBytes
        if (keyByAssetId[key.assetId] == key) keyByAssetId.remove(key.assetId)
        return true
    }

    private fun clearLocked() {
        val changed = entries.isNotEmpty() || keyByAssetId.isNotEmpty()
        entries.clear()
        keyByAssetId.clear()
        ownerProtections.clear()
        estimatedBytes = 0L
        if (changed) publishDrawIndexLocked()
    }

    private fun publishDrawIndexLocked() {
        drawIndexVersion++
        val handles = HashMap<MediaGridResidentImageIdentity, MediaGridResidentDrawHandle>(entries.size)
        val identities = HashMap<Long, MediaGridResidentImageIdentity>(keyByAssetId.size)
        entries.values.forEach { entry ->
            handles[entry.key] = MediaGridResidentDrawHandle(entry.key, entry.value, entry.estimatedBytes)
        }
        keyByAssetId.forEach { (assetId, key) -> if (entries.containsKey(key)) identities[assetId] = key }
        drawIndex.set(
            MediaGridResidentDrawIndex(
                version = drawIndexVersion,
                handlesByIdentity = Collections.unmodifiableMap(handles),
                identityByAssetId = Collections.unmodifiableMap(identities),
            ),
        )
        _drawIndexVersion.value = drawIndexVersion
    }
}

internal const val MEDIA_GRID_RETAINED_IMAGE_TARGET_ENTRIES = 300
internal const val MEDIA_GRID_RETAINED_IMAGE_MAX_BYTES = 48L * 1024L * 1024L
internal const val MEDIA_GRID_RETAINED_IMAGE_MEMORY_FRACTION = 0.75
