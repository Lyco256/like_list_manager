package com.lyco256.llm

import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipTagEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagWithCount
import com.lyco256.llm.data.orderNodesAfterMove
import com.lyco256.llm.data.parentGroupIdForMove
import com.lyco256.llm.data.requireSiblingNameAvailable
import com.lyco256.llm.data.requireValidGroupDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagHierarchyTest {
    private val now = "2026-06-15T00:00:00Z"
    private val parent = TagGroupEntity(1, "開発", null, 0, now, now)
    private val child = TagGroupEntity(2, "Android", 1, 0, now, now)
    private val kotlin = TagEntity(10, "Kotlin", parentGroupId = 2, createdAt = now, updatedAt = now)
    private val compose = TagEntity(11, "Compose", parentGroupId = 2, createdAt = now, updatedAt = now)
    private val design = TagEntity(12, "Design", createdAt = now, updatedAt = now)

    @Test
    fun descendantTagsAndGroupCountsIncludeNestedTagsWithoutDuplicateClips() {
        val hierarchy = hierarchy(
            clipTags = listOf(
                ClipTagEntity(100, kotlin.id, now),
                ClipTagEntity(100, compose.id, now),
                ClipTagEntity(101, compose.id, now),
            ),
        )

        assertEquals(setOf(kotlin.id, compose.id), hierarchy.descendantTagIdsByGroup[parent.id])
        assertEquals(2, hierarchy.groupCounts[parent.id])
        assertEquals(2, hierarchy.groupCounts[child.id])
    }

    @Test
    fun filtersApplyRequiredAndTogetherAndIncludedAsOr() {
        val hierarchy = hierarchy()
        val clip = clip(kotlin, design)
        val requiredParent = TagNodeRef(TagNodeType.GROUP, parent.id)
        val requiredDesign = TagNodeRef(TagNodeType.TAG, design.id)
        val includedCompose = TagNodeRef(TagNodeType.TAG, compose.id)
        val includedKotlin = TagNodeRef(TagNodeType.TAG, kotlin.id)

        assertFalse(
            matchesTagFilters(
                clip,
                hierarchy,
                mapOf(
                    requiredParent to TagFilterState.REQUIRED,
                    requiredDesign to TagFilterState.REQUIRED,
                    includedCompose to TagFilterState.INCLUDED,
                ),
            ),
        )
        assertTrue(
            matchesTagFilters(
                clip,
                hierarchy,
                mapOf(
                    requiredParent to TagFilterState.REQUIRED,
                    requiredDesign to TagFilterState.REQUIRED,
                    includedCompose to TagFilterState.INCLUDED,
                    includedKotlin to TagFilterState.INCLUDED,
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun siblingNameCannotDuplicateAcrossGroupAndTag() {
        requireSiblingNameAvailable(
            groups = listOf(parent),
            tags = emptyList(),
            parentGroupId = null,
            name = parent.name,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun groupCannotMoveIntoDescendant() {
        requireValidGroupDestination(listOf(parent, child), parent.id, child.id)
    }

    @Test
    fun rootNodeParentIsResolvedWithoutTreatingNullAsMissing() {
        assertEquals(null, parentGroupIdForMove(TagNodeRef(TagNodeType.GROUP, parent.id), listOf(parent), emptyList()))
        assertEquals(null, parentGroupIdForMove(TagNodeRef(TagNodeType.TAG, design.id), emptyList(), listOf(design)))
    }

    @Test
    fun movingWithinSameParentAdjustsDownwardIndexAfterRemovingSource() {
        val a = TagNodeRef(TagNodeType.TAG, 1)
        val b = TagNodeRef(TagNodeType.TAG, 2)
        val c = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(b, a, c), orderNodesAfterMove(listOf(a, b, c), a, 2))
        assertEquals(listOf(b, c, a), orderNodesAfterMove(listOf(a, b, c), a, 3))
        assertEquals(listOf(c, a, b), orderNodesAfterMove(listOf(a, b, c), c, 0))
    }

    @Test
    fun movingAcrossParentsInsertsAtRequestedIndex() {
        val moved = TagNodeRef(TagNodeType.TAG, 1)
        val a = TagNodeRef(TagNodeType.TAG, 2)
        val b = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(a, moved, b), orderNodesAfterMove(listOf(a, b), moved, 1))
    }

    private fun hierarchy(clipTags: List<ClipTagEntity> = emptyList()) = TagHierarchy(
        groups = listOf(parent, child),
        tags = listOf(kotlin, compose, design).map { TagWithCount(it, 0) },
        clipTags = clipTags,
    )

    private fun clip(vararg tags: TagEntity) = ClipWithDetails(
        clip = ClipEntity(
            id = 100,
            xPostId = "x-100",
            authorName = "author",
            authorUsername = "author",
            text = "text",
            postUrl = "https://x.com/author/status/x-100",
            xCreatedAt = now,
            savedAt = now,
            syncedAt = now,
        ),
        assets = emptyList(),
        tags = tags.toList(),
    )
}
