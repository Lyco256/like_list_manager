package com.lyco256.llm.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.Observer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.TimeUnit

interface MediaGridPreviewEnqueuer {
    fun enqueue(assetIds: Collection<Long>, tag: String? = null)
}

object NoOpMediaGridPreviewEnqueuer : MediaGridPreviewEnqueuer {
    override fun enqueue(assetIds: Collection<Long>, tag: String?) = Unit
}

class WorkManagerMediaGridPreviewEnqueuer(context: Context) : MediaGridPreviewEnqueuer {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(assetIds: Collection<Long>, tag: String?) {
        mediaGridPreviewAssetBatches(assetIds).forEach { batch ->
            if (batch.isEmpty()) return@forEach
            val requestBuilder = OneTimeWorkRequestBuilder<MediaGridPreviewWorker>()
                .setInputData(Data.Builder().putLongArray(INPUT_ASSET_IDS, batch.toLongArray()).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            tag?.let(requestBuilder::addTag)
            val request = requestBuilder.build()
            workManager
                .beginUniqueWork(uniqueWorkName(tag), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .enqueue()
        }
    }

    fun cancelByTag(tag: String) {
        workManager.cancelAllWorkByTag(tag)
    }

    fun observeByTag(tag: String): Flow<List<WorkInfo>> = callbackFlow {
        val observer = Observer<List<WorkInfo>> { infos -> trySend(infos.orEmpty()) }
        val liveData = workManager.getWorkInfosByTagLiveData(tag)
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }

    private fun uniqueWorkName(tag: String?): String =
        if (tag == EXISTING_PREVIEW_BACKFILL_TAG) EXISTING_PREVIEW_BACKFILL_UNIQUE_WORK_NAME else UNIQUE_WORK_NAME

    companion object {
        const val UNIQUE_WORK_NAME = "media-grid-persistent-jpeg-preview"
        const val EXISTING_PREVIEW_BACKFILL_TAG = "media-grid-existing-preview-backfill"
        const val EXISTING_PREVIEW_BACKFILL_UNIQUE_WORK_NAME = "media-grid-existing-preview-backfill"
        const val INPUT_ASSET_IDS = "asset_ids"
        const val MAX_ASSET_IDS_PER_WORK = 100
    }
}

internal fun mediaGridPreviewAssetBatches(assetIds: Collection<Long>): List<List<Long>> =
    assetIds.distinct().chunked(WorkManagerMediaGridPreviewEnqueuer.MAX_ASSET_IDS_PER_WORK)
