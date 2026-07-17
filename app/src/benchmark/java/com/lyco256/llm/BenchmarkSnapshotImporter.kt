package com.lyco256.llm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import java.io.File
import java.security.MessageDigest
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
        val cachedJpegs: Int = 0,
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

            val thumbnailCache = File(context.cacheDir, "media_grid_thumbnails")
            thumbnailCache.deleteRecursively()
            val copiedCache = File(staging, "cache/media_grid_thumbnails")
            if (copiedCache.isDirectory) copyDirectory(copiedCache, thumbnailCache)
            val remappedCachedJpegs = rewriteLocalPaths(database, images, thumbnailCache, originalMetadata.associateBy { it.originalPath })
            require(remappedCachedJpegs > 0) { "No copied thumbnail JPEG matched benchmark media cache keys" }
            val validation = validateMediaGridInput(database, images).copy(cachedJpegs = remappedCachedJpegs)
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
        require(Regex("\"cachedJpegs\":([1-9]\\d*)").containsMatchIn(markerText)) {
            "Required benchmark snapshot has no mapped cached JPEGs"
        }
        validateMediaGridInput(database, File(context.filesDir, BuildConfig.STORAGE_IMAGES_DIRECTORY))
    }

    fun resetGeneratedResults(context: Context) {
        if (BuildConfig.BUILD_TYPE == "benchmark") File(context.cacheDir, "media_grid_thumbnails_generated").deleteRecursively()
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
        thumbnailCache: File,
        metadataByOriginalPath: Map<String, OriginalMetadata>,
    ): Int {
        var remappedCachedJpegs = 0
        val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.rawQuery("SELECT id, mediaKey, localPath FROM assets", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val mediaKey = cursor.getString(1)
                    val oldPath = if (cursor.isNull(2)) null else cursor.getString(2)
                    val metadata = oldPath?.let(metadataByOriginalPath::get)
                    val copied = metadata?.let { File(images, it.snapshotName) }?.takeIf { it.isFile }
                    if (oldPath != null && metadata != null && copied != null) {
                        val oldKey = thumbnailKey(id, mediaKey, oldPath, metadata.size, metadata.modified)
                        val newKey = thumbnailKey(id, mediaKey, copied.absolutePath, copied.length(), copied.lastModified())
                        val oldCache = File(thumbnailCache, "$oldKey.jpg")
                        if (oldCache.isFile) {
                            oldCache.copyTo(File(thumbnailCache, "$newKey.jpg"), overwrite = true)
                            remappedCachedJpegs += 1
                        }
                    }
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
        return remappedCachedJpegs
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

    private fun thumbnailKey(assetId: Long, mediaKey: String, path: String, size: Long, modified: Long): String {
        val value = "$assetId|$mediaKey|$path|$size|$modified"
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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
                "\"cachedJpegs\":${validation.cachedJpegs}," +
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
        File(context.cacheDir, "media_grid_thumbnails").deleteRecursively()
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
