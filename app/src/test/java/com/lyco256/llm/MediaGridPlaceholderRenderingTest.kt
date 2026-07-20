package com.lyco256.llm

import com.lyco256.llm.data.MediaGridImageCandidate
import com.lyco256.llm.data.MediaGridImageSourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaGridPlaceholderRenderingTest {
    private val candidate = MediaGridImageCandidate(
        kind = MediaGridImageSourceKind.Local,
        data = "/data/user/0/com.lyco256.llm/files/image.webp",
        sourceIdentity = "local|1|media-1|/data/user/0/com.lyco256.llm/files/image.webp|10|20",
    )

    @Test
    fun loadingAndFallbackArePlaceholdersUntilCurrentCandidateSucceeds() {
        assertEquals(
            MediaGridCellVisualState.Placeholder,
            mediaGridCellVisualState(1, -1, null, candidate.sourceIdentity),
        )
        assertEquals(
            MediaGridCellVisualState.Placeholder,
            mediaGridCellVisualState(2, 1, null, candidate.sourceIdentity),
        )
    }

    @Test
    fun currentCandidateSuccessIsImage() {
        assertEquals(
            MediaGridCellVisualState.Image,
            mediaGridCellVisualState(1, -1, candidate.sourceIdentity, candidate.sourceIdentity),
        )
    }

    @Test
    fun candidateChangeDoesNotReusePreviousSuccess() {
        assertEquals(
            MediaGridCellVisualState.Placeholder,
            mediaGridCellVisualState(1, -1, candidate.sourceIdentity, "different"),
        )
    }

    @Test
    fun noCandidatesOrAllCandidatesFailedAreErrors() {
        assertEquals(MediaGridCellVisualState.Error, mediaGridCellVisualState(0, -1, null, null))
        assertEquals(MediaGridCellVisualState.Error, mediaGridCellVisualState(2, 2, null, null))
    }

    @Test
    fun unpreparedCandidateMetadataRemainsPlaceholder() {
        assertEquals(
            MediaGridCellVisualState.Placeholder,
            mediaGridCellVisualState(0, -1, null, null, prepared = false),
        )
    }
}
