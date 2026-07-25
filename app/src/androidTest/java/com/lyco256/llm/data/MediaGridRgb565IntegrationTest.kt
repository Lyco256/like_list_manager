package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lyco256.llm.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaGridRgb565IntegrationTest {
    @Test
    fun centerCropProducesRgb565AndCoilReturnsItWithoutDecoderConversion() = runBlocking {
        val fixture = fixture()
        try {
            val sourceBitmap = Bitmap.createBitmap(768, 256, Bitmap.Config.ARGB_8888)
            Canvas(sourceBitmap).apply {
                drawRect(0f, 0f, 256f, 256f, Paint().apply { color = Color.RED })
                drawRect(256f, 0f, 512f, 256f, Paint().apply { color = Color.GREEN })
                drawRect(512f, 0f, 768f, 256f, Paint().apply { color = Color.BLUE })
            }
            val payload = try {
                createMediaGridRgb565Payload(sourceBitmap)
            } finally {
                sourceBitmap.recycle()
            }
            val slot = fixture.store.publish(1, payload, source(1))
            val direct = fixture.store.readBitmap(slot)
            try {
                assertEquals(Bitmap.Config.RGB_565, direct.config)
                assertEquals(256, direct.width)
                assertEquals(256, direct.height)
                val center = direct.getPixel(128, 128)
                assertTrue(Color.green(center) > Color.red(center))
                assertTrue(Color.green(center) > Color.blue(center))
            } finally {
                direct.recycle()
            }

            val loader = ImageLoader.Builder(fixture.context)
                .allowRgb565(true)
                .components {
                    add(MediaGridRgb565Keyer())
                    add(MediaGridRgb565FetcherFactory(fixture.store))
                }
                .memoryCache {
                    MemoryCache.Builder(fixture.context)
                        .maxSizeBytes(4 * 1024 * 1024)
                        .build()
                }
                .build()
            try {
                val request = ImageRequest.Builder(fixture.context)
                    .data(slot)
                    .memoryCacheKey(slot.cacheKey)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .size(256, 256)
                    .build()
                val first = loader.execute(request) as SuccessResult
                val firstBitmap = (first.drawable as BitmapDrawable).bitmap
                assertEquals(Bitmap.Config.RGB_565, firstBitmap.config)
                assertEquals(DataSource.DISK, first.dataSource)
                assertNotNull(loader.memoryCache?.get(MemoryCache.Key(slot.cacheKey)))

                val second = loader.execute(request) as SuccessResult
                assertEquals(DataSource.MEMORY_CACHE, second.dataSource)
                assertEquals(
                    Bitmap.Config.RGB_565,
                    ((second.drawable as BitmapDrawable).bitmap).config,
                )
            } finally {
                loader.shutdown()
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun samePackSlotsReuseMappingAndOneCorruptSlotDoesNotInvalidateAnother() {
        val fixture = fixture()
        try {
            val first = fixture.store.publish(1, payload(1), source(1))
            val second = fixture.store.publish(2, payload(2), source(2))
            fixture.store.invalidateMappingForTest(0)
            val creationsBefore = fixture.store.mappingCreationsForTest()

            assertEquals(first.generation, fixture.store.readSlot(1, listOf(source(1)))?.generation)
            val creationsAfterFirst = fixture.store.mappingCreationsForTest()
            assertEquals(second.generation, fixture.store.readSlot(2, listOf(source(2)))?.generation)
            assertEquals(creationsBefore + 1, creationsAfterFirst)
            assertEquals(creationsAfterFirst, fixture.store.mappingCreationsForTest())

            corruptActiveMetadata(fixture.store, first)
            assertNull(fixture.store.readSlot(1, listOf(source(1))))
            assertEquals(second.generation, fixture.store.readSlot(2, listOf(source(2)))?.generation)
            assertTrue(fixture.store.validatePayloadCrc(second))
        } finally {
            fixture.close()
        }
    }

    private fun fixture(): Fixture {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "rgb565-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        return Fixture(context, root, MediaGridRgb565PackStore(root))
    }

    private fun payload(value: Int): MediaGridRgb565Payload =
        MediaGridRgb565Payload(ByteArray(MEDIA_GRID_RGB565_PAYLOAD_SIZE) { value.toByte() })

    private fun source(value: Long) = MediaGridRgb565SourceSignature(
        MediaGridRgb565SourceKind.LOCAL_WEBP,
        1_000L + value,
        2_000L + value,
    )

    private fun corruptActiveMetadata(
        store: MediaGridRgb565PackStore,
        slot: MediaGridRgb565Slot,
    ) {
        val address = mediaGridRgb565Address(slot.assetId)
        val offset = address.slotOffset + slot.bankIndex.toLong() * MEDIA_GRID_RGB565_BANK_STRIDE
        RandomAccessFile(store.packFile(slot.packIndex), "rw").use {
            it.seek(offset)
            it.writeInt(0)
        }
        store.invalidateMappingForTest(slot.packIndex)
    }

    private data class Fixture(
        val context: Context,
        val root: File,
        val store: MediaGridRgb565PackStore,
    ) {
        fun close() {
            check(root.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }
}
