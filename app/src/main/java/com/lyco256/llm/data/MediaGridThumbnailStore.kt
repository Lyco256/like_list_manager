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

class MediaGridThumbnailStore(
    context: Context,
    private val settings: MediaGridBenchmarkSettings = MediaGridBenchmarkSettings(),
    private val metrics: MediaGridBenchmarkMetrics = MediaGridBenchmarkMetrics.forSettings(settings),
) {
    private val directory = File(
        context.cacheDir,
        if (settings.enabled && settings.mode != MediaGridBenchmarkMode.CACHED_UI) "media_grid_thumbnails_generated" else "media_grid_thumbnails",
    ).apply { mkdirs() }

    fun resetGeneratedResults() { if (settings.enabled && settings.mode != MediaGridBenchmarkMode.CACHED_UI) directory.deleteRecursively(); directory.mkdirs() }

    suspend fun getOrCreate(source: MediaGridThumbnailSource, allowRemote: Boolean = settings.networkAllowed): File = withContext(Dispatchers.IO) {
        check(!settings.enabled || !allowRemote) { "Benchmark media path cannot access network" }
        val input = source.localPath?.let(::File)?.takeIf { it.isFile }
        val inputName = input?.absolutePath ?: if (allowRemote) (source.previewUrl ?: source.remoteUrl) else null
            ?: error("No media source")
        val actualSize = input?.length() ?: source.size
        val actualModified = input?.lastModified() ?: source.modified
        val key = sha256("${source.assetId}|${source.mediaKey}|$inputName|$actualSize|$actualModified")
        val output = File(directory, "$key.jpg")
        metrics.count("cacheLookup")
        val cached = metrics.trace("MediaGridCacheLookup") { output.isFile && output.length() > 0L }
        if (cached) return@withContext output
        val temporary = File(directory, ".${key}.${Thread.currentThread().id}.tmp")
        try {
            metrics.count("sourceRead")
            val bitmap = metrics.trace("MediaGridSourceDecode") { decode(input, if (allowRemote) listOfNotNull(source.previewUrl, source.remoteUrl) else emptyList()) }
            metrics.count("sourceDecode")
            val square = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
            Canvas(square).drawColor(Color.BLACK)
            val scale = maxOf(256f / bitmap.width, 256f / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            metrics.trace("MediaGridThumbnailResize") {
                Canvas(square).drawBitmap(bitmap, null, RectF((256f - w) / 2f, (256f - h) / 2f, (256f + w) / 2f, (256f + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            metrics.count("resize")
            metrics.trace("MediaGridThumbnailEncode") { check(square.compress(Bitmap.CompressFormat.JPEG, 60, temporary.outputStream())) }
            metrics.count("encode")
            metrics.trace("MediaGridThumbnailWrite") {
                if (!temporary.renameTo(output)) {
                    temporary.copyTo(output, overwrite = true); temporary.delete()
                }
            }
            metrics.count("write")
            bitmap.recycle(); square.recycle()
            output
        } catch (t: Throwable) {
            temporary.delete()
            output.delete()
            throw t
        }
    }

    fun invalidate(file: File) { file.delete() }

    fun cached(source: MediaGridThumbnailSource): File? {
        val input = source.localPath?.let(::File)
        val inputName = input?.absolutePath ?: source.localPath ?: return null
        val actualSize = input?.takeIf { it.isFile }?.length() ?: source.size
        val actualModified = input?.takeIf { it.isFile }?.lastModified() ?: source.modified
        val key = sha256("${source.assetId}|${source.mediaKey}|$inputName|$actualSize|$actualModified")
        val output = File(directory, "$key.jpg")
        metrics.count("cacheLookup")
        val exact = metrics.trace("MediaGridCacheLookup") { output.takeIf { it.isFile && it.length() > 0L } }
        return exact
    }

    private fun decode(file: File?, urls: List<String>): Bitmap {
        if (file != null) return metrics.trace("MediaGridSourceRead") { decodeStream(file.inputStream()) }
        var last: Throwable? = null
        for (url in urls) try {
            val source = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000 }.inputStream
            return metrics.trace("MediaGridSourceRead") { decodeStream(source) }
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
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
