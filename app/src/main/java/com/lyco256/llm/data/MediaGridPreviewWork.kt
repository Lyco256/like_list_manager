package com.lyco256.llm.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface MediaGridPreviewEnqueuer {
    fun enqueue(assetIds: Collection<Long>)
}

object NoOpMediaGridPreviewEnqueuer : MediaGridPreviewEnqueuer {
    override fun enqueue(assetIds: Collection<Long>) = Unit
}

class WorkManagerMediaGridPreviewEnqueuer(context: Context) : MediaGridPreviewEnqueuer {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(assetIds: Collection<Long>) {
        assetIds.distinct().chunked(MAX_ASSET_IDS_PER_WORK).forEach { batch ->
            if (batch.isEmpty()) return@forEach
            val request = OneTimeWorkRequestBuilder<MediaGridPreviewWorker>()
                .setInputData(Data.Builder().putLongArray(INPUT_ASSET_IDS, batch.toLongArray()).build())
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
        const val UNIQUE_WORK_NAME = "media-grid-persistent-jpeg-preview"
        const val INPUT_ASSET_IDS = "asset_ids"
        const val MAX_ASSET_IDS_PER_WORK = 100
    }
}
