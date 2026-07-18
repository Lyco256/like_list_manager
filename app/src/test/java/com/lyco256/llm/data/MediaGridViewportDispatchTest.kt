package com.lyco256.llm.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaGridViewportDispatchTest {
    @Test
    fun pixelOnlyMovementDoesNotChangeViewportStructure() {
        val first = viewport(20L, listOf(7L), centerDistance = 2)
        val pixelOnly = viewport(20L, listOf(7L), centerDistance = 99)

        assertTrue(first.sameStructureAs(pixelOnly))
        val gate = MediaGridViewportRevisionGate().apply { setSourceRevision(20L) }
        assertTrue(gate.shouldApply(first))
        assertFalse(gate.shouldApply(pixelOnly))
    }

    @Test
    fun visibleOrderColumnsAndRevisionChangeViewportStructure() {
        val first = viewport(20L, listOf(7L, 8L), columns = 4)
        val gate = MediaGridViewportRevisionGate().apply { setSourceRevision(20L) }
        assertTrue(gate.shouldApply(first))
        assertTrue(gate.shouldApply(first.copy(columnCount = 5)))
        assertTrue(gate.shouldApply(first.copy(items = first.items.reversed())))
        assertFalse(gate.shouldApply(first.copy(sourceRevision = 19L)))
    }

    @Test
    fun generationUsesOnlyLatestViewportAfterConsecutiveUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        val sources = (1L..3L).map { source(it) }
        try {
            manager.updateSourceSnapshot(1L, sources)
            assertTrue("initial viewport rejected", manager.dispatchViewport(viewport(1L, listOf(1L))))
            gateway.firstStarted.await()

            assertTrue("second viewport rejected", manager.dispatchViewport(viewport(1L, listOf(2L))))
            assertTrue("latest viewport rejected", manager.dispatchViewport(viewport(1L, listOf(3L))))
            gateway.releaseFirst.complete(Unit)

            val reachedLatest = withTimeoutOrNull(2_000) {
                while (gateway.calls() != listOf(1L, 3L)) delay(5)
                true
            } ?: false
            assertTrue("generation calls were ${gateway.calls()}", reachedLatest)
            assertEquals(listOf(1L, 3L), gateway.calls())
        } finally {
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun staleRevisionAndDisposeEventsAreRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        try {
            manager.updateSourceSnapshot(10L, listOf(source(1L)))
            assertTrue(manager.dispatchViewport(viewport(10L, listOf(1L))))
            manager.updateSourceSnapshot(11L, listOf(source(2L)))
            assertFalse(manager.dispatchViewport(viewport(10L, listOf(1L))))
            manager.disposeViewport()
            assertFalse(manager.dispatchViewport(viewport(11L, listOf(2L))))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun holderStateReadDoesNotWaitForCoordinator() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        val source = source(7L)
        try {
            manager.stopCoordinatorForTest()
            val state = manager.state(7L, source)
            assertSame(state, manager.stateIfPresent(7L))
            assertEquals(MediaGridThumbnailState.Waiting, state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun viewportPreparationRangeKeepsOneAdjacentRowMargin() {
        assertEquals(
            setOf(4, 5, 6, 7),
            mediaGridViewportUiIndices(first = 5, last = 6, columns = 1, sourceSize = 10),
        )
        assertEquals(
            setOf(0, 1, 2),
            mediaGridViewportUiIndices(first = 0, last = 1, columns = 1, sourceSize = 3),
        )
    }

    private fun viewport(
        revision: Long,
        assetIds: List<Long>,
        columns: Int = 1,
        centerDistance: Int = 0,
    ) = MediaGridViewportSnapshot(
        sourceRevision = revision,
        columnCount = columns,
        items = assetIds.mapIndexed { index, assetId ->
            MediaGridViewportItem(assetId, sourceIndex = assetId.toInt() - 1, centerDistance = centerDistance + index)
        },
    )

    private fun source(assetId: Long) = MediaGridThumbnailSource(
        assetId = assetId,
        mediaKey = "media-$assetId",
        localPath = "/tmp/media-$assetId.jpg",
        previewUrl = null,
        remoteUrl = null,
    )

    private class BlockingGateway : MediaGridThumbnailStoreGateway {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSubsequent = CompletableDeferred<Unit>()
        private val startedCalls = mutableListOf<Long>()

        override suspend fun getOrCreate(source: MediaGridThumbnailSource, allowRemote: Boolean): File {
            val callNumber = synchronized(startedCalls) {
                startedCalls += source.assetId
                startedCalls.size
            }
            if (callNumber == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                releaseSubsequent.await()
            }
            return File("/tmp/thumb-${source.assetId}.jpg")
        }

        override fun invalidate(file: File) = Unit

        fun calls(): List<Long> = synchronized(startedCalls) { startedCalls.toList() }
    }
}
