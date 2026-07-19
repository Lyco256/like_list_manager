package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class MediaGridThumbnailSource(
    val assetId: Long, val mediaKey: String, val localPath: String?,
    val previewUrl: String?, val remoteUrl: String?, val size: Long = 0L, val modified: Long = 0L,
    val downloadState: String? = null,
)

interface MediaGridThumbnailStoreGateway {
    suspend fun getOrCreate(source: MediaGridThumbnailSource, allowRemote: Boolean = true): File

    /** Returns the identity used by getOrCreate, without opening or creating the output file. */
    fun cacheKey(source: MediaGridThumbnailSource, allowRemote: Boolean = true): String? =
        mediaGridThumbnailCacheKey(source, allowRemote)

    /** Checks only the already-generated cache file. It must not generate or fetch media. */
    fun findCached(source: MediaGridThumbnailSource, allowRemote: Boolean = true): File? = null

    fun invalidate(file: File)
}

internal fun mediaGridThumbnailCacheKey(source: MediaGridThumbnailSource, allowRemote: Boolean): String? {
    val input = source.localPath?.let(::File)?.takeIf { it.isFile }
    val inputName = input?.absolutePath ?: if (allowRemote) {
        source.previewUrl ?: source.remoteUrl
    } else {
        null
    } ?: return null
    val actualSize = input?.length() ?: source.size
    val actualModified = input?.lastModified() ?: source.modified
    return MessageDigest.getInstance("SHA-256")
        .digest("${source.assetId}|${source.mediaKey}|$inputName|$actualSize|$actualModified".toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class MediaGridThumbnailStore(
    context: Context,
) : MediaGridThumbnailStoreGateway {
    private val directory = File(context.cacheDir, "media_grid_thumbnails").apply { mkdirs() }

    private data class CacheLocation(
        val key: String,
        val output: File,
        val input: File?,
        val remoteUrls: List<String>,
    )

    override suspend fun getOrCreate(source: MediaGridThumbnailSource, allowRemote: Boolean): File = withContext(Dispatchers.IO) {
        val location = cacheLocation(source, allowRemote) ?: error("No media source")
        if (location.output.isFile && location.output.length() > 0L) return@withContext location.output
        val temporary = File(directory, ".${location.key}.${Thread.currentThread().id}.tmp")
        try {
            val bitmap = decode(location.input, location.remoteUrls)
            val square = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
            Canvas(square).drawColor(Color.BLACK)
            val scale = maxOf(256f / bitmap.width, 256f / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            Canvas(square).drawBitmap(bitmap, null, RectF((256f - w) / 2f, (256f - h) / 2f, (256f + w) / 2f, (256f + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
            check(square.compress(Bitmap.CompressFormat.JPEG, 60, temporary.outputStream()))
            if (!temporary.renameTo(location.output)) {
                temporary.copyTo(location.output, overwrite = true); temporary.delete()
            }
            bitmap.recycle(); square.recycle()
            location.output
        } catch (t: Throwable) {
            temporary.delete()
            location.output.delete()
            throw t
        }
    }

    override fun cacheKey(source: MediaGridThumbnailSource, allowRemote: Boolean): String? =
        cacheLocation(source, allowRemote)?.key

    override fun findCached(source: MediaGridThumbnailSource, allowRemote: Boolean): File? =
        cacheLocation(source, allowRemote)?.output?.takeIf { it.isFile && it.length() > 0L }

    override fun invalidate(file: File) { file.delete() }

    fun cached(source: MediaGridThumbnailSource): File? = findCached(source, allowRemote = true)

    private fun cacheLocation(source: MediaGridThumbnailSource, allowRemote: Boolean): CacheLocation? {
        val input = source.localPath?.let(::File)?.takeIf { it.isFile }
        val key = mediaGridThumbnailCacheKey(source, allowRemote) ?: return null
        return CacheLocation(
            key = key,
            output = File(directory, "$key.jpg"),
            input = input,
            remoteUrls = if (input == null && allowRemote) listOfNotNull(source.previewUrl, source.remoteUrl) else emptyList(),
        )
    }

    private fun decode(file: File?, urls: List<String>): Bitmap {
        if (file != null) return decodeStream(file.inputStream())
        var last: Throwable? = null
        for (url in urls) try {
            val source = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000 }.inputStream
            return decodeStream(source)
        } catch (t: Throwable) { last = t }
        throw last ?: error("No source")
    }

    private fun decodeStream(source: java.io.InputStream): Bitmap {
        BufferedInputStream(source).use { stream ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream.mark(1024 * 1024)
            BitmapFactory.decodeStream(stream, null, bounds)
            stream.reset()
            val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 256)
            return BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: error("Unable to decode thumbnail")
        }
    }

    private fun calculateInSampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= target && h / (sample * 2) >= target) sample *= 2
        return sample
    }
}
