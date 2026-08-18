package com.lyco256.llm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Query("SELECT COUNT(*) FROM clips")
    suspend fun countClips(): Int

    @Query("SELECT * FROM clips ORDER BY savedAt DESC")
    fun observeAllClips(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips ORDER BY savedAt DESC")
    suspend fun getAllClips(): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE id = :clipId LIMIT 1")
    fun observeClip(clipId: Long): Flow<ClipEntity?>

    @Query("SELECT * FROM clips WHERE id = :clipId LIMIT 1")
    suspend fun getClip(clipId: Long): ClipEntity?

    @Query(
        """
        SELECT
            clips.id AS clipId,
            assets.id AS assetId,
            assets.mediaKey AS mediaKey,
            assets.type AS assetType,
            assets.remoteUrl AS remoteUrl,
            assets.previewUrl AS previewUrl,
            assets.localPath AS localPath,
            assets.width AS width,
            assets.height AS height,
            assets.downloadState AS downloadState
        FROM clips
        INNER JOIN assets ON assets.clipId = clips.id
        WHERE assets.type IN ('photo', 'video_thumbnail')
        ORDER BY assets.clipId DESC, assets.id ASC
        """,
    )
    fun observeMediaGridAssetRows(): Flow<List<MediaGridAssetRow>>

    @Query("SELECT * FROM assets WHERE clipId IN (:clipIds) ORDER BY id")
    suspend fun assetsForClipIds(clipIds: List<Long>): List<AssetEntity>

    @Query("SELECT * FROM assets ORDER BY id")
    fun observeAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE clipId = :clipId ORDER BY id ASC")
    fun observeAssetsForClip(clipId: Long): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets ORDER BY id")
    suspend fun getAllAssets(): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE id = :assetId LIMIT 1")
    suspend fun getAsset(assetId: Long): AssetEntity?

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS totalBytes FROM assets WHERE localPath IS NOT NULL")
    suspend fun getStoredAssetStats(): AssetStorageStats

    @Query("SELECT * FROM clip_tags WHERE clipId IN (:clipIds)")
    suspend fun clipTagsForClipIds(clipIds: List<Long>): List<ClipTagEntity>

    @Query("SELECT * FROM clip_tags WHERE clipId IN (:clipIds) AND tagId IN (:tagIds)")
    suspend fun clipTagsForClipIdsAndTagIds(clipIds: List<Long>, tagIds: List<Long>): List<ClipTagEntity>

    @Query("SELECT * FROM clip_tags WHERE tagId = :tagId ORDER BY clipId")
    suspend fun clipTagsForTag(tagId: Long): List<ClipTagEntity>

    @Query("SELECT * FROM clip_tags")
    fun observeClipTags(): Flow<List<ClipTagEntity>>

    @Query("SELECT * FROM clip_tags WHERE clipId = :clipId")
    fun observeClipTagsForClip(clipId: Long): Flow<List<ClipTagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClip(clip: ClipEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssets(assets: List<AssetEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClipTag(clipTag: ClipTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClipTags(clipTags: List<ClipTagEntity>)

    @Query("DELETE FROM clip_tags WHERE clipId = :clipId AND tagId = :tagId")
    suspend fun deleteClipTag(clipId: Long, tagId: Long)

    @Delete
    suspend fun deleteClipTags(clipTags: List<ClipTagEntity>)

    @Update
    suspend fun updateClip(clip: ClipEntity)

    @Query("UPDATE clips SET summary = :summary WHERE id = :clipId")
    suspend fun updateSummary(clipId: Long, summary: String): Int

    @Query("UPDATE clips SET ocrText = :ocrText, ocrUpdatedAt = :ocrUpdatedAt WHERE id = :clipId")
    suspend fun updateOcrText(clipId: Long, ocrText: String, ocrUpdatedAt: String?): Int

    @Query("DELETE FROM clips WHERE id = :clipId")
    suspend fun deleteClip(clipId: Long)

    @Query("UPDATE clips SET likeCount = :likeCount, likeCountFetchedAt = :fetchedAt, likeCountFetchFailedAt = NULL, likeCountFetchError = NULL WHERE id = :clipId")
    suspend fun updateLikeCount(clipId: Long, likeCount: Long, fetchedAt: String)

    @Query("UPDATE clips SET likeCountFetchFailedAt = :failedAt, likeCountFetchError = :message WHERE id = :clipId")
    suspend fun recordLikeCountFailure(clipId: Long, failedAt: String, message: String)

    @Update
    suspend fun updateAsset(asset: AssetEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observeSyncState(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun getSyncState(): SyncStateEntity?

    @Query("SELECT * FROM api_usage_months ORDER BY usageMonth DESC")
    fun observeApiUsageMonths(): Flow<List<ApiUsageMonthEntity>>

    @Query("SELECT * FROM api_usage_months WHERE usageMonth = :usageMonth")
    suspend fun getApiUsageMonth(usageMonth: String): ApiUsageMonthEntity?

    @Query("SELECT COALESCE(SUM(billableReadCount), 0) FROM api_usage_months")
    suspend fun getTotalBillableReadCount(): Long

    @Query(
        "INSERT INTO api_usage_months (usageMonth, billableReadCount, createdAt, updatedAt) " +
            "VALUES (:usageMonth, :delta, :now, :now) " +
            "ON CONFLICT(usageMonth) DO UPDATE SET billableReadCount = billableReadCount + excluded.billableReadCount, updatedAt = excluded.updatedAt",
    )
    suspend fun incrementApiUsageMonth(usageMonth: String, delta: Long, now: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(syncState: SyncStateEntity)

    @Transaction
    suspend fun replaceClipTags(clipId: Long, tagIds: Set<Long>, now: String) {
        val current = clipTagsForClipIds(listOf(clipId)).map { it.tagId }.toSet()
        current.minus(tagIds).forEach { deleteClipTag(clipId, it) }
        tagIds.minus(current).forEach { insertClipTag(ClipTagEntity(clipId, it, now)) }
    }

    @Transaction
    suspend fun applyClipTagChanges(
        clipIds: Set<Long>,
        pendingAddTagIds: Set<Long>,
        pendingRemoveTagIds: Set<Long>,
        now: String,
    ): ClipTagRelationChanges {
        require(pendingAddTagIds.intersect(pendingRemoveTagIds).isEmpty()) {
            "追加と削除に同じタグを指定できません"
        }
        val targetTagIds = pendingAddTagIds + pendingRemoveTagIds
        if (clipIds.isEmpty() || targetTagIds.isEmpty()) return ClipTagRelationChanges()

        val currentRelations = clipTagsForClipIdsAndTagIds(clipIds.toList(), targetTagIds.toList())
        val currentKeys = currentRelations.mapTo(hashSetOf()) { it.clipId to it.tagId }
        val removedRelations = currentRelations.filter { it.tagId in pendingRemoveTagIds }
        val addedRelations = buildList {
            clipIds.forEach { clipId ->
                pendingAddTagIds.forEach { tagId ->
                    if ((clipId to tagId) !in currentKeys) add(ClipTagEntity(clipId, tagId, now))
                }
            }
        }
        if (removedRelations.isNotEmpty()) deleteClipTags(removedRelations)
        if (addedRelations.isNotEmpty()) insertClipTags(addedRelations)
        return ClipTagRelationChanges(addedRelations, removedRelations)
    }
}

data class ClipTagRelationChanges(
    val addedRelations: List<ClipTagEntity> = emptyList(),
    val removedRelations: List<ClipTagEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = addedRelations.isEmpty() && removedRelations.isEmpty()
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY parentGroupId, sortOrder, name")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY parentGroupId, sortOrder, name")
    suspend fun getTags(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :tagId LIMIT 1")
    suspend fun getTag(tagId: Long): TagEntity?

    @Query(
        "SELECT tags.* FROM tags INNER JOIN clip_tags ON clip_tags.tagId = tags.id WHERE clip_tags.clipId = :clipId ORDER BY tags.parentGroupId, tags.sortOrder, tags.name",
    )
    fun observeTagsForClip(clipId: Long): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag_groups ORDER BY parentGroupId, sortOrder, name")
    fun observeGroups(): Flow<List<TagGroupEntity>>

    @Query("SELECT * FROM tag_groups ORDER BY parentGroupId, sortOrder, name")
    suspend fun getGroups(): List<TagGroupEntity>

    @Query("SELECT * FROM tag_groups WHERE id = :groupId LIMIT 1")
    suspend fun getGroup(groupId: Long): TagGroupEntity?

    @Query("SELECT tagId, COUNT(*) AS count FROM clip_tags GROUP BY tagId")
    fun observeTagCounts(): Flow<List<TagCountRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: TagGroupEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Update
    suspend fun updateGroup(group: TagGroupEntity)

    @Query("UPDATE tags SET name = :name, updatedAt = :updatedAt WHERE id = :tagId")
    suspend fun updateTagName(tagId: Long, name: String, updatedAt: String): Int

    @Query("UPDATE tags SET colorId = :colorId, updatedAt = :updatedAt WHERE id = :tagId")
    suspend fun updateTagColor(tagId: Long, colorId: String, updatedAt: String): Int

    @Query("UPDATE tag_groups SET name = :name, updatedAt = :updatedAt WHERE id = :groupId")
    suspend fun updateGroupName(groupId: Long, name: String, updatedAt: String): Int

    @Query("UPDATE tag_groups SET colorId = :colorId, updatedAt = :updatedAt WHERE id = :groupId")
    suspend fun updateGroupColor(groupId: Long, colorId: String, updatedAt: String): Int

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long): Int

    @Query("DELETE FROM tag_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long): Int

    @Query("SELECT COUNT(*) FROM tags WHERE parentGroupId = :groupId")
    suspend fun countChildTags(groupId: Long): Int

    @Query("SELECT COUNT(*) FROM tag_groups WHERE parentGroupId = :groupId")
    suspend fun countChildGroups(groupId: Long): Int

    @Query("SELECT clips.* FROM clips INNER JOIN clip_tags ON clips.id = clip_tags.clipId WHERE clip_tags.tagId = :tagId ORDER BY clips.savedAt DESC")
    suspend fun clipsForTag(tagId: Long): List<ClipEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClipTag(clipTag: ClipTagEntity)
}

@Dao
interface UndoDao {
    @Query("SELECT * FROM undo_slot WHERE id = 1 LIMIT 1")
    fun observeSlot(): Flow<UndoEntity?>

    @Query("SELECT * FROM undo_slot WHERE id = 1 LIMIT 1")
    suspend fun getSlot(): UndoEntity?

    @Query(
        "INSERT OR REPLACE INTO undo_slot (id, actionType, payloadJson, message, createdAt) " +
            "VALUES (1, :actionType, :payloadJson, :message, :createdAt)",
    )
    suspend fun replaceSlot(
        actionType: String,
        payloadJson: String,
        message: String,
        createdAt: String,
    )

    suspend fun replaceSlot(slot: UndoEntity) {
        replaceSlot(slot.actionType, slot.payloadJson, slot.message, slot.createdAt)
    }

    @Query("DELETE FROM undo_slot WHERE id = 1")
    suspend fun deleteSlot()
}

data class TagCountRow(
    val tagId: Long,
    val count: Int,
)

data class AssetStorageStats(
    val count: Int,
    val totalBytes: Long,
)

data class MediaGridAssetRow(
    val clipId: Long,
    val assetId: Long,
    val mediaKey: String,
    val assetType: String,
    val remoteUrl: String?,
    val previewUrl: String?,
    val localPath: String?,
    val width: Int?,
    val height: Int?,
    val downloadState: String,
)
