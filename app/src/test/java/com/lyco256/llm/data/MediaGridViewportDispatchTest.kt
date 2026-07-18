package com.lyco256.llm.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun candidatePriorityIsVisibleThenAdjacentThenWide() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        try {
            manager.updateSourceSnapshot(1L, (1L..5L).map(::source))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(3L))))
            gateway.firstStarted.await()
            assertEquals(listOf(3L), gateway.calls())

            gateway.releaseFirst.complete(Unit)
            withTimeout(2_000) {
                while (gateway.calls().size < 2) delay(5)
            }
            assertEquals(4L, gateway.calls()[1])
        } finally {
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun wideCandidateIsSelectedOnlyAfterAdjacentRangeIsExhausted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        try {
            val sources = listOf(
                source(1L),
                source(2L, downloadState = "failed"),
                source(3L, downloadState = "failed"),
                source(4L, downloadState = "failed"),
                source(5L),
            )
            manager.updateSourceSnapshot(1L, sources)
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(3L))))
            gateway.firstStarted.await()
            assertEquals(listOf(5L), gateway.calls())
        } finally {
            gateway.releaseFirst.complete(Unit)
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun sourceIdentityChangeResetsTheStableHolder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        val first = source(1L)
        val replacement = first.copy(mediaKey = "replacement", previewUrl = "https://example.test/replacement.jpg")
        try {
            val state = manager.state(1L, first)
            manager.updateSourceSnapshot(1L, listOf(first))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            gateway.firstStarted.await()
            gateway.releaseFirst.complete(Unit)
            withTimeout(2_000) {
                while (state.value !is MediaGridThumbnailState.Ready) delay(5)
            }

            manager.updateSourceSnapshot(2L, listOf(replacement))
            withTimeout(2_000) {
                while (state.value != MediaGridThumbnailState.Waiting) delay(5)
            }
            assertSame(state, manager.stateIfPresent(1L))
        } finally {
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun displayFailureRetriesOnceThenPublishesFinalFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = ScriptedGateway(listOf(true, false))
        val manager = MediaGridThumbnailManager(gateway, scope)
        val source = source(1L, localPath = null, previewUrl = "https://example.test/image.jpg")
        try {
            val state = manager.state(1L, source)
            manager.updateSourceSnapshot(1L, listOf(source))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            withTimeout(2_000) {
                while (state.value !is MediaGridThumbnailState.Ready) delay(5)
            }

            manager.onDisplayError(source, File("/tmp/failed-display.jpg"))
            withTimeout(2_000) {
                while (state.value != MediaGridThumbnailState.Failed) delay(5)
            }
            manager.onDisplayError(source, File("/tmp/failed-display-again.jpg"))
            delay(50)
            assertEquals(listOf(1L, 1L), gateway.calls())
        } finally {
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

    private fun source(
        assetId: Long,
        downloadState: String? = null,
        localPath: String? = "/tmp/media-$assetId.jpg",
        previewUrl: String? = null,
    ) = MediaGridThumbnailSource(
        assetId = assetId,
        mediaKey = "media-$assetId",
        localPath = localPath,
        previewUrl = previewUrl,
        remoteUrl = null,
        downloadState = downloadState,
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

    private class ScriptedGateway(
        outcomes: List<Boolean>,
    ) : MediaGridThumbnailStoreGateway {
        private val remainingOutcomes = ArrayDeque(outcomes)
        private val startedCalls = mutableListOf<Long>()

        override suspend fun getOrCreate(source: MediaGridThumbnailSource, allowRemote: Boolean): File {
            synchronized(startedCalls) { startedCalls += source.assetId }
            val success = synchronized(remainingOutcomes) { remainingOutcomes.removeFirst() }
            if (!success) error("scripted thumbnail failure")
            return File("/tmp/scripted-thumb-${source.assetId}.jpg")
        }

        override fun invalidate(file: File) = Unit

        fun calls(): List<Long> = synchronized(startedCalls) { startedCalls.toList() }
    }
}
