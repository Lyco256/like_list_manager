package com.lyco256.llm.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lyco256.llm.LikeListManagerApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MediaGridPreviewWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val store = MediaGridPersistentPreviewStore(appContext.filesDir)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? LikeListManagerApp ?: return@withContext Result.failure()
        val assetIds = inputData.getLongArray(WorkManagerMediaGridPreviewEnqueuer.INPUT_ASSET_IDS) ?: LongArray(0)
        for (assetId in assetIds) {
            try {
                if (processAsset(app, assetId) == MediaGridPreviewGenerationResult.GENERATED) {
                    MediaGridPreviewNotifier.notifyPreviewChanged(assetId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                return@withContext if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
        Result.success()
    }

    private suspend fun processAsset(app: LikeListManagerApp, assetId: Long): MediaGridPreviewGenerationResult? {
        val initial = app.container.postStorageManager.withDatabase { database ->
            database.clipDao().getAsset(assetId)
        } ?: return null
        val sourcePath = initial.localPath?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val source = File(sourcePath).absoluteFile
        if (!source.isFile) return null

        return store.generate(assetId, source) {
            app.container.postStorageManager.withDatabase { database ->
                database.clipDao().getAsset(assetId)?.localPath?.let { File(it).absoluteFile } == source
            }
        }
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
