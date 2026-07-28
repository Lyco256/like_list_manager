package com.lyco256.llm

import android.content.Context
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.memory.MemoryCache
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun drawIndexVersionFlowOnlyPublishesContentChanges() = runBlocking {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val owner = store.newOwnerToken()
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        try {
            val c = candidate(1L)
            val value = MemoryCache.Value(bitmap, emptyMap())
            val initial = store.drawIndexVersionFlow.first()
            store.retain(1L, c, value)
            val retained = store.drawIndexVersionFlow.first { it > initial }
            store.updateProtection(owner, longArrayOf(1L), longArrayOf())
            assertEquals(retained, store.drawIndexVersionFlow.value)
            assertTrue(store.restore(1L, c))
            assertEquals(retained, store.drawIndexVersionFlow.value)
            store.invalidateAsset(1L)
            assertTrue(store.drawIndexVersionFlow.value > retained)
        } finally {
            store.removeOwner(owner)
            store.clear()
            bitmap.recycle()
            loader.shutdown()
        }
    }

    @Test
    fun eligibilityIsIdentitySafeMonotonicAndLockFree() {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        try {
            val original = candidate(7L)
            val value = MemoryCache.Value(bitmap, emptyMap())
            store.retain(7L, original, value, directDrawEligible = false)
            assertFalse(store.hasEligibleDrawHandle(7L))
            val beforeMark = store.drawIndexSnapshot().version
            store.markDirectDrawEligible(7L, original)
            assertTrue(store.lookupEligibleDrawHandle(7L)!!.directDrawEligible)
            val afterMark = store.drawIndexSnapshot().version
            assertTrue(afterMark > beforeMark)
            val lockCount = store.lockAcquisitionCount()
            repeat(1_000) { assertTrue(store.hasEligibleDrawHandle(7L)) }
            assertEquals(lockCount, store.lockAcquisitionCount())
            store.retain(7L, original, value, directDrawEligible = false)
            assertTrue(store.hasEligibleDrawHandle(7L))
            assertEquals(afterMark, store.drawIndexSnapshot().version)
            val replacement = candidate(7L, "replacement")
            store.retain(7L, replacement, value, directDrawEligible = false)
            assertFalse(store.hasEligibleDrawHandle(7L))
            store.markDirectDrawEligible(7L, original)
            assertFalse(store.hasEligibleDrawHandle(7L))
            store.markDirectDrawEligible(7L, replacement)
            assertTrue(store.hasEligibleDrawHandle(7L))
            store.invalidateAsset(7L)
            assertFalse(store.hasEligibleDrawHandle(7L))
        } finally {
            store.clear()
            bitmap.recycle()
            loader.shutdown()
        }
    }

    @Test
    fun sharedStoreKeepsThreeHundredEntriesAndEvictsOldestUnprotectedFirst() {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(64 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val owner = store.newOwnerToken()
        val bitmaps = ArrayList<Bitmap>(301)
        try {
            repeat(300) { assetId ->
                val c = candidate(assetId.toLong())
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
                bitmap.eraseColor(Color.BLACK)
                bitmap.setPixel(assetId % 256, assetId / 256, Color.WHITE)
                bitmaps += bitmap
                val value = MemoryCache.Value(bitmap, emptyMap())
                loader.memoryCache?.set(MemoryCache.Key(c.cacheKey), value)
                store.retain(assetId.toLong(), c, value)
            }
            store.updateProtection(owner, longArrayOf(0L), longArrayOf(1L))
            val bitmap300 = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
            bitmap300.eraseColor(Color.BLACK)
            bitmap300.setPixel(300 % 256, 300 / 256, Color.WHITE)
            bitmaps += bitmap300
            store.retain(300L, candidate(300L), MemoryCache.Value(bitmap300, emptyMap()))
            assertEquals(300, store.stats().entryCount)
            assertFalse(store.restore(2L, candidate(2L)))
            assertTrue(store.restore(0L, candidate(0L)))
            assertTrue(store.restore(1L, candidate(1L)))
            val handles = (0L..300L).mapNotNull { assetId -> store.lookupDrawHandle(assetId, candidate(assetId)) }
            assertEquals(300, handles.size)
            assertEquals(300, handles.map { it.value.bitmap }.distinct().size)
            assertTrue(handles.all { handle ->
                val assetId = handle.identity.assetId.toInt()
                handle.value.bitmap.getPixel(assetId % 256, assetId / 256) == Color.WHITE
            })
        } finally {
            store.removeOwner(owner)
            store.clear()
            bitmaps.forEach(Bitmap::recycle)
            loader.shutdown()
        }
    }

    @Test
    fun drawIndexLookupIsIdentitySafeAndDoesNotAcquireStoreLock() {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        try {
            val original = candidate(7L)
            val value = MemoryCache.Value(bitmap, emptyMap())
            store.retain(7L, original, value)
            val before = store.drawIndexSnapshot()
            loader.memoryCache?.remove(MemoryCache.Key(original.cacheKey))
            assertTrue(store.restore(7L, original))
            assertEquals(before.version, store.drawIndexSnapshot().version)
            val lockCount = store.lockAcquisitionCount()
            repeat(1_000) { assertSame(value, store.lookupDrawHandle(7L, original)?.value) }
            assertEquals(lockCount, store.lockAcquisitionCount())
            assertEquals(before.version, store.drawIndexSnapshot().version)
            assertNull(store.lookupDrawHandle(7L, candidate(7L, "different")))
            assertNull(store.lookupDrawHandle(8L, original))

            val replacement = candidate(7L, "replacement")
            store.retain(7L, replacement, value)
            assertNull(store.lookupDrawHandle(7L, original))
            assertSame(value, store.lookupDrawHandle(7L, replacement)?.value)
            store.invalidateAsset(7L)
            assertNull(store.lookupDrawHandle(7L, replacement))
            assertTrue(store.drawIndexIsConsistent())
            store.close()
            store.retain(7L, replacement, value)
            assertNull(store.lookupDrawHandle(7L, replacement))
        } finally {
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

    @Test
    fun fixedOrderConcurrentStoreOperationsKeepMutableAndDrawSnapshotsConsistent() {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val owner = store.newOwnerToken()
        val bitmaps = (0 until 8).map { index ->
            Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565).also {
                it.setPixel(0, 0, Color.rgb(index * 17, index * 23, index * 31))
            }
        }
        val candidates = (0L until 8L).map { candidate(it) }
        val values = bitmaps.map { MemoryCache.Value(it, emptyMap()) }
        val barrier = CyclicBarrier(7)
        val executor = Executors.newFixedThreadPool(6)
        try {
            candidates.indices.forEach { index -> store.retain(index.toLong(), candidates[index], values[index]) }
            val futures = (0 until 6).map { worker -> executor.submit {
                repeat(32) { round ->
                    barrier.await()
                    when (worker) {
                        0 -> store.retain((round % 8).toLong(), candidates[round % 8], values[round % 8])
                        1 -> store.lookupDrawHandle((round % 8).toLong(), candidates[round % 8])
                        2 -> store.invalidateAsset(((round + 1) % 8).toLong())
                        3 -> store.updateProtection(owner, longArrayOf((round % 8).toLong()), longArrayOf(((round + 2) % 8).toLong()))
                        4 -> store.onTrimMemory(if (round % 2 == 0) ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW else ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
                        else -> store.restore((round % 8).toLong(), candidates[round % 8])
                    }
                    barrier.await()
                }
            } }
            repeat(32) {
                barrier.await()
                barrier.await()
                assertTrue(store.drawIndexIsConsistent())
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(store.drawIndexIsConsistent())
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            store.removeOwner(owner)
            store.clear()
            bitmaps.forEach(Bitmap::recycle)
            loader.shutdown()
        }
    }
}
