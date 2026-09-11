package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImageEmbeddingFingerprintTest {
    private val asset = AssetEntity(
        id = 12L,
        clipId = 34L,
        mediaKey = "media-1",
        type = "photo",
        remoteUrl = "https://remote.invalid/photo.jpg",
        previewUrl = "https://preview.invalid/photo.jpg",
        localPath = "C:/old/images/photo.webp",
        sizeBytes = 100L,
        createdAt = "created",
    )

    @Test
    fun sameAssetAndSignatureProduceTheSameFingerprint() {
        val signature = ImageFileSignature(length = 100L, lastModified = 200L)

        assertEquals(
            ImageEmbeddingFingerprint.calculate(asset, signature),
            ImageEmbeddingFingerprint.calculate(asset.copy(localPath = "D:/new/images/photo.webp"), signature),
        )
    }

    @Test
    fun fileSignatureAndAssetIdentityFieldsAffectFingerprint() {
        val signature = ImageFileSignature(length = 100L, lastModified = 200L)
        val fingerprint = ImageEmbeddingFingerprint.calculate(asset, signature)

        assertNotEquals(fingerprint, ImageEmbeddingFingerprint.calculate(asset, signature.copy(length = 101L)))
        assertNotEquals(fingerprint, ImageEmbeddingFingerprint.calculate(asset, signature.copy(lastModified = 201L)))
        assertNotEquals(fingerprint, ImageEmbeddingFingerprint.calculate(asset.copy(clipId = 35L), signature))
        assertNotEquals(fingerprint, ImageEmbeddingFingerprint.calculate(asset.copy(mediaKey = "media-2"), signature))
        assertNotEquals(fingerprint, ImageEmbeddingFingerprint.calculate(asset.copy(type = "video_thumbnail"), signature))
        assertNotEquals(fingerprint, ImageEmbeddingFingerprint.calculate(asset.copy(sizeBytes = 101L), signature))
    }
}
