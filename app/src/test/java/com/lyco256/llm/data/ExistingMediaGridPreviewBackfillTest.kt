package com.lyco256.llm.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingMediaGridPreviewBackfillTest {
    @Test
    fun selectsOnlyExistingLocalMissingStaleAndInvalidAssetsInIdOrder() {
        val root = File("build/tmp/existing-preview-target-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1)) }
        try {
            val assets = listOf(
                asset(30L, source),
                asset(10L, source),
                asset(20L, source),
                asset(40L, null),
                asset(50L, File(root, "missing.jpg")),
            )
            val result = collectExistingMediaGridPreviewTargets(assets) { assetId, _ ->
                when (assetId) {
                    10L -> MediaGridPreviewValidity.VALID
                    20L -> MediaGridPreviewValidity.STALE
                    30L -> MediaGridPreviewValidity.INVALID
                    else -> error("source missing is not inspected")
                }
            }

            assertEquals(4, result.localAssetCount)
            assertEquals(1, result.validPreviewCount)
            assertEquals(listOf(20L, 30L), result.targetAssetIds)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun batchesAreDistinctStableAndNeverExceedTheWorkManagerDataLimit() {
        val ids = (1L..205L).toList() + listOf(3L, 1L)
        val batches = mediaGridPreviewAssetBatches(ids)

        assertEquals(3, batches.size)
        assertEquals(100, batches[0].size)
        assertEquals(100, batches[1].size)
        assertEquals(5, batches[2].size)
        assertEquals((1L..205L).toList(), batches.flatten())
        assertTrue(batches.all { it.size <= WorkManagerMediaGridPreviewEnqueuer.MAX_ASSET_IDS_PER_WORK })
    }

    @Test
    fun statusMarksQueuedWorkAsConstraintWaitingAndSeparatesFinishedStates() {
        val status = summarizeExistingMediaGridPreviewStates(
            listOf(
                androidx.work.WorkInfo.State.ENQUEUED,
                androidx.work.WorkInfo.State.RUNNING,
                androidx.work.WorkInfo.State.SUCCEEDED,
                androidx.work.WorkInfo.State.FAILED,
                androidx.work.WorkInfo.State.CANCELLED,
            ),
        )

        assertEquals(5, status.totalCount)
        assertEquals(1, status.queuedCount)
        assertEquals(1, status.runningCount)
        assertEquals(1, status.succeededCount)
        assertEquals(1, status.failedCount)
        assertEquals(1, status.cancelledCount)
        assertTrue(status.constraintWaiting)
    }

    @Test
    fun backfillUsesASeparateDedicatedTagAndUniqueWorkName() {
        assertEquals(
            "media-grid-existing-preview-backfill",
            WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_TAG,
        )
        assertEquals(
            "media-grid-existing-preview-backfill",
            WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_UNIQUE_WORK_NAME,
        )
    }

    @Test
    fun enqueueAndStopUseOnlyTheDedicatedBackfillTag() {
        var enqueueTag: String? = null
        var cancelledTag: String? = null
        enqueueExistingMediaGridPreviewWork(listOf(7L)) { _, tag -> enqueueTag = tag }
        cancelExistingMediaGridPreviewWork { tag -> cancelledTag = tag }

        assertEquals(WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_TAG, enqueueTag)
        assertEquals(WorkManagerMediaGridPreviewEnqueuer.EXISTING_PREVIEW_BACKFILL_TAG, cancelledTag)
        assertTrue(enqueueTag != WorkManagerMediaGridPreviewEnqueuer.UNIQUE_WORK_NAME)
    }

    private fun asset(id: Long, source: File?): AssetEntity = AssetEntity(
        id = id,
        clipId = id,
        mediaKey = "asset-$id",
        type = "photo",
        remoteUrl = null,
        previewUrl = null,
        localPath = source?.absolutePath,
        createdAt = "2026-07-20T00:00:00Z",
    )

}
