package com.lyco256.llm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONArray

/** Imports only the benchmark target's internal snapshot handoff. */
internal object BenchmarkSnapshotImporter {
    private const val ARCHIVE_NAME = "media-grid-snapshot.zip"
    private const val HANDOFF_DIRECTORY = "benchmark-handoff"
    private const val VALIDATION_DIRECTORY = "media-grid-validation"

    private data class SnapshotValidation(
        val activeClips: Long,
        val activeMediaAssets: Long,
        val taggedMediaClips: Long,
        val localMediaAssets: Long,
        val persistentPreviews: Int = 0,
    )

    private data class OriginalMetadata(
        val snapshotName: String,
        val originalPath: String,
        val size: Long,
        val modified: Long,
    )

    fun prepareBenchmarkStorage(context: Context) {
        if (BuildConfig.BUILD_TYPE != "benchmark") return
        // This changes only benchmark target state. No production preference
        // is read or copied; it prevents a stale benchmark-only SD-card
        // selection from bypassing the imported internal database.
        context.getSharedPreferences(BuildConfig.STORAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("selected_id", "internal")
            .remove("selected_path")
            .remove("migration_phase")
            .remove("migration_source")
            .remove("migration_target")
            .commit()
    }

    private fun importSnapshot(context: Context, handoff: File) {
        requireNotNull(context.getExternalFilesDir(VALIDATION_DIRECTORY)).deleteRecursively()
        val staging = File(context.cacheDir, "benchmark-snapshot-staging")
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            ZipInputStream(handoff.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalizedName = entry.name.replace('\\', '/')
                    require(!normalizedName.contains("..") && !File(normalizedName).isAbsolute) { "Invalid snapshot entry" }
                    val target = File(staging, normalizedName)
                    require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        "Snapshot entry escapes staging directory"
                    }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                }
            }
            val database = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
            database.parentFile?.mkdirs()
            listOf(database, File("${database.path}-wal"), File("${database.path}-shm")).forEach { it.delete() }
            copyRequired(File(staging, "db/like_list_manager.db"), database)
            copyOptional(File(staging, "db/like_list_manager.db-wal"), File("${database.path}-wal"))
            copyOptional(File(staging, "db/like_list_manager.db-shm"), File("${database.path}-shm"))

            val images = File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY)
            images.deleteRecursively()
            images.mkdirs()
            val originals = File(staging, "originals")
            val originalMetadata = readOriginalMetadata(File(staging, "original-metadata.json"))
            originalMetadata.forEach { metadata ->
                val target = File(images, metadata.snapshotName)
                copyRequired(File(originals, metadata.snapshotName), target)
                require(target.length() == metadata.size) { "Snapshot original size mismatch: ${metadata.snapshotName}" }
                require(target.setLastModified(metadata.modified)) { "Could not restore snapshot original timestamp" }
            }

            val copiedPreviews = File(staging, "previews")
            require(copiedPreviews.isDirectory) { "Persistent preview snapshot is missing" }
            val previewFiles = copiedPreviews.listFiles()
                ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                .orEmpty()
            require(previewFiles.isNotEmpty()) { "Persistent preview snapshot contains no JPEGs" }
            val previewDirectory = File(context.filesDir, "media_grid_previews/v1")
            copyDirectory(copiedPreviews, previewDirectory)
            require(previewFiles.all { File(previewDirectory, it.name).isFile }) {
                "Persistent preview snapshot copy is incomplete"
            }
            rewriteLocalPaths(database, images, originalMetadata.associateBy { it.originalPath })
            val validation = validateMediaGridInput(database, images).copy(persistentPreviews = previewFiles.size)
            writeSnapshotValidation(context, database, validation)
        } catch (t: Throwable) {
            deleteBenchmarkData(context)
            throw IllegalStateException("Benchmark snapshot import failed", t)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun prepareRequiredSnapshot(context: Context) {
        check(BuildConfig.BUILD_TYPE == "benchmark") { "Benchmark snapshot setup is benchmark-only" }
        val validationDirectory = requireNotNull(context.getExternalFilesDir(VALIDATION_DIRECTORY))
        val handoff = File(File(context.filesDir, HANDOFF_DIRECTORY), ARCHIVE_NAME)
        val marker = File(validationDirectory, "snapshot.json")
        try {
            // State machine is intentionally centralized here:
            // 1) a valid completion marker means the snapshot is already imported;
            // 2) otherwise an internal handoff is the normal import state;
            // 3) only marker-less/no-handoff state fails.
            if (marker.isFile && runCatching { requirePreparedSnapshot(context) }.isSuccess) {
                handoff.delete()
                return
            }
            if (!handoff.isFile) {
                throw IllegalStateException(
                    "Benchmark snapshot is not prepared: completion marker is invalid or missing and " +
                        "internal handoff ZIP is missing (handoff=${handoff.absolutePath}, marker=${marker.absolutePath})",
                )
            }
            prepareBenchmarkStorage(context)
            validationDirectory.deleteRecursively()
            deleteBenchmarkData(context)
            importSnapshot(context, handoff)
            requirePreparedSnapshot(context)
            handoff.delete()
        } catch (t: Throwable) {
            deleteBenchmarkData(context)
            validationDirectory.deleteRecursively()
            throw IllegalStateException("Required benchmark snapshot setup failed", t)
        }
    }

    fun requirePreparedSnapshot(context: Context) {
        check(BuildConfig.BUILD_TYPE == "benchmark") { "Benchmark snapshot validation is benchmark-only" }
        val database = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
        val marker = File(requireNotNull(context.getExternalFilesDir(VALIDATION_DIRECTORY)), "snapshot.json")
        require(marker.isFile) { "Required benchmark snapshot marker is missing" }
        val markerText = marker.readText(Charsets.UTF_8)
        require(markerText.contains("\"ready\":true")) { "Required benchmark snapshot marker is not ready" }
        require(markerText.contains("\"databasePath\":\"${database.absolutePath}\"")) {
            "Required benchmark snapshot DB path does not match Room DB path: ${database.absolutePath}"
        }
        require(Regex("\"persistentPreviews\":([1-9]\\d*)").containsMatchIn(markerText)) {
            "Required benchmark snapshot has no persistent previews"
        }
        validateMediaGridInput(database, File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY))
    }

    fun writeBenchmarkDatabaseDiagnostics(context: Context) {
        if (BuildConfig.BUILD_TYPE != "benchmark") return
        val database = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
        val validation = validateMediaGridInput(database, File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY))
        val directory = requireNotNull(context.getExternalFilesDir(VALIDATION_DIRECTORY))
        directory.mkdirs()
        File(directory, "runtime.json").writeText(
            "{\"ready\":true,\"databasePath\":\"${database.absolutePath}\"," +
                "\"activeClips\":${validation.activeClips}," +
                "\"activeMediaAssets\":${validation.activeMediaAssets}," +
                "\"taggedMediaClips\":${validation.taggedMediaClips}," +
                "\"localMediaAssets\":${validation.localMediaAssets}}",
            Charsets.UTF_8,
        )
    }

    fun writeEffectiveMediaGridValidation(
        context: Context,
        sourceRevision: Long,
        sourceClipCount: Int,
        sourceMediaAssetCount: Int,
        sourceTaggedClipCount: Int,
        matchingClipCount: Int,
        matchingMediaCount: Int,
    ) {
        if (BuildConfig.BUILD_TYPE != "benchmark") return
        val directory = requireNotNull(context.getExternalFilesDir(VALIDATION_DIRECTORY))
        directory.mkdirs()
        File(directory, "effective.json").writeText(
            "{\"ready\":${matchingClipCount > 0 && matchingMediaCount > 0}," +
                "\"sourceRevision\":$sourceRevision," +
                "\"sourceClipCount\":$sourceClipCount," +
                "\"sourceMediaAssetCount\":$sourceMediaAssetCount," +
                "\"sourceTaggedClipCount\":$sourceTaggedClipCount," +
                "\"matchingClipCount\":$matchingClipCount," +
                "\"matchingMediaCount\":$matchingMediaCount}",
            Charsets.UTF_8,
        )
    }

    private fun rewriteLocalPaths(
        database: File,
        images: File,
        metadataByOriginalPath: Map<String, OriginalMetadata>,
    ) {
        val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.rawQuery("SELECT id, mediaKey, localPath FROM assets", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val oldPath = if (cursor.isNull(2)) null else cursor.getString(2)
                    val metadata = oldPath?.let(metadataByOriginalPath::get)
                    val copied = metadata?.let { File(images, it.snapshotName) }?.takeIf { it.isFile }
                    val values = ContentValues().apply {
                        put("localPath", copied?.absolutePath)
                        put("downloadState", if (copied != null) "downloaded" else "failed")
                    }
                    db.update("assets", values, "id = ?", arrayOf(id.toString()))
                }
            }
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                require(cursor.moveToFirst()) { "Benchmark WAL checkpoint returned no result" }
            }
        } finally {
            db.close()
        }
    }

    private fun readOriginalMetadata(file: File): List<OriginalMetadata> {
        require(file.isFile) { "Snapshot original metadata is missing" }
        val array = JSONArray(file.readText(Charsets.UTF_8))
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    OriginalMetadata(
                        snapshotName = item.getString("snapshotName"),
                        originalPath = item.getString("originalPath"),
                        size = item.getLong("size"),
                        modified = item.getLong("modified"),
                    ),
                )
            }
        }
    }

    private fun writeSnapshotValidation(context: Context, database: File, validation: SnapshotValidation) {
        val directory = requireNotNull(context.getExternalFilesDir(VALIDATION_DIRECTORY))
        directory.mkdirs()
        File(directory, "snapshot.json").writeText(
            "{\"ready\":true,\"databasePath\":\"${database.absolutePath}\"," +
                "\"activeClips\":${validation.activeClips}," +
                "\"activeMediaAssets\":${validation.activeMediaAssets}," +
                "\"taggedMediaClips\":${validation.taggedMediaClips}," +
                "\"localMediaAssets\":${validation.localMediaAssets}," +
                "\"persistentPreviews\":${validation.persistentPreviews}," +
                "\"dataInode\":${Os.stat((context.filesDir.parentFile ?: context.filesDir).absolutePath).st_ino}}",
            Charsets.UTF_8,
        )
    }

    private fun validateMediaGridInput(database: File, images: File): SnapshotValidation {
        val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            fun count(sql: String): Long = db.rawQuery(sql, null).use { cursor ->
                require(cursor.moveToFirst()) { "Benchmark validation query returned no row" }
                cursor.getLong(0)
            }
            val activeClips = count("SELECT COUNT(*) FROM clips WHERE isDeleted = 0")
            val activeMediaAssets = count(
                "SELECT COUNT(*) FROM clips INNER JOIN assets ON assets.clipId = clips.id " +
                    "WHERE clips.isDeleted = 0 AND assets.type IN ('photo', 'video_thumbnail')",
            )
            val taggedMediaClips = count(
                "SELECT COUNT(DISTINCT clips.id) FROM clips " +
                    "INNER JOIN clip_tags ON clip_tags.clipId = clips.id " +
                    "INNER JOIN assets ON assets.clipId = clips.id " +
                    "WHERE clips.isDeleted = 0 AND assets.type IN ('photo', 'video_thumbnail')",
            )
            val localMediaAssets = count(
                "SELECT COUNT(*) FROM clips INNER JOIN assets ON assets.clipId = clips.id " +
                    "WHERE clips.isDeleted = 0 AND assets.type IN ('photo', 'video_thumbnail') " +
                    "AND assets.localPath IS NOT NULL",
            )
            require(activeClips > 0 && activeMediaAssets > 0 && taggedMediaClips > 0 && localMediaAssets > 0) {
                "Benchmark snapshot has no usable classified media-grid input " +
                    "(activeClips=$activeClips, activeMediaAssets=$activeMediaAssets, " +
                    "taggedMediaClips=$taggedMediaClips, localMediaAssets=$localMediaAssets)"
            }
            require(images.isDirectory) { "Benchmark original-image directory is missing" }
            return SnapshotValidation(activeClips, activeMediaAssets, taggedMediaClips, localMediaAssets)
        } finally {
            db.close()
        }
    }

    private fun deleteBenchmarkData(context: Context) {
        val database = context.getDatabasePath(BuildConfig.STORAGE_DATABASE_NAME)
        listOf(database, File("${database.path}-wal"), File("${database.path}-shm")).forEach { it.delete() }
        File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY).deleteRecursively()
    }

    private fun copyRequired(source: File, target: File) {
        require(source.isFile) { "Missing required snapshot file: ${source.name}" }
        source.copyTo(target, overwrite = true)
    }

    private fun copyOptional(source: File, target: File) { if (source.isFile) source.copyTo(target, overwrite = true) }

    private fun copyDirectory(source: File, target: File) {
        source.walkTopDown().forEach { file ->
            val destination = File(target, file.relativeTo(source).path)
            if (file.isDirectory) destination.mkdirs() else { destination.parentFile?.mkdirs(); file.copyTo(destination, overwrite = true) }
        }
    }
}
