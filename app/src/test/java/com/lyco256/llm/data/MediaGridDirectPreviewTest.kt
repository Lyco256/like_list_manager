package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
    fun validPersistentPreviewIsBeforeLocalAndUsesPreviewNamespace() {
        val local = File.createTempFile("media-grid", ".webp").apply { writeText("local") }
        try {
            val input = MediaGridImageCandidateInput(
                assetId = 70L,
                mediaKey = "media-70",
                localPath = local.absolutePath,
                previewUrl = "https://example.test/preview.jpg",
                remoteUrl = "https://example.test/remote.jpg",
                displayUrl = null,
                persistentPreview = MediaGridPersistentPreviewMetadata(
                    filePath = "/data/user/0/example/files/media_grid_previews/v1/70.jpg",
                    length = 256L,
                    lastModified = 200L,
                ),
            )
            val candidates = buildMediaGridImageCandidates(input)
            assertEquals(
                listOf(
                    MediaGridImageSourceKind.PersistentPreview,
                    MediaGridImageSourceKind.Local,
                    MediaGridImageSourceKind.Preview,
                    MediaGridImageSourceKind.Remote,
                ),
                candidates.map { it.kind },
            )
            val previewKey = mediaGridImageCacheKey(candidates.first(), 256, 256)
            assertTrue(previewKey.startsWith("media-grid-preview-v1|"))
            assertNotEquals(previewKey, mediaGridImageCacheKey(candidates[1], 256, 256))
        } finally {
            local.delete()
        }
    }

    @Test
    fun staleOrEmptyPersistentPreviewIsNotAddedByCandidateBuilder() {
        val input = MediaGridImageCandidateInput(
            assetId = 71L,
            mediaKey = "media-71",
            localPath = "/tmp/local.webp",
            previewUrl = "https://example.test/preview.jpg",
            remoteUrl = null,
            displayUrl = null,
            persistentPreview = MediaGridPersistentPreviewMetadata("/tmp/preview.jpg", 0L, 1L),
        )
        assertEquals(
            listOf(MediaGridImageSourceKind.Preview),
            buildMediaGridImageCandidates(input).map { it.kind },
        )
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
    fun persistentPreviewIdentityChangesAfterReplacementAndDoesNotUseDiskCache() {
        val first = MediaGridImageCandidate(
            kind = MediaGridImageSourceKind.PersistentPreview,
            data = "/tmp/preview.jpg",
            sourceIdentity = "preview-v1|9|media-9|/tmp/preview.jpg|100|10",
        )
        val replaced = first.copy(sourceIdentity = "preview-v1|9|media-9|/tmp/preview.jpg|120|11")
        assertNotEquals(mediaGridImageCacheKey(first, 256, 256), mediaGridImageCacheKey(replaced, 256, 256))
        val prepared = MediaGridPreparedCandidate(
            kind = first.kind,
            requestData = File(first.data),
            sourceIdentity = first.sourceIdentity,
            cacheKey = mediaGridImageCacheKey(first, 256, 256),
            width = 256,
            height = 256,
            useDiskCache = false,
        )
        assertFalse(prepared.useDiskCache)
    }

    @Test
    fun initialPreloadUsesVisibleCellsOrAtMostSixRowsBeforeLayout() {
        val indices = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        assertEquals(
            listOf(4, 8, 12),
            selectMediaGridInitialPreloadIndices(indices, setOf(12, 4, 8), 0, 4, true),
        )
        assertEquals(
            20,
            selectMediaGridInitialPreloadIndices(indices, emptySet(), 1, 4, false).size,
        )
    }

    @Test
    fun adjacentPreloadIsLimitedToOneDirectionRow() {
        val indices = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertEquals(
            listOf(6, 7, 8, 9),
            selectMediaGridAdjacentPreloadIndices(indices, setOf(2, 3, 4, 5), 4, 1),
        )
        assertEquals(
            listOf(3, 2, 1, 0),
            selectMediaGridAdjacentPreloadIndices(indices, setOf(4, 5), 4, -1),
        )
    }

    @Test
    fun corruptedPreviewRecoveryIsClaimedOnlyOncePerIdentity() {
        val gate = MediaGridPreviewRecoveryGate()
        assertTrue(gate.claim("preview-v1|1|a|/tmp/a.jpg|10|20"))
        assertFalse(gate.claim("preview-v1|1|a|/tmp/a.jpg|10|20"))
        assertTrue(gate.claim("preview-v1|1|a|/tmp/a.jpg|11|21"))
    }

    @Test
    fun preloadPlanDeduplicatesKeysAndCancelsKeysOutsideTheLatestViewport() {
        val first = MediaGridPreparedCandidate(
            kind = MediaGridImageSourceKind.PersistentPreview,
            requestData = File("/tmp/first.jpg"),
            sourceIdentity = "first",
            cacheKey = "first-key",
            width = 256,
            height = 256,
            useDiskCache = false,
        )
        val second = first.copy(sourceIdentity = "second", cacheKey = "second-key")
        val plan = buildMediaGridPreviewPreloadPlan(
            activeKeys = setOf("old-key", "first-key"),
            candidates = listOf(first, first, second),
        )
        assertEquals(setOf("second-key"), plan.enqueueKeys)
        assertEquals(setOf("old-key"), plan.cancelKeys)
    }

}
