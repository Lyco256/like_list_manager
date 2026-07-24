package com.lyco256.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.lyco256.llm.data.MediaGridImageSourceKind
import com.lyco256.llm.data.MediaGridPreparedCandidate
import com.lyco256.llm.data.MediaGridPreparedImage

@RunWith(AndroidJUnit4::class)
class MediaGridSteadyLoadControllerIntegrationTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun frame(count: Int): MediaGridFrameData {
        val entries = (0 until count).map { index ->
            MediaGridEntry(
                entryId = index.toLong(), clipId = index.toLong(), assetId = index.toLong(),
                mediaKey = "controller-test-$index", mediaIndex = 0, type = "photo",
                displayUrl = "https://example.test/$index.jpg", downloadState = "downloaded",
                localPath = null, xCreatedAt = "2026-07-22T00:00:00Z", likeCount = null,
            )
        }
        return buildMediaGridFrameData(
            entries, ClassifiedSortState(), 4,
            MediaGridDataKey(1, 1, TweetFilterState(), ClassifiedSortState()),
        )
    }

    private fun anchor(frame: MediaGridFrameData) = MediaGridViewportAnchor(
        frame.key, 0, 3, intArrayOf(0, 1, 2, 3), 400, 600, 100, 4,
    )

    private class FakePreparer(
        private val prepared: Map<Long, MediaGridPreparedImage>,
        private val failuresBeforeSuccess: Int = 0,
    ) : MediaGridMetadataPreparer {
        private val attempts = AtomicInteger()
        override suspend fun prepareCellOrNull(frame: MediaGridFrameData, itemIndex: Int, cellSizePx: Int): MediaGridPreparedImage? {
            if (attempts.incrementAndGet() <= failuresBeforeSuccess) return null
            return prepared[(frame.items[itemIndex] as MediaGridCellItem).entry.assetId]
        }
    }

    private class FakeBitmapGateway(
        initialCached: Set<String>,
        private val failedKeys: Set<String> = emptySet(),
    ) : MediaGridBitmapGateway {
        private val cached = initialCached.toMutableSet()
        var requests = 0
            private set
        override fun isCached(candidate: MediaGridPreparedCandidate): Boolean = candidate.cacheKey in cached
        override suspend fun load(candidate: MediaGridPreparedCandidate): Boolean {
            requests++
            if (candidate.cacheKey in failedKeys) return false
            cached += candidate.cacheKey
            return true
        }
        fun evict(cacheKey: String) { cached.remove(cacheKey) }
    }

    private fun prepared(frame: MediaGridFrameData, assetId: Long): MediaGridPreparedImage =
        MediaGridPreparedImage(
            frame.key,
            assetId,
            listOf(
                MediaGridPreparedCandidate(
                    kind = MediaGridImageSourceKind.Rgb565Pack,
                    requestData = assetId,
                    sourceIdentity = "source-$assetId",
                    cacheKey = "cache-$assetId",
                    width = 256,
                    height = 256,
                ),
            ),
            itemIndex = assetId.toInt(),
        )

    private fun preparedWithFallback(frame: MediaGridFrameData, assetId: Long): MediaGridPreparedImage =
        prepared(frame, assetId).copy(
            candidates = listOf(
                MediaGridPreparedCandidate(
                    kind = MediaGridImageSourceKind.Rgb565Pack,
                    requestData = assetId,
                    sourceIdentity = "fallback-first-$assetId",
                    cacheKey = "fallback-first-$assetId",
                    width = 256,
                    height = 256,
                ),
                MediaGridPreparedCandidate(
                    kind = MediaGridImageSourceKind.Local,
                    requestData = assetId,
                    sourceIdentity = "fallback-local-$assetId",
                    cacheKey = "fallback-local-$assetId",
                    width = 256,
                    height = 256,
                ),
            ),
        )

    private fun await(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline && !condition()) Thread.yield()
        assertTrue("controller did not reach expected state", condition())
    }

    @Test
    fun cacheHitPreparedAssetDoesNotBecomeQueueLessPending() = runBlocking {
        val frame = frame(1)
        val prepared = prepared(frame, 0L)
        val gateway = FakeBitmapGateway(setOf("cache-0"))
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(mapOf(0L to prepared)), loader, { _, _ -> }, gateway,
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await { controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Ready }
            val snapshot = controller.stateSnapshot()
            assertEquals(MediaGridCellLoadStatus.Ready, snapshot.cells[0L]?.status)
            assertEquals(0, gateway.requests)
            assertTrue(mediaGridControllerStateViolations(snapshot, buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId).isEmpty())
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun metadataNullRetriesAndThenFailsWithoutMetadataComplete() = runBlocking {
        val frame = frame(1)
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(emptyMap(), failuresBeforeSuccess = 3), loader, { _, _ -> }, FakeBitmapGateway(emptySet()),
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await { controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Failed }
            val snapshot = controller.stateSnapshot()
            assertEquals(QueueTaskStatus.Failed, snapshot.records[0L]?.metadataStatus)
            assertTrue(snapshot.records[0L]?.let { it.metadataAttempts >= 3 } == true)
            assertTrue(0L !in snapshot.metadata)
            assertTrue(mediaGridControllerStateViolations(snapshot, buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId).none { it.contains("metadata complete") })
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun cacheEvictionOnVisibleAssetRequeuesUrgentWorkOnce() = runBlocking {
        val frame = frame(1)
        val gateway = FakeBitmapGateway(setOf("cache-0"))
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(mapOf(0L to prepared(frame, 0L))), loader, { _, _ -> }, gateway,
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await { controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Ready }
            gateway.evict("cache-0")
            controller.resume()
            await { gateway.requests == 1 && controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Ready }
            assertConsistentState(controller.stateSnapshot(), buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId)
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun failedCandidateFallsBackToNextCandidateWithoutLosingToken() = runBlocking {
        val frame = frame(1)
        val fallback = preparedWithFallback(frame, 0L)
        val gateway = FakeBitmapGateway(emptySet(), failedKeys = setOf("fallback-first-0"))
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(mapOf(0L to fallback)), loader, { _, _ -> }, gateway,
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await { controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Ready }
            assertEquals(2, gateway.requests)
            assertEquals(1, controller.stateSnapshot().cells[0L]?.candidateIndex)
            assertConsistentState(controller.stateSnapshot(), buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId)
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun mixedCacheAndMissAssetsUseCacheWithoutRequestsAndLoadTheRest() = runBlocking {
        val frame = frame(32)
        val prepared = (0L until 32L).associateWith { prepared(frame, it) }
        val preloaded = prepared.filterKeys { it % 2L == 0L }.values
            .flatMap { image -> image.candidates.map { it.cacheKey } }.toSet()
        val gateway = FakeBitmapGateway(preloaded)
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(prepared), loader, { _, _ -> }, gateway,
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await { controller.stateSnapshot().cells.size == 32 && controller.stateSnapshot().cells.values.all { it.status == MediaGridCellLoadStatus.Ready } }
            val snapshot = controller.stateSnapshot()
            assertTrue(gateway.requests in 1..31)
            assertTrue(snapshot.cells.values.none { it.status == MediaGridCellLoadStatus.Pending })
            assertConsistentState(snapshot, buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId)
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun threeHundredNormalAssetsReachTerminalStateWithoutPersistentPending() = runBlocking {
        val frame = frame(300)
        val prepared = (0L until 300L).associateWith { prepared(frame, it) }
        val gateway = FakeBitmapGateway(emptySet())
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(prepared), loader, { _, _ -> }, gateway,
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await(15_000L) { controller.stateSnapshot().cells.size == 300 && controller.stateSnapshot().cells.values.all { it.status == MediaGridCellLoadStatus.Ready || it.status == MediaGridCellLoadStatus.Failed } }
            val snapshot = controller.stateSnapshot()
            assertTrue(snapshot.cells.values.none { it.status == MediaGridCellLoadStatus.Pending })
            assertConsistentState(snapshot, buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId)
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun oneThousandCacheHitAssetsReachReadyWithoutBitmapRequests() = runBlocking {
        val frame = frame(1_000)
        val prepared = (0L until 1_000L).associateWith { prepared(frame, it) }
        val gateway = FakeBitmapGateway(prepared.values.flatMap { image -> image.candidates.map { it.cacheKey } }.toSet())
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(prepared), loader, { _, _ -> }, gateway,
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await(15_000L) { controller.stateSnapshot().cells.size == 1_000 && controller.stateSnapshot().cells.values.all { it.status == MediaGridCellLoadStatus.Ready } }
            val snapshot = controller.stateSnapshot()
            assertEquals(0, gateway.requests)
            assertTrue(snapshot.cells.values.none { it.status == MediaGridCellLoadStatus.Pending })
            assertTrue(mediaGridControllerStateViolations(snapshot, buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId).isEmpty())
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun fixedSeedOneThousandOperationStateMachineMaintainsControllerInvariants() = runBlocking {
        val frame = frame(32)
        val prepared = (0L until 32L).associateWith { prepared(frame, it) }
        val gateway = FakeBitmapGateway(prepared.values.flatMap { image -> image.candidates.map { it.cacheKey } }.toSet())
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(prepared), loader, { _, _ -> }, gateway,
        )
        val random = java.util.Random(0x5EEDL)
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            repeat(1_000) {
                when (random.nextInt(7)) {
                    0 -> controller.updateViewport(MediaGridViewportAnchor(frame.key, 0, 3, intArrayOf(0, 1, 2, 3), 400, 600, 100, 4))
                    1 -> controller.updateViewport(MediaGridViewportAnchor(frame.key, 4, 7, intArrayOf(4, 5, 6, 7), 400, 600, 100, 4))
                    2 -> controller.pause()
                    3 -> controller.resume()
                    4 -> controller.invalidate(random.nextInt(32).toLong())
                    5 -> controller.updateFrame(frame(32))
                    else -> controller.updateViewport(MediaGridViewportAnchor(frame.key, 8, 11, intArrayOf(8, 9, 10, 11), 400, 600, 100, 4))
                }
                assertConsistentState(controller.stateSnapshot(), buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId)
            }
            await { controller.stateSnapshot().cells.values.all { it.status == MediaGridCellLoadStatus.Ready } }
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }
}
