package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun zeroCorruptAndTemporaryFilesAreNotCacheFiles() {
        val directory = createTempDir(prefix = "media-grid-cache-files")
        try {
            val valid = directory.resolve("valid.jpg").apply {
                writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0xff.toByte(), 0xd9.toByte()))
            }
            val zero = directory.resolve("zero.jpg")
            val corrupt = directory.resolve("corrupt.jpg").apply { writeText("not a jpeg") }
            val temporary = directory.resolve("valid.tmp").apply { writeBytes(valid.readBytes()) }

            assertTrue(mediaGridThumbnailCacheFileIsValid(valid))
            assertFalse(mediaGridThumbnailCacheFileIsValid(zero))
            assertFalse(mediaGridThumbnailCacheFileIsValid(corrupt))
            assertFalse(mediaGridThumbnailCacheFileIsValid(temporary))
        } finally {
            directory.deleteRecursively()
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
