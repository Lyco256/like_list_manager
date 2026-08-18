package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DurableClipDeleteUndoStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun restoreUsesCurrentImageDirectoryAndDoesNotOverwriteSameNamedFile() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val oldImages = temporaryFolder.newFolder("old-images")
        val currentImages = temporaryFolder.newFolder("current-images")
        val source = File(oldImages, "same.webp").apply { writeText("deleted image") }
        val collision = File(currentImages, source.name).apply { writeText("unrelated image") }
        val asset = asset(42, source)
        val store = DurableClipDeleteUndoStore(filesDirectory)
        val prepared = store.prepare(7, listOf(asset), oldImages)
        val payload = payload(asset, prepared.metadata)
        assertTrue(source.delete())

        val restored = File(store.restore(payload, currentImages).getValue(asset.id))

        assertEquals(currentImages.canonicalFile, restored.parentFile.canonicalFile)
        assertNotEquals(collision.canonicalPath, restored.canonicalPath)
        assertEquals("unrelated image", collision.readText())
        assertEquals("deleted image", restored.readText())
    }

    @Test
    fun outsideImagePathIsRejectedWithoutDeletingOrStagingIt() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val images = temporaryFolder.newFolder("images")
        val outside = temporaryFolder.newFile("outside.webp").apply { writeText("outside") }
        val store = DurableClipDeleteUndoStore(filesDirectory)

        val error = runCatching { store.prepare(8, listOf(asset(43, outside)), images) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("outside", outside.readText())
        assertFalse(File(filesDirectory, DurableClipDeleteUndoStore.DIRECTORY_NAME).walkTopDown().any(File::isFile))
    }

    @Test
    fun malformedCleanupPathCannotDeleteOutsideOrSiblingStagingFiles() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val images = temporaryFolder.newFolder("images")
        val sourceA = File(images, "a.webp").apply { writeText("a") }
        val sourceB = File(images, "b.webp").apply { writeText("b") }
        val store = DurableClipDeleteUndoStore(filesDirectory)
        val preparedA = store.prepare(1, listOf(asset(1, sourceA)), images)
        val preparedB = store.prepare(2, listOf(asset(2, sourceB)), images)
        val outside = temporaryFolder.newFile("never-delete.txt").apply { writeText("safe") }

        store.cleanup(
            payload(
                asset(1, sourceA),
                preparedA.metadata + UndoFileMetadata(999, "../../${outside.name}", outside.length(), "bad"),
            ),
        )

        assertEquals("safe", outside.readText())
        assertFalse(stagedFile(filesDirectory, preparedA.metadata.single()).exists())
        assertTrue(stagedFile(filesDirectory, preparedB.metadata.single()).isFile)
    }

    private fun stagedFile(filesDirectory: File, metadata: UndoFileMetadata): File =
        File(File(filesDirectory, DurableClipDeleteUndoStore.DIRECTORY_NAME), metadata.stagingRelativePath)

    private fun asset(id: Long, file: File) = AssetEntity(
        id = id,
        clipId = 7,
        mediaKey = "media-$id",
        type = "photo",
        remoteUrl = null,
        previewUrl = null,
        localPath = file.absolutePath,
        sizeBytes = file.length(),
        downloadState = "downloaded",
        createdAt = "created",
    )

    private fun payload(asset: AssetEntity, files: List<UndoFileMetadata>) = ClipDeletedUndoPayload(
        clip = ClipEntity(
            id = asset.clipId,
            xPostId = "post",
            authorName = "author",
            authorUsername = "user",
            text = "text",
            postUrl = "url",
            xCreatedAt = "x-created",
            savedAt = "saved",
            syncedAt = "synced",
        ),
        assets = listOf(asset),
        relations = emptyList(),
        files = files,
    )
}
