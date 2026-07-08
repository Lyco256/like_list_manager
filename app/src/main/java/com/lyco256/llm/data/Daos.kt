package com.lyco256.llm.data

import androidx.room.Dao
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

    @Query("SELECT COUNT(*) FROM clips WHERE isDeleted = 0")
    suspend fun countActiveClips(): Int

    @Query("SELECT * FROM clips WHERE isDeleted = 0 ORDER BY savedAt DESC")
    fun observeActiveClips(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE isDeleted = 0 ORDER BY savedAt DESC")
    suspend fun getActiveClips(): List<ClipEntity>

    @Query(
        """
        SELECT
            clips.id AS clipId,
            clips.xPostId AS xPostId,
            clips.authorId AS authorId,
            clips.authorName AS authorName,
            clips.authorUsername AS authorUsername,
            clips.text AS text,
            clips.summary AS summary,
            clips.ocrText AS ocrText,
            clips.postUrl AS postUrl,
            clips.xCreatedAt AS xCreatedAt,
            clips.savedAt AS savedAt,
            clips.syncedAt AS syncedAt,
            clips.likeCount AS likeCount,
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
        WHERE clips.isDeleted = 0
          AND assets.type IN ('photo', 'video_thumbnail')
        ORDER BY clips.savedAt DESC, clips.id DESC, assets.id ASC
        """,
    )
    fun observeActiveMediaGridAssetRows(): Flow<List<MediaGridAssetRow>>

    @Query("SELECT * FROM assets WHERE clipId IN (:clipIds) ORDER BY id")
    suspend fun assetsForClipIds(clipIds: List<Long>): List<AssetEntity>

    @Query("SELECT * FROM assets ORDER BY id")
    fun observeAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets ORDER BY id")
    suspend fun getAllAssets(): List<AssetEntity>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS totalBytes FROM assets WHERE localPath IS NOT NULL")
    suspend fun getStoredAssetStats(): AssetStorageStats

    @Query("SELECT * FROM clip_tags WHERE clipId IN (:clipIds)")
    suspend fun clipTagsForClipIds(clipIds: List<Long>): List<ClipTagEntity>

    @Query("SELECT * FROM clip_tags")
    fun observeClipTags(): Flow<List<ClipTagEntity>>

    @Query("SELECT clip_tags.* FROM clip_tags INNER JOIN clips ON clips.id = clip_tags.clipId WHERE clips.isDeleted = 0")
    fun observeActiveClipTags(): Flow<List<ClipTagEntity>>

    @Query(
        """
        SELECT clip_tags.clipId, clip_tags.tagId, clip_tags.createdAt
        FROM clip_tags
        WHERE clip_tags.clipId IN (
            SELECT DISTINCT clips.id
            FROM clips
            INNER JOIN assets ON assets.clipId = clips.id
            WHERE clips.isDeleted = 0
              AND assets.type IN ('photo', 'video_thumbnail')
        )
        """,
    )
    fun observeActiveMediaGridClipTags(): Flow<List<ClipTagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClip(clip: ClipEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssets(assets: List<AssetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClipTag(clipTag: ClipTagEntity)

    @Query("DELETE FROM clip_tags WHERE clipId = :clipId AND tagId = :tagId")
    suspend fun deleteClipTag(clipId: Long, tagId: Long)

    @Update
    suspend fun updateClip(clip: ClipEntity)

    @Query("UPDATE clips SET ocrText = :ocrText, ocrUpdatedAt = :ocrUpdatedAt WHERE id = :clipId")
    suspend fun updateOcrText(clipId: Long, ocrText: String, ocrUpdatedAt: String?)

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
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY parentGroupId, sortOrder, name")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY parentGroupId, sortOrder, name")
    suspend fun getTags(): List<TagEntity>

    @Query("SELECT * FROM tag_groups ORDER BY parentGroupId, sortOrder, name")
    fun observeGroups(): Flow<List<TagGroupEntity>>

    @Query("SELECT * FROM tag_groups ORDER BY parentGroupId, sortOrder, name")
    suspend fun getGroups(): List<TagGroupEntity>

    @Query("SELECT clip_tags.tagId, COUNT(*) AS count FROM clip_tags INNER JOIN clips ON clips.id = clip_tags.clipId WHERE clips.isDeleted = 0 GROUP BY clip_tags.tagId")
    fun observeTagCounts(): Flow<List<TagCountRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: TagGroupEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Update
    suspend fun updateGroup(group: TagGroupEntity)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    @Query("DELETE FROM tag_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)

    @Query("SELECT COUNT(*) FROM tags WHERE parentGroupId = :groupId")
    suspend fun countChildTags(groupId: Long): Int

    @Query("SELECT COUNT(*) FROM tag_groups WHERE parentGroupId = :groupId")
    suspend fun countChildGroups(groupId: Long): Int

    @Query("SELECT clips.* FROM clips INNER JOIN clip_tags ON clips.id = clip_tags.clipId WHERE clip_tags.tagId = :tagId AND clips.isDeleted = 0 ORDER BY clips.savedAt DESC")
    suspend fun clipsForTag(tagId: Long): List<ClipEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClipTag(clipTag: ClipTagEntity)
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
    val xPostId: String,
    val authorId: String?,
    val authorName: String,
    val authorUsername: String,
    val text: String,
    val summary: String,
    val ocrText: String,
    val postUrl: String,
    val xCreatedAt: String,
    val savedAt: String,
    val syncedAt: String,
    val likeCount: Long?,
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
