package com.lyco256.llm.data

import android.content.Context
import android.os.Environment
import androidx.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class PostStorageType { INTERNAL, EXTERNAL }

data class PostStorageLocation(
    val id: String,
    val type: PostStorageType,
    val displayName: String,
    val path: String,
    val isAvailable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long?,
    val isCurrent: Boolean,
)

data class PostStorageState(
    val locations: List<PostStorageLocation> = emptyList(),
    val currentLocationId: String = INTERNAL_ID,
    val isAvailable: Boolean = true,
    val isRefreshing: Boolean = false,
    val isMigrating: Boolean = false,
    val migrationMessage: String? = null,
) {
    companion object {
        const val INTERNAL_ID = "internal"
    }
}

data class PostStorageEstimate(
    val target: PostStorageLocation,
    val clipCount: Int,
    val fileCount: Int,
    val totalBytes: Long,
)

data class PostStorageConfig(
    val databaseName: String = "like_list_manager.db",
    val imagesDirectory: String = "images",
    val dataDirectory: String = "post_data",
    val preferencesName: String = "post_storage_settings",
)

class PostStorageManager(
    private val context: Context,
    private val config: PostStorageConfig = PostStorageConfig(),
) {
    private data class StoragePaths(
        val id: String,
        val type: PostStorageType,
        val displayName: String,
        val root: File,
        val database: File,
        val images: File,
        val available: Boolean,
    )

    private val preferences = context.getSharedPreferences(config.preferencesName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _database = MutableStateFlow<LikeListDatabase?>(null)
    private val _state = MutableStateFlow(PostStorageState())
    private var pendingEstimateSourceId: String? = null
    private var pendingEstimateTargetId: String? = null
    private var pendingEstimateBytes: Long? = null

    val database: StateFlow<LikeListDatabase?> = _database.asStateFlow()
    val state: StateFlow<PostStorageState> = _state.asStateFlow()

    init {
        recoverInterruptedMigration()
        openSelectedDatabase()
        finishSwitchedMigration()
    }

    suspend fun <T> withDatabase(block: suspend (LikeListDatabase) -> T): T = mutex.withLock {
        check(!_state.value.isMigrating) { "保存先を移動中です" }
        val database = _database.value
            ?: error("選択した保存先を利用できません。SDカードを再装着するか保存先を変更してください")
        block(database)
    }

    fun imageDirectory(): File {
        check(!_state.value.isMigrating) { "保存先を移動中です" }
        val selected = selectedPaths()
        check(selected.available) { "選択した保存先を利用できません" }
        return selected.images.also { it.mkdirs() }
    }

    suspend fun refreshLocations() {
        _state.value = _state.value.copy(isRefreshing = true, migrationMessage = null)
        val currentDatabase = _database.value
        val currentUsage = if (currentDatabase != null) managedUsage(currentDatabase) else null
        publishState(currentUsageBytes = currentUsage)
    }

    suspend fun estimateMove(targetId: String): PostStorageEstimate = withDatabase { database ->
        val target = locationForId(targetId) ?: error("移動先を利用できません")
        val source = selectedPaths()
        val assetStats = database.clipDao().getStoredAssetStats()
        val databaseFiles = databaseFiles(source).filter(File::exists)
        val estimate = PostStorageEstimate(
            target = target,
            clipCount = database.clipDao().countClips(),
            fileCount = assetStats.count + databaseFiles.size,
            totalBytes = assetStats.totalBytes + databaseFiles.sumOf(File::length),
        )
        pendingEstimateSourceId = source.id
        pendingEstimateTargetId = target.id
        pendingEstimateBytes = estimate.totalBytes
        estimate
    }

    suspend fun moveTo(targetId: String): Result<Unit> = mutex.withLock {
        val source = selectedPaths()
        val target = availablePaths().firstOrNull { it.id == targetId }
            ?: return@withLock Result.failure(IllegalStateException("移動先を利用できません"))
        if (source.id == target.id) return@withLock Result.success(Unit)
        val sourceDatabase = _database.value
        if (!source.available || sourceDatabase == null) {
            return@withLock Result.failure(IllegalStateException("現在の保存先を読み取れないため移動できません"))
        }
        if (!target.root.mkdirs() && !target.root.isDirectory) {
            return@withLock Result.failure(IllegalStateException("移動先フォルダを作成できません"))
        }
        val requiredBytes = if (
            pendingEstimateSourceId == source.id &&
            pendingEstimateTargetId == target.id
        ) {
            pendingEstimateBytes ?: managedUsage(sourceDatabase)
        } else {
            managedUsage(sourceDatabase)
        }
        pendingEstimateSourceId = null
        pendingEstimateTargetId = null
        pendingEstimateBytes = null
        if (target.root.usableSpace < requiredBytes + MIN_FREE_BYTES) {
            return@withLock Result.failure(IllegalStateException("移動先の空き容量が不足しています"))
        }

        _state.value = _state.value.copy(isMigrating = true, migrationMessage = "投稿データを移動しています")
        preferences.edit()
            .putString(KEY_MIGRATION_PHASE, PHASE_COPYING)
            .putString(KEY_MIGRATION_SOURCE, source.id)
            .putString(KEY_MIGRATION_TARGET, target.id)
            .commit()

        val tempDatabase = File(target.database.parentFile, "${target.database.name}.moving")
        val tempImages = File(target.images.parentFile, "${target.images.name}.moving")
        try {
            _database.value?.openHelper?.writableDatabase
                ?.query("PRAGMA wal_checkpoint(FULL)")
                ?.use { cursor -> while (cursor.moveToNext()) Unit }
            _database.value?.close()
            _database.value = null

            deleteDatabaseFiles(tempDatabase)
            tempImages.deleteRecursively()
            tempDatabase.parentFile?.mkdirs()
            tempImages.mkdirs()
            source.database.copyTo(tempDatabase, overwrite = true)
            copyDirectory(source.images, tempImages)
            val sourceImages = if (source.images.exists()) source.images.walkTopDown().filter { it.isFile }.toList() else emptyList()
            val copiedImages = tempImages.walkTopDown().filter { it.isFile }.toList()
            check(sourceImages.size == copiedImages.size) { "画像ファイル数が一致しません" }
            check(sourceImages.sumOf(File::length) == copiedImages.sumOf(File::length)) { "画像ファイル容量が一致しません" }
            check(tempDatabase.length() == source.database.length()) { "データベース容量が一致しません" }

            val validationDatabase = buildDatabase(tempDatabase)
            validationDatabase.clipDao().countClips()
            validationDatabase.clipDao().getAllAssets().forEach { asset ->
                val oldPath = asset.localPath ?: return@forEach
                val targetFile = File(target.images, File(oldPath).name)
                validationDatabase.clipDao().updateAsset(asset.copy(localPath = targetFile.absolutePath))
            }
            validationDatabase.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
                .use { cursor -> while (cursor.moveToNext()) Unit }
            validationDatabase.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "移動先データベースの検証に失敗しました" }
            }
            validationDatabase.close()

            deleteDatabaseFiles(target.database)
            target.images.deleteRecursively()
            moveFile(tempDatabase, target.database)
            moveDirectory(tempImages, target.images)
            deleteDatabaseFiles(tempDatabase)

            preferences.edit()
                .putString(KEY_SELECTED_ID, target.id)
                .putString(KEY_SELECTED_PATH, target.root.absolutePath)
                .putString(KEY_MIGRATION_PHASE, PHASE_SWITCHED)
                .commit()

            val targetDatabase = buildDatabase(target.database)
            _database.value = targetDatabase
            deleteStorageData(source)
            clearMigrationState()
            publishState(currentUsageBytes = managedUsage(targetDatabase))
            Result.success(Unit)
        } catch (error: Exception) {
            deleteDatabaseFiles(tempDatabase)
            tempImages.deleteRecursively()
            preferences.edit()
                .putString(KEY_SELECTED_ID, source.id)
                .putString(KEY_SELECTED_PATH, source.root.absolutePath)
                .commit()
            clearMigrationState()
            val sourceDatabase = runCatching { buildDatabase(source.database) }.getOrNull()
            _database.value = sourceDatabase
            publishState(
                message = error.message ?: "投稿データの移動に失敗しました",
                currentUsageBytes = sourceDatabase?.let { managedUsage(it) },
            )
            Result.failure(error)
        }
    }

    private fun openSelectedDatabase() {
        val selected = selectedPaths()
        if (selected.available) {
            selected.database.parentFile?.mkdirs()
            selected.images.mkdirs()
            _database.value = buildDatabase(selected.database)
        }
        publishState(
            message = if (selected.available) null else "選択したSDカードを利用できません",
            includeUsage = false,
        )
    }

    private fun buildDatabase(file: File): LikeListDatabase = Room.databaseBuilder(
        context,
        LikeListDatabase::class.java,
        file.absolutePath,
    ).addMigrations(
        LikeListDatabase.MIGRATION_1_2,
        LikeListDatabase.MIGRATION_2_3,
        LikeListDatabase.MIGRATION_3_4,
    ).build()

    private fun selectedPaths(): StoragePaths {
        val selectedId = preferences.getString(KEY_SELECTED_ID, PostStorageState.INTERNAL_ID).orEmpty()
        return availablePaths().firstOrNull { it.id == selectedId }
            ?: unavailableSelectedPaths(selectedId)
    }

    private fun availablePaths(): List<StoragePaths> {
        val internal = StoragePaths(
            id = PostStorageState.INTERNAL_ID,
            type = PostStorageType.INTERNAL,
            displayName = "内部ストレージ",
            root = context.filesDir,
            database = context.getDatabasePath(config.databaseName),
            images = File(context.filesDir, config.imagesDirectory),
            available = true,
        )
        val external = context.getExternalFilesDirs(null)
            .filterNotNull()
            .filter { Environment.isExternalStorageRemovable(it) }
            .mapIndexed { index, directory ->
                val root = File(directory, config.dataDirectory)
                val canonical = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
                StoragePaths(
                    id = "external:$canonical",
                    type = PostStorageType.EXTERNAL,
                    displayName = if (index == 0) "SDカード" else "SDカード ${index + 1}",
                    root = root,
                    database = File(root, config.databaseName),
                    images = File(root, config.imagesDirectory),
                    available = Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED,
                )
            }
        return listOf(internal) + external
    }

    private fun unavailableSelectedPaths(selectedId: String): StoragePaths {
        val savedPath = preferences.getString(KEY_SELECTED_PATH, "").orEmpty()
        val root = File(savedPath.ifBlank { File(context.filesDir, "missing_storage").absolutePath })
        return StoragePaths(
            id = selectedId,
            type = PostStorageType.EXTERNAL,
            displayName = "選択中のSDカード",
            root = root,
            database = File(root, config.databaseName),
            images = File(root, config.imagesDirectory),
            available = false,
        )
    }

    private fun locationForId(id: String): PostStorageLocation? = locations().firstOrNull { it.id == id && it.isAvailable }

    private fun locations(
        includeUsage: Boolean = true,
        currentUsageBytes: Long? = null,
    ): List<PostStorageLocation> {
        val selected = selectedPaths()
        val paths = availablePaths().toMutableList()
        if (paths.none { it.id == selected.id }) paths += selected
        return paths.map { path ->
            if (path.available) path.root.mkdirs()
            val usedBytes = if (includeUsage) {
                if (path.id == selected.id && currentUsageBytes != null) {
                    currentUsageBytes
                } else {
                    dataFiles(path).sumOf { if (it.isFile) it.length() else 0L }
                }
            } else {
                0L
            }
            PostStorageLocation(
                id = path.id,
                type = path.type,
                displayName = path.displayName,
                path = path.root.absolutePath,
                isAvailable = path.available,
                totalBytes = if (path.available) path.root.totalSpace else 0L,
                freeBytes = if (path.available) path.root.usableSpace else 0L,
                usedBytes = usedBytes.takeIf { includeUsage },
                isCurrent = path.id == selected.id,
            )
        }
    }

    private fun publishState(
        message: String? = null,
        includeUsage: Boolean = true,
        currentUsageBytes: Long? = null,
    ) {
        val selected = selectedPaths()
        _state.value = PostStorageState(
            locations = locations(includeUsage, currentUsageBytes),
            currentLocationId = selected.id,
            isAvailable = selected.available && _database.value != null,
            isRefreshing = false,
            isMigrating = false,
            migrationMessage = message,
        )
    }

    private fun dataFiles(paths: StoragePaths): List<File> = buildList {
        addAll(databaseFiles(paths))
        if (paths.images.exists()) addAll(paths.images.walkTopDown().filter { it.isFile }.toList())
    }.filter { it.exists() }

    private fun databaseFiles(paths: StoragePaths): List<File> = listOf(
        paths.database,
        File(paths.database.path + "-wal"),
        File(paths.database.path + "-shm"),
    )

    private suspend fun managedUsage(database: LikeListDatabase): Long {
        val selected = selectedPaths()
        return database.clipDao().getStoredAssetStats().totalBytes +
            databaseFiles(selected).filter(File::exists).sumOf(File::length)
    }

    private fun copyDirectory(source: File, target: File) {
        if (!source.exists()) return
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val destination = File(target, relative.path)
            if (file.isDirectory) destination.mkdirs() else file.copyTo(destination, overwrite = true)
        }
    }

    private fun moveFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun moveDirectory(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            copyDirectory(source, target)
            source.deleteRecursively()
        }
    }

    private fun deleteDatabaseFiles(database: File) {
        database.delete()
        File(database.path + "-wal").delete()
        File(database.path + "-shm").delete()
    }

    private fun deleteStorageData(paths: StoragePaths) {
        deleteDatabaseFiles(paths.database)
        paths.images.deleteRecursively()
    }

    private fun recoverInterruptedMigration() {
        val phase = preferences.getString(KEY_MIGRATION_PHASE, null) ?: return
        val targetId = preferences.getString(KEY_MIGRATION_TARGET, null)
        if (phase != PHASE_SWITCHED && targetId != null) {
            availablePaths().firstOrNull { it.id == targetId }?.let { target ->
                deleteDatabaseFiles(File(target.database.parentFile, "${target.database.name}.moving"))
                File(target.images.parentFile, "${target.images.name}.moving").deleteRecursively()
            }
        }
        if (phase != PHASE_SWITCHED) clearMigrationState()
    }

    private fun finishSwitchedMigration() {
        if (preferences.getString(KEY_MIGRATION_PHASE, null) != PHASE_SWITCHED) return
        val sourceId = preferences.getString(KEY_MIGRATION_SOURCE, null)
        val targetDatabase = _database.value
        val targetIsValid = runCatching {
            targetDatabase?.openHelper?.readableDatabase
                ?.query("PRAGMA integrity_check")
                ?.use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "ok" }
                ?: false
        }.getOrDefault(false)
        if (targetIsValid) {
            val source = sourceId?.let { id -> availablePaths().firstOrNull { it.id == id } }
            source?.let(::deleteStorageData)
            if (source != null || sourceId == null) clearMigrationState()
            publishState()
            return
        }

        val source = sourceId?.let { id -> availablePaths().firstOrNull { it.id == id && it.available } }
        if (source != null) {
            targetDatabase?.close()
            preferences.edit()
                .putString(KEY_SELECTED_ID, source.id)
                .putString(KEY_SELECTED_PATH, source.root.absolutePath)
                .commit()
            clearMigrationState()
            _database.value = buildDatabase(source.database)
            publishState("前回の移動を完了できなかったため元の保存先へ戻しました")
        }
    }

    private fun clearMigrationState() {
        preferences.edit()
            .remove(KEY_MIGRATION_PHASE)
            .remove(KEY_MIGRATION_SOURCE)
            .remove(KEY_MIGRATION_TARGET)
            .commit()
    }

    companion object {
        private const val KEY_SELECTED_ID = "selected_id"
        private const val KEY_SELECTED_PATH = "selected_path"
        private const val KEY_MIGRATION_PHASE = "migration_phase"
        private const val KEY_MIGRATION_SOURCE = "migration_source"
        private const val KEY_MIGRATION_TARGET = "migration_target"
        private const val PHASE_COPYING = "copying"
        private const val PHASE_SWITCHED = "switched"
        private const val MIN_FREE_BYTES = 10L * 1024L * 1024L
    }
}
