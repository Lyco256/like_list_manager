package com.lyco256.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import java.util.Collections
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

    private fun frame(count: Int, columns: Int = 4): MediaGridFrameData {
        val entries = (0 until count).map { index ->
            MediaGridEntry(
                entryId = index.toLong(), clipId = index.toLong(), assetId = index.toLong(),
                mediaKey = "controller-test-$index", mediaIndex = 0, type = "photo",
                displayUrl = "https://example.test/$index.jpg", downloadState = "downloaded",
                localPath = null, xCreatedAt = "2026-07-22T00:00:00Z", likeCount = null,
            )
        }
        return buildMediaGridFrameData(
            entries, ClassifiedSortState(), columns,
            MediaGridDataKey(1, 1, TweetFilterState(), ClassifiedSortState()),
        )
    }

    private fun anchor(frame: MediaGridFrameData, mediaOrdinal: Int = 0): MediaGridViewportAnchor {
        val first = mediaOrdinal.coerceIn(0, (frame.mediaCellIndices.size - 1).coerceAtLeast(0))
        val last = (first + frame.key.columnCount - 1).coerceAtMost(frame.mediaCellIndices.lastIndex)
        val visible = frame.mediaCellIndices.copyOfRange(first, last + 1)
        return MediaGridViewportAnchor(
            frame.key, visible.firstOrNull() ?: 0, visible.lastOrNull() ?: 0, visible,
            400, 600, 100, frame.key.columnCount,
        )
    }

    private class FakePreparer(
        private val prepared: Map<Long, MediaGridPreparedImage>,
        private val failuresBeforeSuccess: Int = 0,
        private val exceptionAttempts: Set<Int> = emptySet(),
        private val preparedFactory: ((MediaGridFrameData, Long) -> MediaGridPreparedImage?)? = null,
    ) : MediaGridMetadataPreparer {
        private val attempts = AtomicInteger()
        override suspend fun prepareCellOrNull(frame: MediaGridFrameData, itemIndex: Int, cellSizePx: Int): MediaGridPreparedImage? {
            val attempt = attempts.incrementAndGet()
            if (attempt in exceptionAttempts) throw IllegalStateException("fake metadata failure $attempt")
            if (attempt <= failuresBeforeSuccess) return null
            val assetId = (frame.items[itemIndex] as MediaGridCellItem).entry.assetId
            return preparedFactory?.invoke(frame, assetId) ?: prepared[assetId]
        }
    }

    private class FakeBitmapGateway(
        initialCached: Set<String>,
        private val failedKeys: Set<String> = emptySet(),
    ) : MediaGridBitmapGateway {
        private val cached = initialCached.toMutableSet()
        val requestedKeys = Collections.synchronizedList(mutableListOf<String>())
        private val requestCount = AtomicInteger()
        val requests: Int
            get() = requestCount.get()
        override fun isCached(candidate: MediaGridPreparedCandidate): Boolean = candidate.cacheKey in cached
        override suspend fun load(candidate: MediaGridPreparedCandidate): Boolean {
            requestCount.incrementAndGet()
            requestedKeys += candidate.cacheKey
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

    private fun await(timeoutMs: Long = 5_000L, diagnostic: () -> Any? = { null }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline && !condition()) Thread.yield()
        assertTrue("controller did not reach expected state: ${diagnostic()}", condition())
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
    fun metadataExceptionRetriesAndThenFailsWithoutMetadataComplete() = runBlocking {
        val frame = frame(1)
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(emptyMap(), exceptionAttempts = setOf(1, 2, 3)), loader, { _, _ -> }, FakeBitmapGateway(emptySet()),
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await { controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Failed }
            val snapshot = controller.stateSnapshot()
            assertEquals(QueueTaskStatus.Failed, snapshot.records[0L]?.metadataStatus)
            assertTrue(snapshot.records[0L]?.metadataAttempts == 3)
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
    fun allCandidatesFailEndsInFailedWithoutPersistentPending() = runBlocking {
        val frame = frame(1)
        val fallback = preparedWithFallback(frame, 0L)
        val loader = ImageLoader.Builder(context).build()
        val gateway = FakeBitmapGateway(
            emptySet(),
            failedKeys = setOf("fallback-first-0", "fallback-local-0"),
        )
        val controller = MediaGridSteadyLoadController(
            context, frame, FakePreparer(mapOf(0L to fallback)), loader, { _, _ -> }, gateway,
        )
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await { gateway.requests >= 2 }
            assertEquals(listOf("fallback-first-0", "fallback-local-0"), gateway.requestedKeys)
            assertEquals(2, gateway.requests)
            await { controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Failed }
            val snapshot = controller.stateSnapshot()
            assertTrue(snapshot.cells.values.none { it.status == MediaGridCellLoadStatus.Pending })
            assertConsistentState(snapshot, buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId)
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun frameUpdateAndInvalidationDiscardOldPreparedKeyAndRestoreCurrentWork() = runBlocking {
        val firstFrame = frame(32, columns = 4)
        val nextFrame = frame(32, columns = 12)
        val loader = ImageLoader.Builder(context).build()
        val gateway = FakeBitmapGateway((0L until 32L).map { "cache-$it" }.toSet())
        val controller = MediaGridSteadyLoadController(
            context,
            firstFrame,
            FakePreparer(emptyMap(), preparedFactory = { current, assetId -> prepared(current, assetId) }),
            loader,
            { _, _ -> },
            gateway,
        )
        try {
            controller.updateViewport(anchor(firstFrame))
            controller.start()
            await { controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Ready }
            val oldGeneration = controller.stateSnapshot().generation
            controller.updateFrame(nextFrame)
            controller.updateViewport(anchor(nextFrame, 24))
            await {
                val snapshot = controller.stateSnapshot()
                snapshot.generation > oldGeneration && snapshot.cells.values.any { it.status == MediaGridCellLoadStatus.Ready } &&
                    snapshot.metadata.values.all { it.key == nextFrame.key }
            }
            controller.invalidate(24L)
            controller.resume()
            await {
                val snapshot = controller.stateSnapshot()
                snapshot.cells[24L]?.status == MediaGridCellLoadStatus.Ready && snapshot.metadata[24L]?.key == nextFrame.key
            }
            assertConsistentState(controller.stateSnapshot(), buildMediaGridOrdinalIndex(nextFrame).mediaOrdinalByAssetId)
        } finally {
            controller.dispose()
            loader.shutdown()
        }
    }

    @Test
    fun pausedPublicationCoalescesThreeHundredInvalidationsAndPublishesVisibleFinalState() = runBlocking {
        val frame = frame(300)
        val prepared = (0L until 300L).associateWith { prepared(frame, it) }
        val loader = ImageLoader.Builder(context).build()
        val gateway = FakeBitmapGateway(prepared.values.flatMap { image -> image.candidates.map { it.cacheKey } }.toSet())
        val controller = MediaGridSteadyLoadController(context, frame, FakePreparer(prepared), loader, { _, _ -> }, gateway)
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await { controller.stateSnapshot().cells[0L]?.status == MediaGridCellLoadStatus.Ready }
            controller.pause()
            repeat(300) { controller.invalidate(it.toLong()) }
            controller.resume()
            await {
                val visible = controller.stateSnapshot().visibleAssetIds
                visible.isNotEmpty() && controller.uiState.value.cells.keys.containsAll(visible) &&
                    visible.all { controller.uiState.value.cells[it]?.status == MediaGridCellLoadStatus.Ready }
            }
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
            await(
                timeoutMs = 15_000L,
                diagnostic = {
                    val snapshot = controller.stateSnapshot()
                    "cells=${snapshot.cells.size}, statuses=${snapshot.cells.values.groupingBy { it.status }.eachCount()}, metadata=${snapshot.metadata.size}, pending=${snapshot.metadataPendingOrdinals.size}/${snapshot.bitmapPendingOrdinals.size}"
                },
            ) { controller.stateSnapshot().cells.size == 1_000 && controller.stateSnapshot().cells.values.all { it.status == MediaGridCellLoadStatus.Ready } }
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
    fun oneHundredCacheHitAssetsReachReadyWithoutRequests() = runBlocking {
        val frame = frame(100)
        val prepared = (0L until 100L).associateWith { prepared(frame, it) }
        val gateway = FakeBitmapGateway(prepared.values.flatMap { image -> image.candidates.map { it.cacheKey } }.toSet())
        val loader = ImageLoader.Builder(context).build()
        val controller = MediaGridSteadyLoadController(context, frame, FakePreparer(prepared), loader, { _, _ -> }, gateway)
        try {
            controller.updateViewport(anchor(frame))
            controller.start()
            await(15_000L) { controller.stateSnapshot().cells.size == 100 && controller.stateSnapshot().cells.values.all { it.status == MediaGridCellLoadStatus.Ready } }
            assertEquals(0, gateway.requests)
            assertTrue(controller.stateSnapshot().cells.values.none { it.status == MediaGridCellLoadStatus.Pending })
            assertConsistentState(controller.stateSnapshot(), buildMediaGridOrdinalIndex(frame).mediaOrdinalByAssetId)
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
