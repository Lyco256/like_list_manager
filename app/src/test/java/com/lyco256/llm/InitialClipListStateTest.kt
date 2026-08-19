package com.lyco256.llm

import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialClipListStateTest {
    @Test
    fun firstEmptyEmissionCompletesLoadingPermanently() {
        val initial = InitialClipListState()

        assertFalse(initial.hasReceivedInitialEmission)
        val loadedEmpty = initial.afterEmission(emptyList())
        assertTrue(loadedEmpty.hasReceivedInitialEmission)
        assertEquals(emptyList<ClipWithDetails>(), loadedEmpty.clips)

        val loadedNonEmpty = loadedEmpty.afterEmission(listOf(clip(1)))
        assertTrue(loadedNonEmpty.hasReceivedInitialEmission)
        assertEquals(1, loadedNonEmpty.clips.size)

        val laterEmpty = loadedNonEmpty.afterEmission(emptyList())
        assertTrue(laterEmpty.hasReceivedInitialEmission)
        assertTrue(laterEmpty.clips.isEmpty())
    }

    private fun clip(id: Long): ClipWithDetails {
        val now = "2026-08-12T00:00:00Z"
        return ClipWithDetails(
            clip = ClipEntity(
                id = id,
                xPostId = "post-$id",
                authorName = "Author",
                authorUsername = "author",
                text = "post",
                postUrl = "https://x.com/author/status/$id",
                xCreatedAt = now,
                savedAt = now,
                syncedAt = now,
            ),
            assets = emptyList(),
            tags = emptyList(),
        )
    }
}
