package com.lyco256.llm

import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.MediaGridAssetRow
import com.lyco256.llm.data.MediaGridClipSource
import com.lyco256.llm.data.MediaGridSourceSnapshot
import com.lyco256.llm.data.TagHierarchy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDuplicateMediaGridTest {
    @Test
    fun duplicateResultUsesOnlyOrderedExistingAssetsAndPreservesAssetOrder() = runBlocking {
        val source = snapshot()
        val state = ClassifiedImageDuplicateSearchState(
            status = ClassifiedImageDuplicateSearchStatus.READY,
            orderedAssetIds = listOf(102L, 201L, 999L),
            groupCount = 1,
            sourceRevision = 12L,
            requestGeneration = 1L,
        )

        val result = prepare(source, state)

        assertEquals(listOf(102L, 201L), result.entries.map { it.assetId })
        assertEquals(listOf(102L, 201L), result.entries.map { it.entryId })
        assertEquals(listOf(1, 0), result.entries.map { it.mediaIndex })
        assertEquals(2, result.matchingMediaCount)
        assertEquals(2, result.matchingClipCount)
        assertTrue(result.entries.all { it.assetId in setOf(102L, 201L) })
        assertFalse(result.entries.any { it.assetId == 101L || it.assetId == 202L })
    }

    @Test
    fun duplicateFilterAppliesToParentClipWithoutRegroupingOrReplenishing() = runBlocking {
        val state = ClassifiedImageDuplicateSearchState(
            status = ClassifiedImageDuplicateSearchStatus.READY,
            orderedAssetIds = listOf(102L, 201L),
            groupCount = 1,
            sourceRevision = 12L,
            requestGeneration = 1L,
        )

        val result = prepare(
            snapshot = snapshot(),
            state = state,
            filters = TweetFilterState(
                taggedOnly = false,
                selectedAuthors = setOf(TweetAuthorKey(null, "first")),
            ),
        )

        assertEquals(listOf(102L), result.entries.map { it.assetId })
        assertEquals(1, result.matchingMediaCount)
        assertEquals(1, result.matchingClipCount)
    }

    @Test
    fun duplicateFrameForcesDefaultContiguousCellsEvenIfCallerPassesSortState() = runBlocking {
        val state = ClassifiedImageDuplicateSearchState(
            status = ClassifiedImageDuplicateSearchStatus.READY,
            orderedAssetIds = listOf(102L, 201L),
            groupCount = 1,
            requestGeneration = 4L,
        )
        val result = prepare(snapshot(), state)
        val key = checkNotNull(result.dataKey)
        val frame = buildMediaGridFrameData(
            entries = result.entries,
            sort = ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime, tagEnabled = true),
            columnCount = 4,
            dataKey = key,
        )

        assertEquals(listOf(102L, 201L), frame.items.map { (it as MediaGridCellItem).entry.assetId })
        assertTrue(frame.items.all { it is MediaGridCellItem })
    }

    @Test
    fun normalMediaGridPathStillExpandsAllMediaAssets() = runBlocking {
        val result = prepare(snapshot(), ClassifiedImageDuplicateSearchState())

        assertEquals(listOf(101L, 102L, 201L, 202L), result.entries.map { it.assetId })
        assertEquals(4, result.matchingMediaCount)
    }

    private suspend fun prepare(
        snapshot: MediaGridSourceSnapshot,
        state: ClassifiedImageDuplicateSearchState,
        filters: TweetFilterState = TweetFilterState(taggedOnly = false),
    ): ClassifiedMediaGridState {
        val key = MediaGridCacheKey(
            sourceRevision = snapshot.revision,
            hierarchyRevision = TagHierarchy().structuralRevision,
            filter = filters,
            sort = ClassifiedSortState(),
            imageDuplicateSearchIdentity = state.mediaGridIdentity,
            imageDuplicateSearchGeneration = state.requestGeneration,
        )
        return prepareMediaGridMetadata(
            snapshot = snapshot,
            hierarchy = TagHierarchy(),
            filters = filters,
            sort = ClassifiedSortState(),
            searchState = ClassifiedSearchState(),
            cache = MediaGridMetadataCache(maxEntries = 10),
            key = key,
            imageDuplicateSearchState = state,
        )
    }

    private fun snapshot(): MediaGridSourceSnapshot = MediaGridSourceSnapshot(
        revision = 12L,
        clips = listOf(
            sourceClip(1L, "first", listOf(asset(101L), asset(102L))),
            sourceClip(2L, "second", listOf(asset(201L), asset(202L))),
            sourceClip(3L, "third", listOf(asset(301L, type = "audio"))),
        ),
    )

    private fun sourceClip(id: Long, username: String, assets: List<MediaGridAssetRow>) = MediaGridClipSource(
        clip = ClipEntity(
            id = id,
            xPostId = "post-$id",
            authorName = username,
            authorUsername = username,
            text = "text-$id",
            postUrl = "https://example.invalid/$id",
            xCreatedAt = "2026-09-13T00:00:00Z",
            savedAt = "2026-09-13T00:00:00Z",
            syncedAt = "2026-09-13T00:00:00Z",
        ),
        assets = assets,
        sourceIndex = id.toInt(),
    )

    private fun asset(id: Long, type: String = "photo") = MediaGridAssetRow(
        clipId = if (id >= 200L) 2L else 1L,
        assetId = id,
        mediaKey = "media-$id",
        assetType = type,
        remoteUrl = "https://example.invalid/$id",
        previewUrl = null,
        localPath = "/data/local/$id.jpg",
        width = 100,
        height = 100,
        downloadState = "downloaded",
    )
}
