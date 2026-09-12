package com.lyco256.llm.data

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAnnIndexSnapshotTest {
    @Test
    fun supportedDimensionsCopyAndNormalizeInputs() = runBlocking {
        val original = FloatArray(256) { 2f }
        val factory = RecordingFactory(searchResults = { longArrayOf(10L) })
        LocalAnnIndexSnapshot.buildForTesting(
            dimension = 256,
            entries = listOf(LocalAnnEntry(10L, original)),
            backendFactory = factory,
        ).use { snapshot ->
            assertEquals(256, snapshot.dimension)
            assertEquals(1f / kotlin.math.sqrt(256f), factory.addedVectors.single()[0], 0.00001f)
            val query = original.copyOf()
            val hits = snapshot.search(query, 1)
            assertEquals(1, hits.size)
            assertEquals(1f, hits.single().cosineSimilarity, 0.00001f)
            assertArrayEquals(FloatArray(256) { 2f }, original, 0f)
        }
    }

    @Test
    fun acceptsBothSupportedDimensionsAndRejectsOtherDimensions() = runBlocking {
        for (dimension in listOf(256, 768)) {
            LocalAnnIndexSnapshot.buildForTesting(
                dimension,
                listOf(LocalAnnEntry(1L, FloatArray(dimension) { 1f })),
                RecordingFactory(searchResults = { longArrayOf(1L) }),
            ).close()
        }
        assertFailure {
            LocalAnnIndexSnapshot.buildForTesting(
                128,
                listOf(LocalAnnEntry(1L, FloatArray(128) { 1f })),
                RecordingFactory(searchResults = { longArrayOf(1L) }),
            )
        }
    }

    @Test
    fun unsupportedAbiIsRejectedWithExplicitError() {
        val error = runCatching { requireSupportedUsearchAbi(listOf("x86_64")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message?.contains("x86_64") == true)
    }

    @Test
    fun emptySnapshotSearchesAsEmptyWithoutCreatingBackend() = runBlocking {
        val factory = RecordingFactory(searchResults = { longArrayOf() })
        LocalAnnIndexSnapshot.buildForTesting(256, emptyList(), factory).use { snapshot ->
            assertTrue(snapshot.search(FloatArray(256) { 1f }, 1).isEmpty())
        }
        assertEquals(0, factory.createCount.get())
    }

    @Test
    fun rejectsInvalidVectorsAndDuplicateKeysBeforeCreatingBackend() = runBlocking {
        val cases = listOf<FloatArray>(
            FloatArray(255),
            FloatArray(256) { Float.NaN },
            FloatArray(256) { Float.POSITIVE_INFINITY },
            FloatArray(256),
        )
        cases.forEach { vector ->
            val factory = RecordingFactory(searchResults = { longArrayOf() })
            assertFailure {
                LocalAnnIndexSnapshot.buildForTesting(
                    256,
                    listOf(LocalAnnEntry(1L, vector)),
                    factory,
                )
            }
            assertEquals(0, factory.createCount.get())
        }

        val factory = RecordingFactory(searchResults = { longArrayOf() })
        assertFailure {
            LocalAnnIndexSnapshot.buildForTesting(
                256,
                listOf(
                    LocalAnnEntry(1L, FloatArray(256) { 1f }),
                    LocalAnnEntry(1L, FloatArray(256) { 2f }),
                ),
                factory,
            )
        }
        assertEquals(0, factory.createCount.get())
    }

    @Test
    fun exactRerankSortsByScoreThenKeyAndClampsCandidateCount() = runBlocking {
        val factory = RecordingFactory(searchResults = { longArrayOf(7L, 2L, 9L) })
        LocalAnnIndexSnapshot.buildForTesting(
            256,
            listOf(
                LocalAnnEntry(7L, FloatArray(256) { 1f }),
                LocalAnnEntry(2L, FloatArray(256) { 1f }),
                LocalAnnEntry(9L, FloatArray(256) { -1f }),
            ),
            factory,
        ).use { snapshot ->
            val hits = snapshot.search(FloatArray(256) { 1f }, 99)
            assertEquals(listOf(2L, 7L, 9L), hits.map { it.key })
            assertTrue(hits.all { it.cosineSimilarity.isFinite() })
            assertEquals(3, factory.requestedCandidateCounts.single())
        }
    }

    @Test
    fun duplicateNativeKeyDoesNotDuplicateResult() = runBlocking {
        val factory = RecordingFactory(searchResults = { longArrayOf(1L, 1L) })
        LocalAnnIndexSnapshot.buildForTesting(
            256,
            listOf(
                LocalAnnEntry(1L, FloatArray(256) { 1f }),
                LocalAnnEntry(2L, FloatArray(256) { -1f }),
            ),
            factory,
        ).use { snapshot ->
            assertEquals(listOf(1L), snapshot.search(FloatArray(256) { 1f }, 2).map { it.key })
        }
    }

    @Test
    fun unknownNativeKeyIsAnIndexIntegrityErrorAndNonPositiveCountIsRejected() = runBlocking {
        val factory = RecordingFactory(searchResults = { longArrayOf(99L) })
        LocalAnnIndexSnapshot.buildForTesting(
            256,
            listOf(LocalAnnEntry(1L, FloatArray(256) { 1f })),
            factory,
        ).use { snapshot ->
            assertFailure { snapshot.search(FloatArray(256) { 1f }, 1) }
            assertFailure { snapshot.search(FloatArray(256) { 1f }, 0) }
            assertFailure { snapshot.search(FloatArray(256) { 1f }, -1) }
        }
    }

    @Test
    fun closeIsIdempotentReleasesBackendAndRejectsSearch() = runBlocking {
        val factory = RecordingFactory(searchResults = { longArrayOf(1L) })
        val snapshot = LocalAnnIndexSnapshot.buildForTesting(
            256,
            listOf(LocalAnnEntry(1L, FloatArray(256) { 1f })),
            factory,
        )
        snapshot.close()
        snapshot.close()
        assertEquals(1, factory.closeCount)
        val error = runCatching { snapshot.search(FloatArray(256) { 1f }, 1) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun buildFailureAndCancellationClosePartialBackend() = runBlocking {
        val failureFactory = RecordingFactory(
            searchResults = { longArrayOf() },
            addFailure = IllegalStateException("simulated add failure"),
        )
        assertFailure {
            LocalAnnIndexSnapshot.buildForTesting(
                256,
                listOf(LocalAnnEntry(1L, FloatArray(256) { 1f })),
                failureFactory,
            )
        }
        assertEquals(1, failureFactory.closeCount)

        val cancellationFactory = RecordingFactory(
            searchResults = { longArrayOf() },
            addFailure = CancellationException("simulated cancellation"),
        )
        assertFailure {
            LocalAnnIndexSnapshot.buildForTesting(
                256,
                listOf(LocalAnnEntry(1L, FloatArray(256) { 1f })),
                cancellationFactory,
            )
        }
        assertEquals(1, cancellationFactory.closeCount)
    }

    @Test
    fun actualCoroutineCancellationClosesPartialBackend() = runBlocking {
        val addStarted = CountDownLatch(1)
        val releaseAdd = CountDownLatch(1)
        val factory = RecordingFactory(
            searchResults = { longArrayOf() },
            beforeAdd = {
                addStarted.countDown()
                releaseAdd.await(5, TimeUnit.SECONDS)
            },
        )
        val build = async(Dispatchers.Default) {
            LocalAnnIndexSnapshot.buildForTesting(
                256,
                listOf(
                    LocalAnnEntry(1L, FloatArray(256) { 1f }),
                    LocalAnnEntry(2L, FloatArray(256) { 2f }),
                ),
                factory,
            )
        }
        assertTrue(addStarted.await(5, TimeUnit.SECONDS))
        build.cancel()
        releaseAdd.countDown()
        val error = runCatching { build.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, factory.closeCount)
    }

    private suspend fun assertFailure(block: suspend () -> Any?) {
        assertTrue(runCatching { block() }.isFailure)
    }

    private class RecordingFactory(
        private val searchResults: () -> LongArray,
        private val addFailure: Throwable? = null,
        private val beforeAdd: (() -> Unit)? = null,
    ) : LocalAnnBackendFactory {
        val createCount = AtomicInteger()
        var closeCount = 0
        val addedVectors = mutableListOf<FloatArray>()
        val requestedCandidateCounts = mutableListOf<Int>()

        override fun create(dimension: Int, capacity: Int): LocalAnnIndexBackend {
            createCount.incrementAndGet()
            return object : LocalAnnIndexBackend {
                private var closed = false

                override fun add(key: Long, vector: FloatArray) {
                    check(!closed)
                    beforeAdd?.invoke()
                    addedVectors += vector.copyOf()
                    addFailure?.let { throw it }
                }

                override fun search(query: FloatArray, candidateCount: Int): LongArray {
                    check(!closed)
                    requestedCandidateCounts += candidateCount
                    return searchResults()
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    closeCount++
                }
            }
        }

    }
}
