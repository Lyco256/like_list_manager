package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class MediaGridDirectPreviewTest {

    @Test
    fun preparationUsesVisibleCellsAndOneAdjacentRowFromIndexColumn() {
        val indices = intArrayOf(1, 2, 4, 5, 7, 8, 10, 11)
        assertEquals(
            listOf(4, 5, 7, 8, 10, 11),
            selectMediaGridPreparationIndices(indices, setOf(4, 5, 7, 8), 2, 1),
        )
        assertEquals(
            listOf(4, 5, 7, 8, 2, 1),
            selectMediaGridPreparationIndices(indices, setOf(4, 5, 7, 8), 2, -1),
        )
    }
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

}
