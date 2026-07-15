package com.lyco256.llm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.ZipInputStream

/** Imports only the benchmark target's external, app-specific snapshot handoff. */
internal object BenchmarkSnapshotImporter {
    private const val ARCHIVE_NAME = "media-grid-snapshot.zip"
    private const val VALIDATION_DIRECTORY = "media-grid-validation"

    private data class SnapshotValidation(
        val activeClips: Long,
        val activeMediaAssets: Long,
        val taggedMediaClips: Long,
        val localMediaAssets: Long,
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

    fun importIfPresent(context: Context) {
        if (BuildConfig.BUILD_TYPE != "benchmark") return
        val handoff = File(requireNotNull(context.getExternalFilesDir("media-grid-snapshot")), ARCHIVE_NAME)
        if (!handoff.isFile) return
        requireNotNull(context.getExternalFilesDir(VALIDATION_DIRECTORY)).deleteRecursively()
        val staging = File(context.cacheDir, "benchmark-snapshot-staging")
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            ZipInputStream(handoff.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.name.contains("..") && !File(entry.name).isAbsolute) { "Invalid snapshot entry" }
                    val target = File(staging, entry.name)
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
            if (originals.isDirectory) originals.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(images, it.name), overwrite = true) }

            val thumbnailCache = File(context.cacheDir, "media_grid_thumbnails")
            thumbnailCache.deleteRecursively()
            val copiedCache = File(staging, "cache/media_grid_thumbnails")
            if (copiedCache.isDirectory) copyDirectory(copiedCache, thumbnailCache)
            rewriteLocalPaths(database, images)
            val validation = validateMediaGridInput(database, images)
            writeSnapshotValidation(context, database, validation)
        } catch (t: Throwable) {
            deleteBenchmarkData(context)
            throw IllegalStateException("Benchmark snapshot import failed", t)
        } finally {
            staging.deleteRecursively()
            handoff.delete()
        }
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

    private fun rewriteLocalPaths(database: File, images: File) {
        val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.rawQuery("SELECT id, localPath FROM assets", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val oldPath = if (cursor.isNull(1)) null else cursor.getString(1)
                    val copied = oldPath?.let { File(images, File(it).name) }?.takeIf { it.isFile }
                    val values = ContentValues().apply {
                        put("localPath", copied?.absolutePath)
                        put("downloadState", if (copied != null) "downloaded" else "failed")
                    }
                    db.update("assets", values, "id = ?", arrayOf(id.toString()))
                }
            }
            db.execSQL("PRAGMA wal_checkpoint(FULL)")
        } finally {
            db.close()
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
                "\"localMediaAssets\":${validation.localMediaAssets}}",
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
