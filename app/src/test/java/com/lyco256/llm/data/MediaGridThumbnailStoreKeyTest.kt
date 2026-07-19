package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MediaGridThumbnailStoreKeyTest {
    @Test
    fun localPreviewAndRemoteSourcesUseTheCanonicalKeyRule() {
        val localFile = File.createTempFile("media-grid-key", ".jpg")
        try {
            localFile.writeBytes(byteArrayOf(1, 2, 3))
            val local = source(localFile.absolutePath, previewUrl = "https://example.test/preview.jpg")
            val preview = source(null, previewUrl = "https://example.test/preview.jpg")
            val remote = source(null, previewUrl = null, remoteUrl = "https://example.test/remote.jpg")

            assertEquals(mediaGridThumbnailCacheKey(local, true), mediaGridThumbnailCacheKey(local, true))
            assertNotNull(mediaGridThumbnailCacheKey(preview, true))
            assertNotNull(mediaGridThumbnailCacheKey(remote, true))
            assertNull(mediaGridThumbnailCacheKey(preview, false))
            assertNull(mediaGridThumbnailCacheKey(remote, false))
        } finally {
            localFile.delete()
        }
    }

    @Test
    fun localFileSizeAndModifiedTimeArePartOfTheCacheIdentity() {
        val localFile = File.createTempFile("media-grid-key", ".jpg")
        try {
            localFile.writeBytes(byteArrayOf(1))
            val source = source(localFile.absolutePath)
            val first = mediaGridThumbnailCacheKey(source, true)
            localFile.appendBytes(byteArrayOf(2))
            val second = mediaGridThumbnailCacheKey(source, true)
            assertNotEquals(first, second)
        } finally {
            localFile.delete()
        }
    }

    private fun source(localPath: String?, previewUrl: String? = null, remoteUrl: String? = null) =
        MediaGridThumbnailSource(
            assetId = 1L,
            mediaKey = "media-1",
            localPath = localPath,
            previewUrl = previewUrl,
            remoteUrl = remoteUrl,
        )
}
