package com.lyco256.llm.data

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/** Durable file staging used by clip-deletion undo. */
internal class DurableClipDeleteUndoStore(
    filesDirectory: File,
    private val copyFile: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = false) },
) {
    private val root = File(filesDirectory, DIRECTORY_NAME)

    data class PreparedFiles(
        val metadata: List<UndoFileMetadata>,
        internal val originalFiles: List<OriginalFile>,
    )

    internal data class OriginalFile(val canonicalPath: String, val sizeBytes: Long, val sha256: String)

    fun prepare(clipId: Long, assets: List<AssetEntity>, imageDirectory: File): PreparedFiles {
        val imageRoot = canonical(imageDirectory)
        val stagingRoot = canonicalRoot()
        require(!isWithin(stagingRoot, imageRoot) && !isWithin(imageRoot, stagingRoot)) {
            "Undo stagingと画像保存先を分離できません"
        }
        val slotDirectory = File(stagingRoot, "clip_${clipId}_${UUID.randomUUID()}")
        return try {
            val originals = mutableListOf<OriginalFile>()
            val metadata = assets.mapNotNull { asset ->
                val path = asset.localPath?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val source = File(path)
                if (!source.exists()) return@mapNotNull null
                val canonicalSource = canonical(source)
                require(canonicalSource.isFile && isStrictChild(canonicalSource, imageRoot)) {
                    "管理画像ディレクトリ外のファイルはUndo stagingへコピーできません"
                }
                check(slotDirectory.mkdir() || slotDirectory.isDirectory) { "Undo stagingを作成できません" }
                val relative = "${slotDirectory.name}/${asset.id}_${safeName(canonicalSource.name)}"
                val destination = resolveRelative(relative)
                val temporary = File(destination.parentFile, ".${destination.name}.copying")
                copyFile(canonicalSource, temporary)
                check(temporary.isFile && temporary.length() == canonicalSource.length()) {
                    "Undo stagingへのコピー容量が一致しません"
                }
                val digest = sha256(canonicalSource)
                check(sha256(temporary) == digest) { "Undo stagingへのコピー内容が一致しません" }
                check(temporary.renameTo(destination)) { "Undo stagingへのコピーを確定できません" }
                originals += OriginalFile(canonicalSource.path, canonicalSource.length(), digest)
                UndoFileMetadata(asset.id, relative, destination.length(), digest)
            }
            PreparedFiles(metadata, originals.distinctBy(OriginalFile::canonicalPath))
        } catch (error: Throwable) {
            slotDirectory.deleteRecursively()
            throw error
        }
    }

    /** Restores verified bytes into the currently selected image directory. */
    fun restore(payload: ClipDeletedUndoPayload, imageDirectory: File): Map<Long, String> {
        val imageRoot = canonical(imageDirectory)
        check(imageRoot.mkdirs() || imageRoot.isDirectory) { "画像保存先を作成できません" }
        val assetsById = payload.assets.associateBy(AssetEntity::id)
        return payload.files.associate { metadata ->
            val asset = checkNotNull(assetsById[metadata.assetId]) { "画像のUndoデータが不正です" }
            val staged = resolveRelative(metadata.stagingRelativePath)
            verify(staged, metadata)
            val originalName = asset.localPath?.let(::File)?.name?.takeIf(String::isNotBlank)
                ?: "restored_${asset.id}.bin"
            val target = chooseTarget(imageRoot, safeName(originalName), asset.id, metadata)
            if (!target.exists()) {
                val temporary = File(imageRoot, ".undo_restore_${asset.id}.tmp")
                if (temporary.exists()) check(temporary.delete()) { "復元用一時ファイルを更新できません" }
                copyFile(staged, temporary)
                try {
                    verify(temporary, metadata)
                    check(temporary.renameTo(target)) { "画像の復元を確定できません" }
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
            asset.id to target.absolutePath
        }
    }

    /** Best-effort and payload-scoped: failure intentionally leaves a safe orphan. */
    fun cleanup(payload: ClipDeletedUndoPayload) {
        cleanup(payload.files)
    }

    fun discardPrepared(metadata: List<UndoFileMetadata>) {
        cleanup(metadata)
    }

    /** Deletes only an unchanged file whose exact canonical path and content were staged. */
    fun discardOriginals(prepared: PreparedFiles) {
        prepared.originalFiles.forEach { original ->
            runCatching {
                val file = File(original.canonicalPath)
                val canonical = file.canonicalFile
                if (
                    canonical.path == original.canonicalPath && canonical.isFile &&
                    canonical.length() == original.sizeBytes && sha256(canonical) == original.sha256
                ) {
                    canonical.delete()
                }
            }
        }
    }

    private fun cleanup(metadata: List<UndoFileMetadata>) {
        val files = metadata.mapNotNull { item -> runCatching { resolveRelative(item.stagingRelativePath) }.getOrNull() }
        files.forEach { file -> runCatching { if (file.isFile) file.delete() } }
        files.mapNotNull(File::getParentFile).distinctBy(File::getPath).sortedByDescending { it.path.length }.forEach { directory ->
            runCatching {
                if (directory != canonicalRoot() && isStrictChild(directory.canonicalFile, canonicalRoot())) directory.delete()
            }
        }
    }

    private fun chooseTarget(root: File, name: String, assetId: Long, metadata: UndoFileMetadata): File {
        val preferred = File(root, name)
        if (!preferred.exists()) return preferred
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 0
        while (true) {
            val suffix = if (index == 0) "_restored_$assetId" else "_restored_${assetId}_$index"
            val candidate = File(root, "$stem$suffix$extension")
            if (!candidate.exists() || matches(candidate, metadata)) return candidate
            index++
        }
    }

    private fun verify(file: File, metadata: UndoFileMetadata) {
        check(file.isFile && file.length() == metadata.sizeBytes && sha256(file) == metadata.sha256) {
            "Undo stagingの画像を検証できません"
        }
    }

    private fun matches(file: File, metadata: UndoFileMetadata): Boolean =
        file.isFile && file.length() == metadata.sizeBytes && runCatching { sha256(file) == metadata.sha256 }.getOrDefault(false)

    private fun resolveRelative(relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "Undo staging pathが不正です" }
        val rootFile = canonicalRoot()
        val resolved = canonical(File(rootFile, relativePath))
        require(isStrictChild(resolved, rootFile)) { "Undo staging pathが管理領域外です" }
        return resolved
    }

    private fun canonicalRoot(): File {
        check(root.mkdirs() || root.isDirectory) { "Undo stagingを作成できません" }
        return canonical(root)
    }

    private fun canonical(file: File): File = runCatching { file.canonicalFile }
        .getOrElse { throw IllegalStateException("ファイルのパスを解決できません", it) }

    private fun isWithin(file: File, parent: File): Boolean = file == parent || isStrictChild(file, parent)

    private fun isStrictChild(file: File, parent: File): Boolean =
        file.path.startsWith(parent.path + File.separator)

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        .ifBlank { "image" }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        internal const val DIRECTORY_NAME = "clip_delete_undo_staging"
    }
}
