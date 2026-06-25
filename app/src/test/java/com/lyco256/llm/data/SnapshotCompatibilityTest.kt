package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager

class SnapshotCompatibilityTest {
    @Test
    fun copiedProductionDatabaseIsReadableAndSourceRemainsByteIdentical() {
        val root = System.getProperty("llm.snapshot.root")?.let(::File)
        assumeTrue("-PsnapshotRoot was not supplied", root != null)
        val source = requireNotNull(root).resolve("like_list_manager.db")
        require(source.isFile) { "Snapshot database was not found: ${source.absolutePath}" }
        val sourceBefore = source.sha256()
        val tempDirectory = Files.createTempDirectory("llm-snapshot-db-").toFile()
        try {
            val workingCopy = source.copyTo(tempDirectory.resolve("working.db"), overwrite = true)
            DriverManager.getConnection("jdbc:sqlite:${workingCopy.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA integrity_check").use { result ->
                        assertTrue(result.next())
                        assertEquals("ok", result.getString(1))
                    }
                    val requiredTables = setOf("clips", "assets", "tags", "tag_groups", "clip_tags", "sync_state")
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { result ->
                        val actual = buildSet { while (result.next()) add(result.getString(1)) }
                        assertTrue("Missing tables: ${requiredTables - actual}", actual.containsAll(requiredTables))
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM clips").use { result -> assertTrue(result.next()) }
                    statement.executeQuery("SELECT COUNT(*) FROM clip_tags").use { result -> assertTrue(result.next()) }
                }
            }
        } finally {
            tempDirectory.deleteRecursively()
        }
        assertEquals(sourceBefore, source.sha256())
    }

    @Test
    fun copiedProductionImagesKeepNamesSizesAndHashes() {
        val imageRoot = System.getProperty("llm.snapshot.images")?.let(::File)
        assumeTrue("-PsnapshotImages was not supplied", imageRoot != null)
        val source = requireNotNull(imageRoot)
        require(source.isDirectory) { "Snapshot image directory was not found: ${source.absolutePath}" }
        val before = source.fileManifest()
        val tempDirectory = Files.createTempDirectory("llm-snapshot-images-").toFile()
        try {
            source.copyRecursively(tempDirectory.resolve("images"), overwrite = true)
            assertEquals(before, tempDirectory.resolve("images").fileManifest())
        } finally {
            tempDirectory.deleteRecursively()
        }
        assertEquals(before, source.fileManifest())
    }
}

private fun File.fileManifest(): Map<String, Pair<Long, String>> = walkTopDown()
    .filter(File::isFile)
    .associate { it.relativeTo(this).invariantSeparatorsPath to (it.length() to it.sha256()) }

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
