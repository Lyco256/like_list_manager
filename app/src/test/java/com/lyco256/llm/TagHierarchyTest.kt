package com.lyco256.llm

import androidx.compose.ui.geometry.Rect
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipTagEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagGroupNode
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagLeafNode
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagWithCount
import com.lyco256.llm.data.orderNodesAfterMove
import com.lyco256.llm.data.orderNodesAfterMoveAtSlot
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

    @Test(expected = IllegalArgumentException::class)
    fun negativeMoveIndexFailsInsteadOfMovingToHead() {
        val moved = TagNodeRef(TagNodeType.TAG, 1)
        val a = TagNodeRef(TagNodeType.TAG, 2)

        orderNodesAfterMove(listOf(a), moved, -1)
    }

    @Test
    fun maxMoveIndexAppendsToTail() {
        val moved = TagNodeRef(TagNodeType.TAG, 1)
        val a = TagNodeRef(TagNodeType.TAG, 2)
        val b = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(a, b, moved), orderNodesAfterMove(listOf(a, b), moved, Int.MAX_VALUE))
    }

    @Test
    fun slotMoveUsesIndexAfterRemovingDraggedNode() {
        val a = TagNodeRef(TagNodeType.TAG, 1)
        val b = TagNodeRef(TagNodeType.TAG, 2)
        val c = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(b, a, c), orderNodesAfterMoveAtSlot(listOf(a, b, c), a, 1))
        assertEquals(listOf(b, c, a), orderNodesAfterMoveAtSlot(listOf(a, b, c), a, 2))
        assertEquals(listOf(c, a, b), orderNodesAfterMoveAtSlot(listOf(a, b, c), c, 0))
    }

    @Test
    fun slotMoveAcrossParentsInsertsAtRequestedSlot() {
        val moved = TagNodeRef(TagNodeType.GROUP, 1)
        val a = TagNodeRef(TagNodeType.TAG, 2)
        val b = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(a, moved, b), orderNodesAfterMoveAtSlot(listOf(a, b), moved, 1))
        assertEquals(listOf(a, b, moved), orderNodesAfterMoveAtSlot(listOf(a, b), moved, Int.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeSlotIndexFails() {
        val moved = TagNodeRef(TagNodeType.TAG, 1)

        orderNodesAfterMoveAtSlot(emptyList(), moved, -1)
    }

    @Test
    fun groupDropKeepsPreviousVisualPlaceholder() {
        val tag = TagLeafNode(design, 0)
        val group = TagGroupNode(parent, 0)
        val rows = listOf(
            VisibleTagRow(tag, depth = 0, parentGroupId = null, indexInParent = 0),
            VisibleTagRow(group, depth = 0, parentGroupId = null, indexInParent = 1),
        )
        val initial = dragState(
            node = tag.ref(),
            targetParentId = null,
            placeholderIndex = 1,
            visualParentId = null,
            visualPlaceholderIndex = 1,
            pointerYInRoot = 122f,
            grabOffsetY = 20f,
            itemHeight = 40f,
            lastDragCenterY = 122f,
        )
        val updated = updateDragStateAfterMove(
            state = initial,
            visibleRows = rows,
            displayItems = buildTagListItems(rows, initial),
            rowBounds = mapOf(group.ref() to Rect(0f, 100f, 300f, 160f)),
            hierarchy = TagHierarchy(groups = listOf(parent), tags = listOf(TagWithCount(design, 0))),
            listBounds = Rect(0f, 0f, 300f, 500f),
            allowStaticCrossing = false,
        )
        val items = buildTagListItems(rows, updated)
        val placeholder = items.filterIsInstance<TagListItem.Placeholder>().single()

        assertTrue(updated.targetIsGroupDrop)
        assertEquals(parent.id, updated.targetParentId)
        assertEquals(null, placeholder.parentGroupId)
        assertEquals(1, placeholder.index)
    }

    @Test
    fun draggedPlaceholderUsesSeparateKeyFromDraggedRow() {
        val tag = TagLeafNode(design, 0)
        val rows = listOf(VisibleTagRow(tag, depth = 0, parentGroupId = null, indexInParent = 0))
        val state = dragState(node = tag.ref(), placeholderIndex = 0)
        val items = buildTagListItems(rows, state)

        assertEquals(1, items.size)
        assertTrue(items.single() is TagListItem.Placeholder)
        assertFalse(items.single().key == tag.ref().saveableKeyForTest())
    }

    @Test
    fun topItemMovesDownOnlyOneSlotPerCrossing() {
        val a = TagLeafNode(design.copy(id = 20, name = "A", sortOrder = 0), 0)
        val b = TagLeafNode(design.copy(id = 21, name = "B", sortOrder = 1), 0)
        val c = TagLeafNode(design.copy(id = 22, name = "C", sortOrder = 2), 0)
        val rows = listOf(
            VisibleTagRow(a, depth = 0, parentGroupId = null, indexInParent = 0),
            VisibleTagRow(b, depth = 0, parentGroupId = null, indexInParent = 1),
            VisibleTagRow(c, depth = 0, parentGroupId = null, indexInParent = 2),
        )
        val beforeCross = dragState(
            node = a.ref(),
            placeholderIndex = 0,
            visualPlaceholderIndex = 0,
            pointerYInRoot = 80f,
            grabOffsetY = 20f,
            itemHeight = 40f,
            lastDragCenterY = 100f,
        )
        val crossed = beforeCross.copy(pointerYInRoot = 135f)
        val movedOnce = updateDragStateAfterMove(
            state = crossed,
            visibleRows = rows,
            displayItems = buildTagListItems(rows, crossed),
            rowBounds = mapOf(
                b.ref() to Rect(0f, 100f, 300f, 160f),
                c.ref() to Rect(0f, 180f, 300f, 240f),
            ),
            hierarchy = TagHierarchy(groups = emptyList(), tags = listOf(TagWithCount(a.tag, 0), TagWithCount(b.tag, 0), TagWithCount(c.tag, 0))),
            listBounds = Rect(0f, 0f, 300f, 500f),
            allowStaticCrossing = false,
        )
        val repeated = updateDragStateAfterMove(
            state = movedOnce.copy(reorderLocked = false),
            visibleRows = rows,
            displayItems = buildTagListItems(rows, movedOnce),
            rowBounds = mapOf(
                b.ref() to Rect(0f, 100f, 300f, 160f),
                c.ref() to Rect(0f, 180f, 300f, 240f),
            ),
            hierarchy = TagHierarchy(groups = emptyList(), tags = listOf(TagWithCount(a.tag, 0), TagWithCount(b.tag, 0), TagWithCount(c.tag, 0))),
            listBounds = Rect(0f, 0f, 300f, 500f),
            allowStaticCrossing = false,
        )

        assertEquals(1, movedOnce.placeholderIndex)
        assertEquals(1, movedOnce.visualPlaceholderIndex)
        assertEquals(1, repeated.placeholderIndex)
    }

    @Test
    fun autoScrollDoesNotRequestUnavailableDirection() {
        val bounds = Rect(0f, 100f, 300f, 500f)

        assertEquals(0f, calculateAutoScrollDelta(110f, bounds, canScrollBackward = false, canScrollForward = true), 0.001f)
        assertTrue(calculateAutoScrollDelta(110f, bounds, canScrollBackward = true, canScrollForward = true) < 0f)
        assertEquals(0f, calculateAutoScrollDelta(490f, bounds, canScrollBackward = true, canScrollForward = false), 0.001f)
        assertTrue(calculateAutoScrollDelta(490f, bounds, canScrollBackward = true, canScrollForward = true) > 0f)
    }

    private fun hierarchy(clipTags: List<ClipTagEntity> = emptyList()) = TagHierarchy(
        groups = listOf(parent, child),
        tags = listOf(kotlin, compose, design).map { TagWithCount(it, 0) },
        clipTags = clipTags,
    )

    private fun dragState(
        node: TagNodeRef,
        targetParentId: Long? = null,
        placeholderIndex: Int = 0,
        visualParentId: Long? = targetParentId,
        visualPlaceholderIndex: Int = placeholderIndex,
        pointerYInRoot: Float = 20f,
        grabOffsetY: Float = 20f,
        itemHeight: Float = 40f,
        lastDragCenterY: Float = pointerYInRoot - grabOffsetY + itemHeight / 2,
    ) = DragState(
        node = node,
        label = "drag",
        isGroup = node.type == TagNodeType.GROUP,
        sourceParentId = null,
        sourceIndexInParent = 0,
        targetParentId = targetParentId,
        placeholderIndex = placeholderIndex,
        visualParentId = visualParentId,
        visualPlaceholderIndex = visualPlaceholderIndex,
        pointerYInRoot = pointerYInRoot,
        grabOffsetY = grabOffsetY,
        itemLeftX = 0f,
        itemWidth = 300f,
        itemHeight = itemHeight,
        lastDragCenterY = lastDragCenterY,
    )

    private fun TagNodeRef.saveableKeyForTest(): String = "${type.name}:$id"

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
