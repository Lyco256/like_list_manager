package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lyco256.llm.LikeListManagerApp
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal interface MediaGridRgb565RepairEnqueuer {
    fun enqueue(assetIds: Collection<Long>)
}

internal object NoOpMediaGridRgb565RepairEnqueuer : MediaGridRgb565RepairEnqueuer {
    override fun enqueue(assetIds: Collection<Long>) = Unit
}

internal class WorkManagerMediaGridRgb565RepairEnqueuer(
    context: Context,
) : MediaGridRgb565RepairEnqueuer {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(assetIds: Collection<Long>) {
        assetIds.asSequence()
            .filter { it > 0L }
            .distinct()
            .filter(PENDING_ASSET_IDS::add)
            .chunked(MAX_ASSET_IDS_PER_WORK)
            .forEach { batch ->
                if (batch.isEmpty()) return@forEach
                val request = OneTimeWorkRequestBuilder<MediaGridRgb565RepairWorker>()
                    .setInputData(
                        Data.Builder()
                            .putLongArray(INPUT_ASSET_IDS, batch.toLongArray())
                            .build(),
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresStorageNotLow(true)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                workManager
                    .beginUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                    .enqueue()
            }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "media-grid-rgb565-repair"
        const val INPUT_ASSET_IDS = "asset_ids"
        const val MAX_ASSET_IDS_PER_WORK = 100
        private val PENDING_ASSET_IDS = ConcurrentHashMap.newKeySet<Long>()

        internal fun markFinished(assetId: Long) {
            PENDING_ASSET_IDS.remove(assetId)
        }
    }
}

internal class MediaGridRgb565RepairWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private enum class Outcome {
        COMPLETE,
        PERMANENT_SKIP,
        TRANSIENT_FAILURE,
    }

    private val store = MediaGridRgb565PackStore(appContext.filesDir)
    private val jpegStore = MediaGridPersistentPreviewStore(appContext.filesDir)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? LikeListManagerApp ?: return@withContext Result.failure()
        val assetIds = inputData
            .getLongArray(WorkManagerMediaGridRgb565RepairEnqueuer.INPUT_ASSET_IDS)
            ?.filter { it > 0L }
            ?.distinct()
            .orEmpty()
        val gate = Semaphore(MEDIA_GRID_RGB565_MAX_REPAIRS)
        val outcomes = coroutineScope {
            assetIds.map { assetId ->
                async {
                    gate.withPermit {
                        try {
                            processAsset(app, assetId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: IOException) {
                            Outcome.TRANSIENT_FAILURE
                        }
                    }
                }
            }.awaitAll()
        }
        assetIds.zip(outcomes).forEach { (assetId, outcome) ->
            if (outcome != Outcome.TRANSIENT_FAILURE ||
                runAttemptCount >= MAX_RETRY_ATTEMPTS
            ) {
                WorkManagerMediaGridRgb565RepairEnqueuer.markFinished(assetId)
            }
        }
        if (outcomes.any { it == Outcome.TRANSIENT_FAILURE }) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        } else {
            Result.success()
        }
    }

    private suspend fun processAsset(
        app: LikeListManagerApp,
        assetId: Long,
    ): Outcome {
        val asset = app.container.postStorageManager.withDatabase {
            it.clipDao().getAsset(assetId)
        } ?: return Outcome.PERMANENT_SKIP
        val local = asset.localPath
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::File)
            ?.absoluteFile
            ?.takeIf(File::isFile)
            ?: return Outcome.PERMANENT_SKIP
        val sources = mediaGridRgb565CurrentSources(assetId, local, jpegStore)
        if (store.readSlot(assetId, sources.map { it.signature }) != null) return Outcome.COMPLETE
        val selected = sources.firstOrNull() ?: return Outcome.PERMANENT_SKIP
        val payload = decodeMediaGridRgb565Payload(selected.file)
            ?: return Outcome.PERMANENT_SKIP
        val unchangedFile = selected.file.isFile &&
            selected.file.length() == selected.signature.length &&
            selected.file.lastModified() == selected.signature.lastModified
        val unchangedAsset = app.container.postStorageManager.withDatabase {
            it.clipDao().getAsset(assetId)?.localPath?.let(::File)?.absoluteFile == local
        }
        if (!unchangedFile || !unchangedAsset) return Outcome.PERMANENT_SKIP
        store.publish(assetId, payload, selected.signature)
        MediaGridPreviewNotifier.notifyPreviewChanged(assetId)
        return Outcome.COMPLETE
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 2
    }
}

internal data class MediaGridRgb565SourceFile(
    val file: File,
    val signature: MediaGridRgb565SourceSignature,
)

internal fun mediaGridRgb565CurrentSources(
    assetId: Long,
    localFile: File?,
    jpegStore: MediaGridPersistentPreviewStore,
): List<MediaGridRgb565SourceFile> {
    if (assetId <= 0L) return emptyList()
    val local = localFile?.absoluteFile?.takeIf(File::isFile)
    val jpeg = jpegStore.previewFile(assetId).absoluteFile
    val validJpeg = jpeg.takeIf {
        isValidMediaGridJpeg(it) &&
            (local == null || it.lastModified() >= local.lastModified())
    }
    return buildList {
        validJpeg?.let {
            add(
                MediaGridRgb565SourceFile(
                    it,
                    MediaGridRgb565SourceSignature(
                        MediaGridRgb565SourceKind.PERSISTENT_JPEG,
                        it.length(),
                        it.lastModified(),
                    ),
                ),
            )
        }
        local?.let {
            add(
                MediaGridRgb565SourceFile(
                    it,
                    MediaGridRgb565SourceSignature(
                        MediaGridRgb565SourceKind.LOCAL_WEBP,
                        it.length(),
                        it.lastModified(),
                    ),
                ),
            )
        }
    }
}

internal fun isValidMediaGridJpeg(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return options.outWidth == MEDIA_GRID_RGB565_SIZE &&
        options.outHeight == MEDIA_GRID_RGB565_SIZE &&
        options.outMimeType == "image/jpeg"
}

internal fun decodeMediaGridRgb565Payload(source: File): MediaGridRgb565Payload? {
    if (!source.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = mediaGridPreviewInSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
    return try {
        if (decoded.width == MEDIA_GRID_RGB565_SIZE &&
            decoded.height == MEDIA_GRID_RGB565_SIZE &&
            decoded.config == Bitmap.Config.RGB_565
        ) {
            copyMediaGridRgb565Payload(decoded)
        } else {
            createMediaGridRgb565Payload(decoded)
        }
    } finally {
        decoded.recycle()
    }
}

internal const val MEDIA_GRID_RGB565_MAX_REPAIRS = 2
