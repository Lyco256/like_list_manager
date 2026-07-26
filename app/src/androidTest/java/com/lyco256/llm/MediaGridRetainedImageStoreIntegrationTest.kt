package com.lyco256.llm

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.memory.MemoryCache
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaGridRetainedImageStoreIntegrationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun candidate(assetId: Long, suffix: String = "same") = MediaGridPreparedCandidate(
        kind = MediaGridImageSourceKind.Rgb565Pack,
        requestData = assetId,
        sourceIdentity = "source-$assetId-$suffix",
        cacheKey = "cache-$assetId-$suffix",
        width = 256,
        height = 256,
    )

    @Test
    fun sharedStoreKeepsThreeHundredEntriesAndEvictsOldestUnprotectedFirst() {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(64 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val owner = store.newOwnerToken()
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        try {
            repeat(300) { assetId ->
                val c = candidate(assetId.toLong())
                val value = MemoryCache.Value(bitmap, emptyMap())
                loader.memoryCache?.set(MemoryCache.Key(c.cacheKey), value)
                store.retain(assetId.toLong(), c, value)
            }
            store.updateProtection(owner, longArrayOf(0L), longArrayOf(1L))
            store.retain(300L, candidate(300L), MemoryCache.Value(bitmap, emptyMap()))
            assertEquals(300, store.stats().entryCount)
            assertFalse(store.restore(2L, candidate(2L)))
            assertTrue(store.restore(0L, candidate(0L)))
            assertTrue(store.restore(1L, candidate(1L)))
        } finally {
            store.removeOwner(owner)
            store.clear()
            bitmap.recycle()
            loader.shutdown()
        }
    }

    @Test
    fun restoreUsesTheSameCoilValueWithoutASecondLoadAndInvalidationIsAssetLocal() {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        try {
            val old = candidate(7L)
            val value = MemoryCache.Value(bitmap, emptyMap())
            loader.memoryCache?.set(MemoryCache.Key(old.cacheKey), value)
            store.retain(7L, old, value)
            loader.memoryCache?.remove(MemoryCache.Key(old.cacheKey))
            assertTrue(store.restore(7L, old))
            assertTrue(value.bitmap === loader.memoryCache?.get(MemoryCache.Key(old.cacheKey))?.bitmap)

            val replacement = candidate(7L, "new")
            store.retain(7L, replacement, value)
            assertFalse(store.restore(7L, old))
            assertTrue(store.restore(7L, replacement))
        } finally {
            store.clear()
            bitmap.recycle()
            loader.shutdown()
        }
    }

    @Test
    fun memoryTrimKeepsVisibleOnlyAtBackgroundAndHalvesAtRunningLow() {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(64 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val owner = store.newOwnerToken()
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        try {
            repeat(200) { assetId -> store.retain(assetId.toLong(), candidate(assetId.toLong()), MemoryCache.Value(bitmap, emptyMap())) }
            store.updateProtection(owner, longArrayOf(0L), longArrayOf())
            store.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertTrue(store.stats().entryCount <= 150)
            assertTrue(store.restore(0L, candidate(0L)))
            store.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            assertEquals(1, store.stats().entryCount)
            assertTrue(store.restore(0L, candidate(0L)))
        } finally {
            store.removeOwner(owner)
            store.clear()
            bitmap.recycle()
            loader.shutdown()
        }
    }
}
