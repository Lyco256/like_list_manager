package com.lyco256.llm.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

/** Common verified, atomic installer for model assets owned by one runtime directory. */
internal interface LocalAssetSource {
    fun open(path: String): InputStream
}

internal data class LocalRuntimeAssetSpec(
    val name: String,
    val sha256: String,
)

internal class LocalRuntimeAssetInstaller(
    private val source: LocalAssetSource,
    private val modelDirectory: File,
    private val expectedAssets: List<LocalRuntimeAssetSpec>,
    private val expectedRepository: String,
    private val expectedRevision: String,
    private val assetRoot: String,
    private val metadataFileName: String,
    private val label: String,
) {
    fun install(): Map<String, File> {
        check(!modelDirectory.exists() || modelDirectory.isDirectory) {
            "$label model path is not a directory: ${modelDirectory.absolutePath}"
        }
        val metadata = readAndValidateMetadata()
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IllegalStateException("Failed to create $label model directory: ${modelDirectory.absolutePath}")
        }
        cleanInterruptedCopies()

        metadata.forEach { asset ->
            val destination = File(modelDirectory, asset.name)
            if (!isValid(destination, asset)) copyAssetAtomically(asset, destination)
            check(isValid(destination, asset)) {
                "Installed $label asset failed verification: ${asset.name}"
            }
        }
        return metadata.associate { it.name to File(modelDirectory, it.name) }
    }

    private fun readAndValidateMetadata(): List<MetadataAsset> {
        val metadataPath = "$assetRoot/$metadataFileName"
        val json = source.open(metadataPath).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        check(json.getString("repository") == expectedRepository) {
            "Unexpected $label repository in generated metadata"
        }
        check(json.getString("revision") == expectedRevision) {
            "Unexpected $label revision in generated metadata"
        }

        val expected = expectedAssets.associateBy { it.name }
        val files = json.getJSONArray("files")
        check(files.length() == expected.size) {
            "$label metadata contains an unexpected file count"
        }
        val metadata = buildList {
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val name = item.getString("name")
                val expectedAsset = expected[name] ?: error("Unexpected $label asset in metadata: $name")
                val sha256 = item.getString("sha256")
                check(sha256.equals(expectedAsset.sha256, ignoreCase = true)) {
                    "$label metadata SHA-256 does not match the pinned value: $name"
                }
                val byteSize = item.getLong("byteSize")
                check(byteSize > 0L) { "$label asset has an invalid byte size: $name" }
                add(MetadataAsset(name, sha256.lowercase(), byteSize))
            }
        }
        check(metadata.map { it.name }.toSet() == expected.keys) {
            "$label metadata file set does not match the pinned asset set"
        }
        return metadata
    }

    private fun cleanInterruptedCopies() {
        modelDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".partial") }
            .forEach { partial ->
                check(partial.delete()) { "Failed to remove interrupted $label copy: ${partial.name}" }
            }
    }

    private fun isValid(file: File, asset: MetadataAsset): Boolean {
        if (!file.isFile || file.length() != asset.byteSize) return false
        return runCatching { sha256(file).equals(asset.sha256, ignoreCase = true) }.getOrDefault(false)
    }

    private fun copyAssetAtomically(asset: MetadataAsset, destination: File) {
        val partial = File(modelDirectory, ".${asset.name}.partial")
        if (partial.exists() && !partial.delete()) {
            throw IllegalStateException("Failed to remove interrupted $label copy: ${partial.name}")
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteSize = 0L
            source.open("$assetRoot/${asset.name}").use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        byteSize += read.toLong()
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            val copiedHash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            check(byteSize == asset.byteSize && copiedHash.equals(asset.sha256, ignoreCase = true)) {
                "Generated $label asset failed verification: ${asset.name}"
            }
            try {
                Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (partial.exists() && !partial.delete()) {
                throw IllegalStateException("Failed to remove incomplete $label copy: ${partial.name}")
            }
        }
    }

    private data class MetadataAsset(
        val name: String,
        val sha256: String,
        val byteSize: Long,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
