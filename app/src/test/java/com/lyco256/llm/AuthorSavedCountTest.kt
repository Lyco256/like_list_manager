package com.lyco256.llm

import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuthorSavedCountTest {
    @Test
    fun countUsesAllSavedClipsInsteadOfFilteredResults() {
        val allClips = (1L..5L).map { clip(it, authorId = "author-1", username = "same") }
        val state = MainUiState(clips = allClips)
        val filteredResults = allClips.take(2)

        assertEquals(2, filteredResults.size)
        assertEquals(5, state.authorSavedCountByAuthor.getValue(allClips.first().clip.authorKey()))
    }

    @Test
    fun sameDisplayNameWithDifferentIdentityIsCountedSeparately() {
        val first = clip(1, authorId = "author-1", username = "first", displayName = "同名")
        val second = clip(2, authorId = "author-2", username = "second", displayName = "同名")
        val state = MainUiState(clips = listOf(first, second))

        assertNotEquals(first.clip.authorKey(), second.clip.authorKey())
        assertEquals(1, state.authorSavedCountByAuthor.getValue(first.clip.authorKey()))
        assertEquals(1, state.authorSavedCountByAuthor.getValue(second.clip.authorKey()))
    }

    @Test
    fun countTracksDeleteAndUndoClipEmissions() {
        val clips = (1L..5L).map { clip(it, authorId = "author-1", username = "same") }
        val key = clips.first().clip.authorKey()

        assertEquals(5, MainUiState(clips = clips).authorSavedCountByAuthor.getValue(key))
        assertEquals(4, MainUiState(clips = clips.dropLast(1)).authorSavedCountByAuthor.getValue(key))
        assertEquals(5, MainUiState(clips = clips).authorSavedCountByAuthor.getValue(key))
    }

    @Test
    fun countUsesJapaneseThousandsSeparatorAndSuffix() {
        assertEquals("1,100件", formatAuthorSavedCount(1_100))
    }

    private fun clip(
        id: Long,
        authorId: String?,
        username: String,
        displayName: String = "Author",
    ): ClipWithDetails {
        val now = "2026-08-12T00:00:00Z"
        return ClipWithDetails(
            clip = ClipEntity(
                id = id,
                xPostId = "post-$id",
                authorId = authorId,
                authorName = displayName,
                authorUsername = username,
                text = "post",
                postUrl = "https://x.com/$username/status/$id",
                xCreatedAt = now,
                savedAt = now,
                syncedAt = now,
            ),
            assets = emptyList(),
            tags = emptyList(),
        )
    }
}
