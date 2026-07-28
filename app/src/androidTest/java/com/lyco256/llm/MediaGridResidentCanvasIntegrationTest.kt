package com.lyco256.llm

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.memory.MemoryCache
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaGridResidentCanvasIntegrationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun twelveDistinctRgb565BitmapsAdaptOncePerIdentityWithoutCopying() {
        val loader = ImageLoader.Builder(context).memoryCache {
            MemoryCache.Builder(context).maxSizeBytes(16 * 1024 * 1024).build()
        }.build()
        val store = MediaGridRetainedImageStore(loader.memoryCache)
        val adapter = MediaGridResidentCanvasImageAdapter()
        val bitmaps = (0 until 12).map { index ->
            Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565).also { it.eraseColor(Color.rgb(index * 17, index * 11, index * 7)) }
        }
        try {
            bitmaps.forEachIndexed { index, bitmap ->
                val candidate = MediaGridPreparedCandidate(
                    kind = MediaGridImageSourceKind.Rgb565Pack,
                    requestData = index.toLong(),
                    sourceIdentity = "source-$index",
                    cacheKey = "cache-$index",
                    width = 256,
                    height = 256,
                )
                store.retain(index.toLong(), candidate, MemoryCache.Value(bitmap, emptyMap()))
            }
            val index = store.drawIndexSnapshot()
            adapter.sync(index)
            val images = index.handlesByIdentity.values.mapNotNull(adapter::resolve)
            assertEquals(12, images.size)
            assertEquals(12, adapter.size())
            images.forEach { image ->
                val handle = index.handlesByIdentity[image.identity]!!
                assertSame(image, adapter.resolve(handle))
                assertSame(handle.value.bitmap, bitmaps[image.identity.assetId.toInt()])
            }
            assertTrue(bitmaps.all { it.config == Bitmap.Config.RGB_565 })
        } finally {
            store.clear()
            bitmaps.forEach(Bitmap::recycle)
            loader.shutdown()
        }
    }
}
