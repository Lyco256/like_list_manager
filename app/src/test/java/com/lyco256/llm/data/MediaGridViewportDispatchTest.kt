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
    fun viewportRejectedBeforeSourceRegistrationCanBeResent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        try {
            assertFalse(manager.dispatchViewport(viewport(1L, listOf(1L))))
            manager.updateSourceSnapshot(1L, listOf(source(1L)))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            gateway.firstStarted.await()
        } finally {
            gateway.releaseFirst.complete(Unit)
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun emptyInitialViewportDoesNotPreventFirstNonEmptyGeneration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        try {
            manager.updateSourceSnapshot(1L, listOf(source(1L)))
            assertTrue(manager.dispatchViewport(viewport(1L, emptyList())))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            gateway.firstStarted.await()
            assertEquals(listOf(1L), gateway.calls())
        } finally {
            gateway.releaseFirst.complete(Unit)
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun sameAcceptedViewportIsNotAppliedTwice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        try {
            manager.updateSourceSnapshot(1L, listOf(source(1L)))
            val snapshot = viewport(1L, listOf(1L))
            assertTrue(manager.dispatchViewport(snapshot))
            assertFalse(manager.dispatchViewport(snapshot))
            gateway.firstStarted.await()
            assertEquals(listOf(1L), gateway.calls())
        } finally {
            gateway.releaseFirst.complete(Unit)
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

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
    fun draggingAndFlingingAllowOneNormalCompletionButDoNotStartTheNextCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        val state = manager.state(1L, source(1L))
        try {
            manager.updateSourceSnapshot(1L, listOf(source(1L), source(2L)))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            gateway.firstStarted.await()

            manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
            delay(20)
            gateway.releaseFirst.complete(Unit)
            withTimeout(2_000) {
                while (state.value !is MediaGridThumbnailState.Ready) delay(5)
            }
            delay(150)
            assertEquals(listOf(1L), gateway.calls())

            manager.setScrollOperationState(MediaGridScrollOperationState.Flinging)
            delay(20)
            assertEquals(listOf(1L), gateway.calls())
            manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
            withTimeout(2_000) {
                while (gateway.calls().size < 2) delay(5)
            }
            assertEquals(listOf(1L, 2L), gateway.calls())
        } finally {
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun operationStartCancelsWideWithoutRecordingCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        val sources = listOf(
            source(1L),
            source(2L, downloadState = "failed"),
            source(3L, downloadState = "failed"),
            source(4L, downloadState = "failed"),
            source(5L),
        )
        try {
            manager.updateSourceSnapshot(1L, sources)
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(3L))))
            gateway.firstStarted.await()
            assertEquals(listOf(false), gateway.allowRemoteCalls())

            manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
            delay(30)
            assertEquals(listOf(5L), gateway.calls())
            manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
            withTimeout(2_000) {
                while (gateway.calls().size < 2) delay(5)
            }
            assertEquals(listOf(5L, 5L), gateway.calls())
        } finally {
            gateway.releaseFirst.complete(Unit)
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun latestViewportIsUsedAfterOperationStops() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = BlockingGateway()
        val manager = MediaGridThumbnailManager(gateway, scope)
        try {
            manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
            manager.updateSourceSnapshot(1L, (1L..3L).map(::source))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(3L))))
            delay(40)
            assertTrue(gateway.calls().isEmpty())

            manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
            withTimeout(2_000) {
                while (gateway.calls().isEmpty()) delay(5)
            }
            assertEquals(listOf(3L), gateway.calls())
        } finally {
            gateway.releaseFirst.complete(Unit)
            gateway.releaseSubsequent.complete(Unit)
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun idleResumeIsCancelledByRedragRevisionChangeAndDispose() = runBlocking {
        fun newManager(): Triple<MediaGridThumbnailManager, BlockingGateway, CoroutineScope> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gateway = BlockingGateway()
            return Triple(MediaGridThumbnailManager(gateway, scope), gateway, scope)
        }

        run {
            val (manager, gateway, scope) = newManager()
            try {
                manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
                manager.updateSourceSnapshot(1L, listOf(source(1L)))
                assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
                manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
                delay(30)
                manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
                delay(130)
                assertTrue(gateway.calls().isEmpty())
                manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
                withTimeout(2_000) { while (gateway.calls().isEmpty()) delay(5) }
            } finally {
                gateway.releaseFirst.complete(Unit)
                gateway.releaseSubsequent.complete(Unit)
                manager.disposeViewport()
                scope.cancel()
            }
        }

        run {
            val (manager, gateway, scope) = newManager()
            try {
                manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
                manager.updateSourceSnapshot(1L, listOf(source(1L)))
                assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
                manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
                delay(30)
                manager.updateSourceSnapshot(2L, listOf(source(2L)))
                delay(130)
                assertTrue(gateway.calls().isEmpty())
            } finally {
                gateway.releaseFirst.complete(Unit)
                gateway.releaseSubsequent.complete(Unit)
                manager.disposeViewport()
                scope.cancel()
            }
        }

        run {
            val (manager, gateway, scope) = newManager()
            try {
                manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
                manager.updateSourceSnapshot(1L, listOf(source(1L)))
                assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
                manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
                delay(30)
                manager.disposeViewport()
                delay(130)
                assertTrue(gateway.calls().isEmpty())
            } finally {
                gateway.releaseFirst.complete(Unit)
                gateway.releaseSubsequent.complete(Unit)
                scope.cancel()
            }
        }

        run {
            val (manager, gateway, scope) = newManager()
            try {
                manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
                manager.updateSourceSnapshot(1L, listOf(source(1L)))
                assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
                manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
                delay(30)
                manager.setForeground(false)
                delay(130)
                assertTrue(gateway.calls().isEmpty())
                manager.setForeground(true)
                withTimeout(2_000) { while (gateway.calls().isEmpty()) delay(5) }
            } finally {
                gateway.releaseFirst.complete(Unit)
                gateway.releaseSubsequent.complete(Unit)
                manager.disposeViewport()
                scope.cancel()
            }
        }
    }

    @Test
    fun holderObservedAndDisplayErrorDoNotRestartGenerationDuringOperation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = ScriptedGateway(listOf(true, true))
        val manager = MediaGridThumbnailManager(gateway, scope)
        val source = source(1L, localPath = null, previewUrl = "https://example.test/image.jpg")
        val state = manager.state(1L, source)
        try {
            manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
            manager.updateSourceSnapshot(1L, listOf(source))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            delay(50)
            assertTrue(gateway.calls().isEmpty())

            manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
            withTimeout(2_000) {
                while (state.value !is MediaGridThumbnailState.Ready) delay(5)
            }
            manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
            delay(20)
            manager.onDisplayError(source, File("/tmp/failed-display.jpg"))
            delay(150)
            assertEquals(listOf(1L), gateway.calls())

            manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
            withTimeout(2_000) {
                while (gateway.calls().size < 2) delay(5)
            }
            assertEquals(listOf(1L, 1L), gateway.calls())
        } finally {
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

    @Test
    fun cacheHydrationRestoresVisibleAndAdjacentHitsWithoutGeneration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = CacheGateway(setOf(1L, 2L, 3L))
        val manager = MediaGridThumbnailManager(gateway, scope)
        val sources = (1L..3L).map { source(it, localPath = null, previewUrl = "https://example.test/$it.jpg") }
        try {
            val states = sources.map { manager.state(it.assetId, it) }
            manager.updateSourceSnapshot(1L, sources)
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(2L))))
            withTimeout(2_000) {
                states.take(3).forEach { state ->
                    while (state.value !is MediaGridThumbnailState.Ready) delay(5)
                }
            }
            assertEquals(listOf(2L, 3L, 1L), gateway.findCalls())
            assertTrue(gateway.generationCalls().isEmpty())
        } finally {
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun cacheMissesOnlyEnterGenerationAndKnownMissesAreNotRecheckedInRevision() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = CacheGateway(setOf(1L))
        val manager = MediaGridThumbnailManager(gateway, scope)
        val sources = (1L..3L).map { source(it, localPath = null, previewUrl = "https://example.test/$it.jpg") }
        try {
            val visibleState = manager.state(2L, sources[1])
            manager.updateSourceSnapshot(1L, sources)
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(2L))))
            withTimeout(2_000) {
                while (visibleState.value !is MediaGridThumbnailState.Ready) delay(5)
            }
            assertEquals(listOf(2L, 3L, 1L), gateway.findCalls())
            withTimeout(2_000) {
                while (gateway.generationCalls().size < 2) delay(5)
            }
            assertEquals(listOf(2L, 3L), gateway.generationCalls())

            assertTrue(manager.dispatchViewport(viewport(1L, listOf(3L))))
            delay(100)
            assertEquals(listOf(2L, 3L, 1L), gateway.findCalls())
        } finally {
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun cacheHydrationIsCancelledByDraggingAndStaleHitIsNotApplied() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = CacheGateway(setOf(1L), firstFindDelayMs = 300)
        val manager = MediaGridThumbnailManager(gateway, scope)
        val source = source(1L, localPath = null, previewUrl = "https://example.test/1.jpg")
        val state = manager.state(1L, source)
        try {
            manager.updateSourceSnapshot(1L, listOf(source))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            gateway.firstFindStarted.await()
            manager.setScrollOperationState(MediaGridScrollOperationState.Dragging)
            delay(80)
            assertEquals(MediaGridThumbnailState.Waiting, state.value)

            manager.setScrollOperationState(MediaGridScrollOperationState.Idle)
            withTimeout(2_000) {
                while (state.value !is MediaGridThumbnailState.Ready) delay(5)
            }
            assertEquals(2, gateway.findCalls().size)
            assertTrue(gateway.generationCalls().isEmpty())
        } finally {
            manager.disposeViewport()
            scope.cancel()
        }
    }

    @Test
    fun cacheHydrationDoesNotStartGenerationUntilItsSingleResultCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = CacheGateway(emptySet(), firstFindDelayMs = 250)
        val manager = MediaGridThumbnailManager(gateway, scope)
        val source = source(1L, localPath = null, previewUrl = "https://example.test/1.jpg")
        val state = manager.state(1L, source)
        try {
            manager.updateSourceSnapshot(1L, listOf(source))
            assertTrue(manager.dispatchViewport(viewport(1L, listOf(1L))))
            gateway.firstFindStarted.await()
            delay(80)
            assertTrue(gateway.generationCalls().isEmpty())
            assertEquals(MediaGridThumbnailState.Waiting, state.value)
            withTimeout(2_000) {
                while (state.value !is MediaGridThumbnailState.Ready) delay(5)
            }
            assertEquals(listOf(1L), gateway.generationCalls())
        } finally {
            manager.disposeViewport()
            scope.cancel()
        }
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
        private val startedAllowRemote = mutableListOf<Boolean>()

        override suspend fun getOrCreate(source: MediaGridThumbnailSource, allowRemote: Boolean): File {
            val callNumber = synchronized(startedCalls) {
                startedCalls += source.assetId
                startedAllowRemote += allowRemote
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

        fun allowRemoteCalls(): List<Boolean> = synchronized(startedCalls) { startedAllowRemote.toList() }
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

    private class CacheGateway(
        private val hits: Set<Long>,
        private val firstFindDelayMs: Long = 0L,
    ) : MediaGridThumbnailStoreGateway {
        private val checked = mutableListOf<Long>()
        private val generated = mutableListOf<Long>()
        val firstFindStarted = CompletableDeferred<Unit>()

        override fun cacheKey(source: MediaGridThumbnailSource, allowRemote: Boolean): String =
            "cache-${source.assetId}-$allowRemote"

        override fun findCached(source: MediaGridThumbnailSource, allowRemote: Boolean): File? {
            synchronized(checked) { checked += source.assetId }
            if (checked.size == 1) {
                firstFindStarted.complete(Unit)
                if (firstFindDelayMs > 0) {
                    try {
                        Thread.sleep(firstFindDelayMs)
                    } catch (_: InterruptedException) {
                        // The coordinator will observe cancellation before publishing a result.
                    }
                }
            }
            return if (source.assetId in hits) File("/tmp/cached-${source.assetId}.jpg") else null
        }

        override suspend fun getOrCreate(source: MediaGridThumbnailSource, allowRemote: Boolean): File {
            synchronized(generated) { generated += source.assetId }
            return File("/tmp/generated-${source.assetId}.jpg")
        }

        override fun invalidate(file: File) = Unit

        fun findCalls(): List<Long> = synchronized(checked) { checked.toList() }

        fun generationCalls(): List<Long> = synchronized(generated) { generated.toList() }
    }
}
