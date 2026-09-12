package com.lyco256.llm.data

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.unum.usearch.Index
import com.lyco256.llm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAnnIndexSnapshotIntegrationTest {
    @Test
    fun realUsearchJniBuildsAndSearches256And768SnapshotsSafely() = runBlocking {
        assertTestHarness()
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" })
        assertEquals("2.26.0", Index.version())

        listOf(256, 768).forEach { dimension ->
            val entries = (0L until 12L).map { key ->
                LocalAnnEntry(key, deterministicVector(dimension, key.toInt()))
            }
            val query = deterministicVector(dimension, 99)
            val snapshot = LocalAnnIndexSnapshot.build(dimension, entries)
            try {
                val hits = snapshot.search(query, entries.size + 20)
                assertTrue(hits.isNotEmpty())
                assertTrue(hits.all { hit -> entries.any { it.key == hit.key } })
                assertEquals(hits.size, hits.map { it.key }.toSet().size)
                assertTrue(hits.all { it.cosineSimilarity.isFinite() })
                assertDeterministicallySorted(hits)

                coroutineScope {
                    (0 until 8).map { worker ->
                        async(Dispatchers.Default) {
                            repeat(16) {
                                val repeated = snapshot.search(
                                    deterministicVector(dimension, worker + it + 100),
                                    4,
                                )
                                assertTrue(repeated.size <= 4)
                                assertTrue(repeated.all { hit -> hit.cosineSimilarity.isFinite() })
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                snapshot.close()
                snapshot.close()
            }
            val error = runCatching { snapshot.search(query, 1) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
        }
    }

    @Test
    fun emptySnapshotAndRepeatedNativeLifecycleAreSafe() = runBlocking {
        assertTestHarness()
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" })
        listOf(256, 768).forEach { dimension ->
            repeat(4) {
                val snapshot = LocalAnnIndexSnapshot.build(dimension, emptyList())
                assertTrue(snapshot.search(deterministicVector(dimension, it), 1).isEmpty())
                snapshot.close()
                snapshot.close()
            }
        }
    }

    private fun assertTestHarness() {
        assertTrue(BuildConfig.TEST_HARNESS)
        assertEquals("com.lyco256.llm.test", BuildConfig.APPLICATION_ID)
    }

    private fun assertDeterministicallySorted(hits: List<AnnHit>) {
        hits.zipWithNext().forEach { (left, right) ->
            assertTrue(
                left.cosineSimilarity > right.cosineSimilarity ||
                    (left.cosineSimilarity == right.cosineSimilarity && left.key < right.key),
            )
        }
    }

    private fun deterministicVector(dimension: Int, salt: Int): FloatArray = FloatArray(dimension) { index ->
        val value = ((index * 37 + salt * 17) % 101) - 50
        (value + 0.25f * ((index + salt) % 4)).toFloat()
    }
}
