package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class MediaGridRgb565Keyer : Keyer<MediaGridRgb565Slot> {
    override fun key(data: MediaGridRgb565Slot, options: Options): String = data.cacheKey
}

internal class MediaGridRgb565FetcherFactory(
    private val store: MediaGridRgb565BitmapReader,
    private val permits: Semaphore = Semaphore(MEDIA_GRID_RGB565_MAX_FETCHES),
) : Fetcher.Factory<MediaGridRgb565Slot> {
    override fun create(
        data: MediaGridRgb565Slot,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher = MediaGridRgb565Fetcher(data, options, store, permits)
}

internal class MediaGridRgb565Fetcher(
    private val slot: MediaGridRgb565Slot,
    private val options: Options,
    private val store: MediaGridRgb565BitmapReader,
    private val permits: Semaphore,
) : Fetcher {
    override suspend fun fetch(): FetchResult = permits.withPermit {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            var bitmap: Bitmap? = null
            try {
                bitmap = store.readBitmap(slot)
                currentCoroutineContext().ensureActive()
                check(bitmap.config == Bitmap.Config.RGB_565)
                check(bitmap.width == MEDIA_GRID_RGB565_SIZE)
                check(bitmap.height == MEDIA_GRID_RGB565_SIZE)
                DrawableResult(
                    drawable = BitmapDrawable(options.context.resources, bitmap),
                    isSampled = false,
                    dataSource = DataSource.DISK,
                ).also {
                    bitmap = null
                }
            } finally {
                bitmap?.recycle()
            }
        }
    }
}

internal const val MEDIA_GRID_RGB565_MAX_FETCHES = 4
