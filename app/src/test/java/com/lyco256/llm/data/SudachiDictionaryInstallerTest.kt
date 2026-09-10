package com.lyco256.llm.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SudachiDictionaryInstallerTest {
    @Test
    fun verifiedDictionaryIsInstalledOnceAndReused() = runBlocking {
        val fixture = sudachiFixture()
        val assets = FakeSudachiDictionaryAssets(fixture.metadataJson, fixture.archiveBytes)
        val directory = Files.createTempDirectory("sudachi-installer-").toFile()
        try {
            val installer = SudachiDictionaryInstaller(directory, assets)

            val first = installer.ensureInstalled()
            val second = installer.ensureInstalled()

            assertEquals(first, second)
            assertEquals(fixture.dictionaryBytes.toList(), first.readBytes().toList())
            assertEquals(1, assets.metadataOpenCount)
            assertEquals(1, assets.archiveOpenCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentFirstUseSharesOneAtomicInstallation() = runBlocking {
        val fixture = sudachiFixture()
        val assets = FakeSudachiDictionaryAssets(fixture.metadataJson, fixture.archiveBytes)
        val directory = Files.createTempDirectory("sudachi-installer-concurrent-").toFile()
        try {
            val installer = SudachiDictionaryInstaller(directory, assets)
            val files = coroutineScope {
                List(8) { async { installer.ensureInstalled() } }.awaitAll()
            }

            assertTrue(files.all { it.isFile })
            assertEquals(1, assets.metadataOpenCount)
            assertEquals(1, assets.archiveOpenCount)
            assertEquals(fixture.dictionaryBytes.toList(), files.first().readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptInstalledDictionaryIsReplacedOnlyAfterAValidTemporaryCopyExists() = runBlocking {
        val fixture = sudachiFixture()
        val assets = FakeSudachiDictionaryAssets(fixture.metadataJson, fixture.archiveBytes)
        val directory = Files.createTempDirectory("sudachi-installer-repair-").toFile()
        try {
            File(directory, "system_full.dic").writeText("corrupt")
            val installer = SudachiDictionaryInstaller(directory, assets)

            val repaired = installer.ensureInstalled()

            assertEquals(fixture.dictionaryBytes.toList(), repaired.readBytes().toList())
            assertFalse(File(directory, "system_full.dic.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiedDictionaryRemovesStaleTemporaryFileWithoutReinstalling() = runBlocking {
        val fixture = sudachiFixture()
        val assets = FakeSudachiDictionaryAssets(fixture.metadataJson, fixture.archiveBytes)
        val directory = Files.createTempDirectory("sudachi-installer-stale-temp-").toFile()
        try {
            val installer = SudachiDictionaryInstaller(directory, assets)
            val installed = installer.ensureInstalled()
            File(directory, "system_full.dic.tmp").writeText("stale")

            val reused = installer.ensureInstalled()

            assertEquals(installed, reused)
            assertFalse(File(directory, "system_full.dic.tmp").exists())
            assertEquals(fixture.dictionaryBytes.toList(), reused.readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dictionaryHashMismatchFailsWithoutLeavingAFinalFile() = runBlocking {
        val fixture = sudachiFixture()
        val mismatchedMetadata = fixture.metadataJson.replace(
            fixture.dictionaryBytes.sha256(),
            "0".repeat(64),
        )
        val assets = FakeSudachiDictionaryAssets(mismatchedMetadata, fixture.archiveBytes)
        val directory = Files.createTempDirectory("sudachi-installer-hash-mismatch-").toFile()
        try {
            val installer = SudachiDictionaryInstaller(directory, assets)

            val failure = runCatching { installer.ensureInstalled() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(File(directory, "system_full.dic").exists())
            assertFalse(File(directory, "system_full.dic.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingDictionaryEntryLeavesNoIncompleteFinalFile() = runBlocking {
        val fixture = sudachiFixture(archiveEntryName = "other.dic")
        val assets = FakeSudachiDictionaryAssets(fixture.metadataJson, fixture.archiveBytes)
        val directory = Files.createTempDirectory("sudachi-installer-failure-").toFile()
        try {
            val installer = SudachiDictionaryInstaller(directory, assets)

            val failure = runCatching { installer.ensureInstalled() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(File(directory, "system_full.dic").exists())
            assertFalse(File(directory, "system_full.dic.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun blankInputDoesNotInitializeDictionaryAndCloseIsIdempotent() = runBlocking {
        val fixture = sudachiFixture()
        val assets = FakeSudachiDictionaryAssets(fixture.metadataJson, fixture.archiveBytes)
        val directory = Files.createTempDirectory("sudachi-analyzer-lifecycle-").toFile()
        try {
            val analyzer = SudachiLexicalTextAnalyzer(SudachiDictionaryInstaller(directory, assets))

            assertEquals(SudachiLexicalTextAnalysis("", "", "", ""), analyzer.analyze(" \n"))
            assertEquals(0, analyzer.dictionaryInitializationCount)
            assertEquals(0, assets.archiveOpenCount)

            analyzer.close()
            analyzer.close()
            val failure = runCatching { analyzer.analyze("") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private data class SudachiFixture(
    val dictionaryBytes: ByteArray,
    val archiveBytes: ByteArray,
    val metadataJson: String,
)

private fun sudachiFixture(
    dictionaryEntryName: String = "sudachi-dictionary-20260723/system_full.dic",
    archiveEntryName: String = dictionaryEntryName,
): SudachiFixture {
    val dictionaryBytes = "small deterministic dictionary fixture".toByteArray()
    val archiveBytes = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(archiveEntryName))
            zip.write(dictionaryBytes)
            zip.closeEntry()
        }
    }.toByteArray()
    return SudachiFixture(
        dictionaryBytes = dictionaryBytes,
        archiveBytes = archiveBytes,
        metadataJson = """
            {
              "dictionaryVersion": "20260723",
              "archiveFileName": "sudachi-dictionary-20260723-full.zip",
              "archiveUrl": "https://example.invalid/sudachi-dictionary-20260723-full.zip",
              "archiveSha256": "${archiveBytes.sha256()}",
              "archiveByteSize": ${archiveBytes.size},
              "systemDictionaryEntryName": "$dictionaryEntryName",
              "systemDictionarySha256": "${dictionaryBytes.sha256()}",
              "systemDictionaryByteSize": ${dictionaryBytes.size}
            }
        """.trimIndent(),
    )
}

private class FakeSudachiDictionaryAssets(
    private val metadataJson: String,
    private val archiveBytes: ByteArray,
) : SudachiDictionaryAssetSource {
    var metadataOpenCount = 0
        private set
    var archiveOpenCount = 0
        private set

    override fun openMetadata() = ByteArrayInputStream(metadataJson.toByteArray()).also { metadataOpenCount += 1 }

    override fun openArchive() = ByteArrayInputStream(archiveBytes).also { archiveOpenCount += 1 }
}

private fun ByteArray.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(this)
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
