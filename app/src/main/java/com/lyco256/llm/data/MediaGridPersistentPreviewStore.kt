package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal const val MEDIA_GRID_PREVIEW_SIZE = 256
internal const val MEDIA_GRID_PREVIEW_QUALITY = 80
internal val mediaGridPreviewPublishSemaphore = Semaphore(1)
internal val mediaGridPreviewGenerationSemaphore = Semaphore(1)

internal suspend fun <T> withMediaGridPreviewPublishLock(block: suspend () -> T): T {
    mediaGridPreviewPublishSemaphore.acquire()
    return try {
        block()
    } finally {
        mediaGridPreviewPublishSemaphore.release()
    }
}

enum class MediaGridPreviewGenerationResult {
    GENERATED,
    SKIPPED_VALID,
    SKIPPED_SOURCE,
    SKIPPED_STALE_ASSET,
}

internal data class MediaGridPreviewSourceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Owns only the persistent JPEG output; it never changes the source image or Room data. */
class MediaGridPersistentPreviewStore(
    private val filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val previewDirectory: File
        get() = File(filesDir, "media_grid_previews/v1")

    fun previewFile(assetId: Long): File = safeOutputFile("$assetId.jpg")

    suspend fun deletePreview(assetId: Long): Boolean = withMediaGridPreviewPublishLock {
        val existed = previewFile(assetId).exists()
        val deleted = deletePreviewUnsafe(assetId)
        if (existed && deleted) MediaGridPreviewNotifier.notifyPreviewChanged(assetId)
        deleted
    }

    suspend fun <T> withPublishLock(block: suspend () -> T): T = withMediaGridPreviewPublishLock(block)

    suspend fun generate(
        assetId: Long,
        sourceFile: File,
        isCurrentAsset: suspend () -> Boolean,
    ): MediaGridPreviewGenerationResult = mediaGridPreviewGenerationSemaphore.withPermit {
        withContext(ioDispatcher) {
        ensureActive()
        val source = sourceFile.absoluteFile
        if (!source.isFile) return@withContext MediaGridPreviewGenerationResult.SKIPPED_SOURCE

        val target = previewFile(assetId)
        if (isValidJpeg(target) && target.lastModified() > source.lastModified()) {
            return@withContext MediaGridPreviewGenerationResult.SKIPPED_VALID
        }

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return@withContext MediaGridPreviewGenerationResult.SKIPPED_SOURCE
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = mediaGridPreviewInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
            ?: return@withContext MediaGridPreviewGenerationResult.SKIPPED_SOURCE
        var output: Bitmap? = null
        var temporary: File? = null
        try {
            ensureActive()
            if (decoded.width <= 0 || decoded.height <= 0) {
                return@withContext MediaGridPreviewGenerationResult.SKIPPED_SOURCE
            }
            output = Bitmap.createBitmap(MEDIA_GRID_PREVIEW_SIZE, MEDIA_GRID_PREVIEW_SIZE, Bitmap.Config.ARGB_8888)
            val crop = mediaGridPreviewSourceRect(decoded.width, decoded.height)
            Canvas(output!!).apply {
                drawColor(Color.BLACK)
                drawBitmap(
                    decoded,
                    Rect(crop.left, crop.top, crop.right, crop.bottom),
                    Rect(0, 0, MEDIA_GRID_PREVIEW_SIZE, MEDIA_GRID_PREVIEW_SIZE),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }

            previewDirectory.mkdirs()
            check(previewDirectory.isDirectory) { "preview directory is unavailable" }
            temporary = safeOutputFile(".$assetId.${UUID.randomUUID()}.tmp")
            temporary!!.outputStream().use { outputStream ->
                check(output!!.compress(Bitmap.CompressFormat.JPEG, MEDIA_GRID_PREVIEW_QUALITY, outputStream)) {
                    "JPEG compression failed"
                }
                outputStream.flush()
                outputStream.fd.sync()
            }
            ensureActive()
            val publishResult = withMediaGridPreviewPublishLock {
                if (!isCurrentAsset()) {
                    MediaGridPreviewGenerationResult.SKIPPED_STALE_ASSET
                } else {
                    replaceAtomically(temporary!!, target)
                    MediaGridPreviewGenerationResult.GENERATED
                }
            }
            if (publishResult == MediaGridPreviewGenerationResult.SKIPPED_STALE_ASSET) return@withContext publishResult
            temporary = null
            publishResult
        } finally {
            temporary?.delete()
            output?.recycle()
            decoded.recycle()
        }
        }
    }

    internal fun inspect(assetId: Long, sourceFile: File): MediaGridPreviewValidity {
        val target = previewFile(assetId)
        if (!target.isFile) return MediaGridPreviewValidity.MISSING
        if (!isValidJpeg(target)) return MediaGridPreviewValidity.INVALID
        return if (target.lastModified() > sourceFile.lastModified()) {
            MediaGridPreviewValidity.VALID
        } else {
            MediaGridPreviewValidity.STALE
        }
    }

    private fun isValidJpeg(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth == MEDIA_GRID_PREVIEW_SIZE &&
            options.outHeight == MEDIA_GRID_PREVIEW_SIZE &&
            options.outMimeType == "image/jpeg"
    }

    internal fun deletePreviewUnsafe(assetId: Long): Boolean {
        val target = previewFile(assetId)
        return !target.exists() || target.delete()
    }

    private fun replaceAtomically(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            throw IOException("JPEG replacement failed", error)
        }
    }

    private fun safeOutputFile(name: String): File {
        val directory = previewDirectory
        val file = File(directory, name)
        val canonicalDirectory = directory.canonicalFile
        check(file.canonicalFile.parentFile == canonicalDirectory) { "preview path escaped its directory" }
        return file
    }
}

internal fun mediaGridPreviewInSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (
        minOf(width / (sample * 2), height / (sample * 2)) >= MEDIA_GRID_PREVIEW_SIZE
    ) {
        sample *= 2
    }
    return sample
}

internal fun mediaGridPreviewSourceRect(width: Int, height: Int): MediaGridPreviewSourceRect {
    val cropSize = minOf(width, height)
    val left = (width - cropSize) / 2
    val top = (height - cropSize) / 2
    return MediaGridPreviewSourceRect(left, top, left + cropSize, top + cropSize)
}
