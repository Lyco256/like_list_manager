package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaGridDirectPreviewTest {
    @Test
    fun candidatesUseLocalPreviewRemoteDisplayOrderAndRemoveDuplicates() {
        val local = File.createTempFile("media-grid", ".webp").apply { writeText("local") }
        try {
            val candidates = buildMediaGridImageCandidates(
                MediaGridImageCandidateInput(
                    assetId = 7L,
                    mediaKey = "media-7",
                    localPath = local.absolutePath,
                    previewUrl = "https://example.test/preview.jpg",
                    remoteUrl = "https://example.test/remote.jpg",
                    displayUrl = "https://example.test/preview.jpg",
                ),
            )
            assertEquals(
                listOf(MediaGridImageSourceKind.Local, MediaGridImageSourceKind.Preview, MediaGridImageSourceKind.Remote),
                candidates.map { it.kind },
            )
        } finally {
            local.delete()
        }
    }

    @Test
    fun missingLocalFallsBackToPreviewAndFailedDownloadStateDoesNotSuppressUrls() {
        val candidates = buildMediaGridImageCandidates(
            MediaGridImageCandidateInput(
                assetId = 8L,
                mediaKey = "media-8",
                localPath = "/tmp/media-grid-does-not-exist.webp",
                previewUrl = "https://example.test/preview.jpg",
                remoteUrl = "https://example.test/remote.jpg",
                displayUrl = null,
            ),
        )
        assertEquals(listOf(MediaGridImageSourceKind.Preview, MediaGridImageSourceKind.Remote), candidates.map { it.kind })
    }

    @Test
    fun cacheKeyIncludesStableSourceAndDisplaySizeAndChangesWhenLocalFileChanges() {
        val local = File.createTempFile("media-grid", ".webp").apply { writeText("one") }
        try {
            val input = MediaGridImageCandidateInput(9L, "media-9", local.absolutePath, null, null, null)
            val first = buildMediaGridImageCandidates(input).single()
            val same = mediaGridImageCacheKey(first, 120, 120)
            assertEquals(same, mediaGridImageCacheKey(first, 120, 120))
            assertNotEquals(same, mediaGridImageCacheKey(first, 121, 120))
            local.appendText("changed")
            val changed = buildMediaGridImageCandidates(input).single()
            assertNotEquals(first.sourceIdentity, changed.sourceIdentity)
            assertNotEquals(same, mediaGridImageCacheKey(changed, 120, 120))
        } finally {
            local.delete()
        }
    }

    @Test
    fun prefetchIsLimitedToOneDirectionAdjacentRowAndColumnCount() {
        val entries = (0 until 10).map { index ->
            MediaGridPrefetchEntry(
                itemIndex = index,
                assetId = index.toLong(),
                candidates = listOf(
                    MediaGridImageCandidate(MediaGridImageSourceKind.Remote, "https://example.test/$index", "url|$index"),
                ),
            )
        }
        val forward = selectMediaGridPrefetchEntries(entries, setOf(2, 3, 4, 5), 3, 1)
        assertEquals(listOf(6L, 7L, 8L), forward.map { it.assetId })
        val backward = selectMediaGridPrefetchEntries(entries, setOf(5, 6, 7, 8), 2, -1)
        assertEquals(listOf(4L, 3L), backward.map { it.assetId })
        assertTrue(selectMediaGridPrefetchEntries(entries, setOf(2, 3), 3, 0).isEmpty())
    }

    @Test
    fun prefetchRequestIdentityChangesWhenSourceRevisionChanges() {
        val first = mediaGridPrefetchRequestId(1L, 10L, "cache-key")
        val refreshed = mediaGridPrefetchRequestId(2L, 10L, "cache-key")
        assertNotEquals(first, refreshed)
        assertTrue(selectMediaGridPrefetchEntries(emptyList(), setOf(1), 3, 0).isEmpty())
    }
}
