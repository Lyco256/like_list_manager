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

    @Query("SELECT * FROM clips WHERE isDeleted = 0 ORDER BY savedAt DESC")
    fun observeActiveClips(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM assets WHERE clipId IN (:clipIds) ORDER BY id")
    suspend fun assetsForClipIds(clipIds: List<Long>): List<AssetEntity>

    @Query("SELECT * FROM assets ORDER BY id")
    fun observeAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM clip_tags WHERE clipId IN (:clipIds)")
    suspend fun clipTagsForClipIds(clipIds: List<Long>): List<ClipTagEntity>

    @Query("SELECT * FROM clip_tags")
    fun observeClipTags(): Flow<List<ClipTagEntity>>

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

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observeSyncState(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun getSyncState(): SyncStateEntity?

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
    @Query("SELECT * FROM tags ORDER BY sortOrder, name")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT tagId, COUNT(*) AS count FROM clip_tags GROUP BY tagId")
    fun observeTagCounts(): Flow<List<TagCountRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    @Query("SELECT clips.* FROM clips INNER JOIN clip_tags ON clips.id = clip_tags.clipId WHERE clip_tags.tagId = :tagId AND clips.isDeleted = 0 ORDER BY clips.savedAt DESC")
    suspend fun clipsForTag(tagId: Long): List<ClipEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClipTag(clipTag: ClipTagEntity)
}

data class TagCountRow(
    val tagId: Long,
    val count: Int,
)
