package com.lyco256.llm.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyLocalWorkTrackerTest {
    @Test
    fun oneOperationIsActiveOnlyInsideTrackedBlock() = runBlocking {
        val tracker = HeavyLocalWorkTracker()

        assertFalse(tracker.isActive.value)
        tracker.track { assertTrue(tracker.isActive.value) }
        assertFalse(tracker.isActive.value)
    }

    @Test
    fun finishingOneOfTwoOverlappingOperationsKeepsTrackerActive() = runBlocking {
        val tracker = HeavyLocalWorkTracker()
        val firstMayFinish = CompletableDeferred<Unit>()
        val secondMayFinish = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = async {
            tracker.track {
                firstStarted.complete(Unit)
                firstMayFinish.await()
            }
        }
        firstStarted.await()
        val second = async {
            tracker.track {
                secondStarted.complete(Unit)
                secondMayFinish.await()
            }
        }
        secondStarted.await()
        assertTrue(tracker.isActive.value)

        firstMayFinish.complete(Unit)
        first.await()
        assertTrue(tracker.isActive.value)

        secondMayFinish.complete(Unit)
        second.await()
        assertFalse(tracker.isActive.value)
    }

    @Test
    fun exceptionAlwaysReleasesTracker() = runBlocking {
        val tracker = HeavyLocalWorkTracker()

        runCatching {
            tracker.track { error("persist failed") }
        }

        assertFalse(tracker.isActive.value)
    }

    @Test
    fun networkWaitAndUiPreparationStayInactiveWhileLocalPersistIsTracked() = runBlocking {
        val tracker = HeavyLocalWorkTracker()

        suspend fun simulatedNetworkWait() = Unit
        suspend fun simulatedUiPreparation() = Unit
        simulatedNetworkWait()
        assertFalse(tracker.isActive.value)
        simulatedUiPreparation()
        assertFalse(tracker.isActive.value)
        tracker.trackIf(condition = false) { assertFalse(tracker.isActive.value) }

        tracker.track { assertTrue(tracker.isActive.value) }
        assertFalse(tracker.isActive.value)
    }
}
