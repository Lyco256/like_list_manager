package com.lyco256.llm.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "clips",
    indices = [Index(value = ["xPostId"], unique = true)],
)
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val xPostId: String,
    val authorId: String? = null,
    val authorName: String,
    val authorUsername: String,
    val text: String,
    val postUrl: String,
    val xCreatedAt: String,
    val savedAt: String,
    val syncedAt: String,
    val summary: String = "",
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "assets",
    foreignKeys = [
        ForeignKey(
            entity = ClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clipId")],
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clipId: Long,
    val mediaKey: String,
    val type: String,
    val remoteUrl: String?,
    val previewUrl: String?,
    val localPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val downloadState: String = "remote",
    val createdAt: String,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF3F8CFF,
    val sortOrder: Int = 0,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(
    tableName = "clip_tags",
    primaryKeys = ["clipId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clipId"), Index("tagId")],
)
data class ClipTagEntity(
    val clipId: Long,
    val tagId: Long,
    val createdAt: String,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val xUserId: String? = null,
    val newestSeenPostId: String? = null,
    val lastSyncAt: String? = null,
    val monthlyFetchedCount: Int = 0,
    val monthlyBudgetLimit: Int = 1800,
    val monthlyWarningLimit: Int = 1500,
    val monthlyStopLimit: Int = 2000,
    val usageMonth: String? = null,
    val rateLimitRemaining: Int? = null,
    val rateLimitLimit: Int? = null,
    val rateLimitResetEpochSeconds: Long? = null,
)

data class ClipWithDetails(
    val clip: ClipEntity,
    val assets: List<AssetEntity>,
    val tags: List<TagEntity>,
)

data class TagWithCount(
    val tag: TagEntity,
    val count: Int,
)
