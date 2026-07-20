package com.lyco256.llm.data

import androidx.work.WorkInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal enum class MediaGridPreviewValidity {
    VALID,
    MISSING,
    STALE,
    INVALID,
}

data class ExistingMediaGridPreviewScan(
    val localAssetCount: Int,
    val validPreviewCount: Int,
    val targetAssetIds: List<Long>,
) {
    val targetCount: Int get() = targetAssetIds.size
}

data class ExistingMediaGridPreviewScanState(
    val summary: ExistingMediaGridPreviewScan = ExistingMediaGridPreviewScan(0, 0, emptyList()),
    val isScanning: Boolean = false,
    val errorMessage: String? = null,
)

data class ExistingMediaGridPreviewWorkStatus(
    val totalCount: Int = 0,
    val queuedCount: Int = 0,
    val runningCount: Int = 0,
    val succeededCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
    val constraintWaiting: Boolean = false,
) {
    val isFinished: Boolean
        get() = totalCount > 0 && queuedCount == 0 && runningCount == 0
}

/** Pure target selection used by the temporary backfill UI and its unit tests. */
internal fun collectExistingMediaGridPreviewTargets(
    assets: List<AssetEntity>,
    inspect: (assetId: Long, sourceFile: File) -> MediaGridPreviewValidity,
): ExistingMediaGridPreviewScan {
    val localAssets = assets.filter { !it.localPath.isNullOrBlank() }
    var validPreviewCount = 0
    val targets = localAssets.asSequence()
        .sortedBy { it.id }
        .mapNotNull { asset ->
            val source = File(requireNotNull(asset.localPath).trim()).absoluteFile
            if (!source.isFile) return@mapNotNull null
            when (inspect(asset.id, source)) {
                MediaGridPreviewValidity.VALID -> {
                    validPreviewCount++
                    null
                }
                MediaGridPreviewValidity.MISSING,
                MediaGridPreviewValidity.STALE,
                MediaGridPreviewValidity.INVALID,
                -> asset.id
            }
        }
        .toList()
    return ExistingMediaGridPreviewScan(
        localAssetCount = localAssets.size,
        validPreviewCount = validPreviewCount,
        targetAssetIds = targets,
    )
}

internal fun summarizeExistingMediaGridPreviewWork(
    infos: List<WorkInfo>,
): ExistingMediaGridPreviewWorkStatus = summarizeExistingMediaGridPreviewStates(infos.map { it.state })

internal fun summarizeExistingMediaGridPreviewStates(
    states: List<WorkInfo.State>,
): ExistingMediaGridPreviewWorkStatus {
    var queued = 0
    var running = 0
    var succeeded = 0
    var failed = 0
    var cancelled = 0
    var constraintWaiting = false
    states.forEach { state ->
        when (state) {
            WorkInfo.State.ENQUEUED -> {
                queued++
                constraintWaiting = true
            }
            WorkInfo.State.BLOCKED -> queued++
            WorkInfo.State.RUNNING -> running++
            WorkInfo.State.SUCCEEDED -> succeeded++
            WorkInfo.State.FAILED -> failed++
            WorkInfo.State.CANCELLED -> cancelled++
        }
    }
    return ExistingMediaGridPreviewWorkStatus(
        totalCount = states.size,
        queuedCount = queued,
        runningCount = running,
        succeededCount = succeeded,
        failedCount = failed,
        cancelledCount = cancelled,
        constraintWaiting = constraintWaiting,
    )
}

internal fun enqueueExistingMediaGridPreviewWork(
    assetIds: Collection<Long>,
    enqueue: (Collection<Long>, String) -> Unit,
) {
    enqueue(assetIds, WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_TAG)
}

internal fun cancelExistingMediaGridPreviewWork(cancelByTag: (String) -> Unit) {
    cancelByTag(WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_TAG)
}

class ExistingMediaGridPreviewBackfill(
    private val postStorageManager: PostStorageManager,
    private val previewStore: MediaGridPersistentPreviewStore,
    private val previewEnqueuer: WorkManagerMediaGridPreviewEnqueuer,
) {
    val workStatus: Flow<ExistingMediaGridPreviewWorkStatus> =
        previewEnqueuer.observeByTag(WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_TAG)
            .map(::summarizeExistingMediaGridPreviewWork)
            .distinctUntilChanged()

    suspend fun scan(): ExistingMediaGridPreviewScan = withContext(Dispatchers.IO) {
        val assets = postStorageManager.withDatabase { database ->
            database.clipDao().getActiveAssets()
        }
        collectExistingMediaGridPreviewTargets(assets, previewStore::inspect)
    }

    fun enqueue(assetIds: Collection<Long>) {
        enqueueExistingMediaGridPreviewWork(assetIds) { ids, tag -> previewEnqueuer.enqueue(ids, tag) }
    }

    fun cancel() {
        cancelExistingMediaGridPreviewWork(previewEnqueuer::cancelByTag)
    }
}
