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
    tableName = "tag_groups",
    foreignKeys = [
        ForeignKey(
            entity = TagGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentGroupId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("parentGroupId")],
)
data class TagGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentGroupId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(
    tableName = "tags",
    foreignKeys = [
        ForeignKey(
            entity = TagGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentGroupId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("parentGroupId")],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF3F8CFF,
    val parentGroupId: Long? = null,
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

sealed interface TagTreeNode {
    val id: Long
    val name: String
    val parentGroupId: Long?
    val sortOrder: Int
    val count: Int
}

data class TagGroupNode(
    val group: TagGroupEntity,
    override val count: Int,
) : TagTreeNode {
    override val id: Long = group.id
    override val name: String = group.name
    override val parentGroupId: Long? = group.parentGroupId
    override val sortOrder: Int = group.sortOrder
}

data class TagLeafNode(
    val tag: TagEntity,
    override val count: Int,
) : TagTreeNode {
    override val id: Long = tag.id
    override val name: String = tag.name
    override val parentGroupId: Long? = tag.parentGroupId
    override val sortOrder: Int = tag.sortOrder
}

data class TagHierarchy(
    val groups: List<TagGroupEntity> = emptyList(),
    val tags: List<TagWithCount> = emptyList(),
    val clipTags: List<ClipTagEntity> = emptyList(),
) {
    val nodesByParent: Map<Long?, List<TagTreeNode>> = buildList<TagTreeNode> {
        addAll(groups.map { TagGroupNode(it, 0) })
        addAll(tags.map { TagLeafNode(it.tag, it.count) })
    }.groupBy { it.parentGroupId }
        .mapValues { (_, nodes) -> nodes.sortedWith(compareBy<TagTreeNode> { it.sortOrder }.thenBy { it.name }) }

    val descendantTagIdsByGroup: Map<Long, Set<Long>> = groups.associate { group ->
        group.id to descendantTagIds(group.id)
    }

    val groupCounts: Map<Long, Int> = groups.associate { group ->
        val descendantIds = descendantTagIdsByGroup[group.id].orEmpty()
        group.id to clipTags.asSequence()
            .filter { it.tagId in descendantIds }
            .map { it.clipId }
            .distinct()
            .count()
    }

    fun children(parentGroupId: Long?): List<TagTreeNode> = nodesByParent[parentGroupId].orEmpty().map { node ->
        if (node is TagGroupNode) node.copy(count = groupCounts[node.id] ?: 0) else node
    }

    private fun descendantTagIds(groupId: Long): Set<Long> {
        val childTagIds = tags.filter { it.tag.parentGroupId == groupId }.mapTo(mutableSetOf()) { it.tag.id }
        groups.filter { it.parentGroupId == groupId }.forEach { child ->
            childTagIds += descendantTagIds(child.id)
        }
        return childTagIds
    }
}

enum class TagFilterState { NONE, INCLUDED, REQUIRED }

enum class TagNodeType { GROUP, TAG }

data class TagNodeRef(val type: TagNodeType, val id: Long)
