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
)

class MediaGridThumbnailStore(context: Context) {
    private val directory = File(context.cacheDir, "media_grid_thumbnails").apply { mkdirs() }

    suspend fun getOrCreate(source: MediaGridThumbnailSource, allowRemote: Boolean = true): File = withContext(Dispatchers.IO) {
        val input = source.localPath?.let(::File)?.takeIf { it.isFile }
        val inputName = input?.absolutePath ?: if (allowRemote) (source.previewUrl ?: source.remoteUrl) else null
            ?: error("No media source")
        val actualSize = input?.length() ?: source.size
        val actualModified = input?.lastModified() ?: source.modified
        val key = sha256("${source.assetId}|${source.mediaKey}|$inputName|$actualSize|$actualModified")
        val output = File(directory, "$key.jpg")
        if (output.isFile && output.length() > 0L) return@withContext output
        val temporary = File(directory, ".${key}.${Thread.currentThread().id}.tmp")
        try {
            val bitmap = decode(input, if (allowRemote) listOfNotNull(source.previewUrl, source.remoteUrl) else emptyList())
            val square = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
            Canvas(square).drawColor(Color.BLACK)
            val scale = maxOf(256f / bitmap.width, 256f / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            Canvas(square).drawBitmap(bitmap, null, RectF((256f - w) / 2f, (256f - h) / 2f, (256f + w) / 2f, (256f + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
            check(square.compress(Bitmap.CompressFormat.JPEG, 60, temporary.outputStream()))
            if (!temporary.renameTo(output)) {
                temporary.copyTo(output, overwrite = true); temporary.delete()
            }
            bitmap.recycle(); square.recycle()
            output
        } catch (t: Throwable) {
            temporary.delete()
            output.delete()
            throw t
        }
    }

    fun invalidate(file: File) { file.delete() }

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
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
