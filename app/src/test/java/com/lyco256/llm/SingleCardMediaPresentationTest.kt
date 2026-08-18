package com.lyco256.llm

import com.lyco256.llm.data.AssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleCardMediaPresentationTest {
    @Test
    fun nineBySixteenIsCappedAtThreeByFourAndCropped() {
        val presentation = asset(width = 900, height = 1600).singleCardMediaPresentation()

        assertEquals(3f / 4f, presentation.aspectRatio, 0.0001f)
        assertTrue(presentation.crop)
    }

    @Test
    fun exactThreeByFourKeepsItsRatioWithoutCrop() {
        val presentation = asset(width = 900, height = 1200).singleCardMediaPresentation()

        assertEquals(3f / 4f, presentation.aspectRatio, 0.0001f)
        assertFalse(presentation.crop)
    }

    @Test
    fun squareAndLandscapeKeepExistingPresentation() {
        val square = asset(width = 1200, height = 1200).singleCardMediaPresentation()
        val landscape = asset(width = 1600, height = 900).singleCardMediaPresentation()

        assertEquals(1f, square.aspectRatio, 0.0001f)
        assertFalse(square.crop)
        assertEquals(16f / 9f, landscape.aspectRatio, 0.0001f)
        assertFalse(landscape.crop)
    }

    @Test
    fun missingOrInvalidDimensionsKeepExistingFallback() {
        listOf(
            asset(width = null, height = null),
            asset(width = 0, height = 1200),
            asset(width = 900, height = -1),
        ).forEach { asset ->
            val presentation = asset.singleCardMediaPresentation()
            assertEquals(16f / 10f, presentation.aspectRatio, 0.0001f)
            assertFalse(presentation.crop)
        }
    }

    private fun asset(width: Int?, height: Int?) = AssetEntity(
        id = 1,
        clipId = 1,
        mediaKey = "single-card-image",
        type = "photo",
        remoteUrl = "https://example.test/image.jpg",
        previewUrl = null,
        width = width,
        height = height,
        createdAt = "2026-08-12T00:00:00Z",
    )
}
