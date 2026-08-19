package com.lyco256.llm

import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagWithCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagTreeGuideTest {
    private val now = "2026-08-13T00:00:00Z"
    private val root = TagGroupEntity(1, "root", null, 0, now, now)
    private val nested = TagGroupEntity(2, "nested", root.id, 0, now, now)
    private val otherRoot = TagGroupEntity(3, "other", null, 1, now, now)
    private val nestedFirst = TagEntity(11, "nested-first", nested.id, 0, now, now)
    private val nestedLast = TagEntity(12, "nested-last", nested.id, 1, now, now)
    private val rootLast = TagEntity(13, "root-last", root.id, 1, now, now)

    private val hierarchy = TagHierarchy(
        groups = listOf(root, nested, otherRoot),
        tags = listOf(nestedFirst, nestedLast, rootLast).map { TagWithCount(it, 0) },
    )

    @Test
    fun guideCountMatchesDepthZeroThroughThree() {
        val segments = calculateTagTreeGuideSegments(listOf(0, 1, 2, 3))

        assertEquals(listOf(0, 1, 2, 3), segments.map { it.size })
        assertEquals(listOf(0, 1, 2), segments.last().map { it.ancestorDepth })
    }

    @Test
    fun mixedSiblingsContinueOnlyGuidesWhoseSubtreeIsStillOpen() {
        val rows = hierarchy.visibleRows(setOf(root.id, nested.id))

        assertEquals(listOf(1L, 2L, 11L, 12L, 13L, 3L), rows.map { it.node.id })
        assertEquals(listOf(0, 1, 2, 2, 1, 0), rows.map { it.depth })

        val firstNestedLeaf = rows[2].guideSegments
        assertTrue(firstNestedLeaf.single { it.ancestorDepth == 0 }.connectsBelow)
        assertTrue(firstNestedLeaf.single { it.ancestorDepth == 1 }.connectsBelow)

        val lastNestedLeaf = rows[3].guideSegments
        assertTrue(lastNestedLeaf.single { it.ancestorDepth == 0 }.connectsBelow)
        assertFalse(lastNestedLeaf.single { it.ancestorDepth == 1 }.connectsBelow)

        val lastRootChild = rows[4].guideSegments.single()
        assertTrue(lastRootChild.connectsAbove)
        assertFalse(lastRootChild.connectsBelow)
        assertTrue(rows.last().guideSegments.isEmpty())
    }

    @Test
    fun collapsingRecomputesGuideMetadataForTheVisibleSequence() {
        val expanded = hierarchy.visibleRows(setOf(root.id, nested.id))
        val collapsed = hierarchy.visibleRows(setOf(root.id))

        assertEquals(listOf(0, 1, 2, 2, 1, 0), expanded.map { it.depth })
        assertEquals(listOf(0, 1, 1, 0), collapsed.map { it.depth })
        assertTrue(collapsed[1].guideSegments.single().connectsBelow)
        assertFalse(collapsed[2].guideSegments.single().connectsBelow)
    }

    @Test
    fun dragPlaceholderKeepsBothNestedGuidesAndTheirNeighborConnections() {
        val rows = hierarchy.visibleRows(setOf(root.id, nested.id))
        val state = DragState(
            node = TagNodeRef(TagNodeType.TAG, nestedFirst.id),
            label = nestedFirst.name,
            isGroup = false,
            sourceParentId = nested.id,
            sourceIndexInParent = 0,
            targetParentId = nested.id,
            placeholderIndex = 1,
            visualParentId = nested.id,
            visualPlaceholderIndex = 1,
            pointerYInRoot = 0f,
            grabOffsetY = 0f,
            itemLeftX = 0f,
            itemWidth = 100f,
            itemHeight = 40f,
            lastDragCenterY = 20f,
        )

        val items = buildTagListItems(rows, state)
        val placeholderIndex = items.indexOfFirst { it is TagListItem.Placeholder }
        val placeholder = items[placeholderIndex] as TagListItem.Placeholder
        val previous = (items[placeholderIndex - 1] as TagListItem.Row).row

        assertEquals(2, placeholder.depth)
        assertEquals(listOf(0, 1), placeholder.guideSegments.map { it.ancestorDepth })
        assertTrue(placeholder.guideSegments.all { it.connectsAbove })
        assertTrue(previous.guideSegments.all { it.connectsBelow })
        assertTrue(placeholder.guideSegments.single { it.ancestorDepth == 0 }.connectsBelow)
        assertFalse(placeholder.guideSegments.single { it.ancestorDepth == 1 }.connectsBelow)
    }
}
