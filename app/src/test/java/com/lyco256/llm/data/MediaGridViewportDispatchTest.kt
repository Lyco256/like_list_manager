package com.lyco256.llm.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridViewportDispatchTest {
    @Test
    fun latestUnprocessedSnapshotReplacesIntermediateValue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val processed = mutableListOf<Int>()
        val dispatcher = LatestValueDispatcher<Int>(scope, process = { value ->
            synchronized(processed) { processed += value }
            if (value == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        })

        try {
            assertTrue(dispatcher.dispatch(1))
            firstStarted.await()
            assertTrue(dispatcher.dispatch(2))
            assertTrue(dispatcher.dispatch(3))
            releaseFirst.complete(Unit)
            withTimeout(2_000) {
                while (synchronized(processed) { processed.toList() } != listOf(1, 3)) delay(5)
            }
            assertEquals(listOf(1, 3), synchronized(processed) { processed.toList() })
        } finally {
            dispatcher.close()
            scope.cancel()
        }
    }

    @Test
    fun dispatchDoesNotWaitForWorkerProcessing() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dispatcher = LatestValueDispatcher<Int>(scope, process = {
            started.complete(Unit)
            release.await()
        })

        try {
            assertTrue(dispatcher.dispatch(1))
            started.await()
            assertTrue(dispatcher.dispatch(2))
            release.complete(Unit)
        } finally {
            dispatcher.close()
            scope.cancel()
        }
    }

    @Test
    fun staleRevisionAndDuplicateSnapshotsAreRejected() {
        val gate = MediaGridViewportRevisionGate()
        val current = MediaGridViewportSnapshot(
            sourceRevision = 20L,
            columnCount = 4,
            items = listOf(MediaGridViewportItem(assetId = 7L, sourceIndex = 3, centerDistance = 2)),
        )
        gate.setSourceRevision(20L)

        assertTrue(gate.shouldApply(current))
        assertFalse(gate.shouldApply(current))
        assertFalse(gate.shouldApply(current.copy(sourceRevision = 19L)))

        gate.setSourceRevision(21L)
        assertTrue(gate.shouldApply(current.copy(sourceRevision = 21L)))
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
    fun dispatchAfterCloseIsIgnored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = LatestValueDispatcher<Int>(scope, process = {})
        dispatcher.close()
        assertFalse(dispatcher.dispatch(1))
        scope.cancel()
    }
}
