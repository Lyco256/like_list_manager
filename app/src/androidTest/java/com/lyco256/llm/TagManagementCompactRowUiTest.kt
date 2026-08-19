package com.lyco256.llm

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagGroupNode
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagLeafNode
import com.lyco256.llm.data.TagWithCount
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TagManagementCompactRowUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tagAndGroupRowsKeepLongNamesCountsAndActionsOnOneCompactLine() {
        val now = "2026-08-13T00:00:00Z"
        val group = TagGroupEntity(
            id = 1,
            name = "操作を押し出さない非常に長いグループ名",
            createdAt = now,
            updatedAt = now,
        )
        val tag = TagEntity(
            id = 2,
            name = "操作を押し出さない非常に長いタグ名",
            createdAt = now,
            updatedAt = now,
        )
        val hierarchy = TagHierarchy(
            groups = listOf(group),
            tags = listOf(TagWithCount(tag, 123)),
        )

        composeRule.setContent {
            MaterialTheme {
                Column {
                    TagManagementRow(
                        row = VisibleTagRow(TagGroupNode(group, 0), 0, null, 0),
                        hierarchy = hierarchy,
                        expanded = mutableStateMapOf(),
                        isGroupDropTarget = false,
                        onBounds = {},
                        onToggleExpanded = {},
                        onMove = { _, _ -> },
                        onCreate = { _, _ -> },
                        onRenameTag = { _, _, _ -> },
                        onRenameGroup = { _, _, _ -> },
                        onDeleteTag = {},
                        onDeleteGroup = {},
                        onAddAll = { _, _ -> },
                    )
                    TagManagementRow(
                        row = VisibleTagRow(TagLeafNode(tag, 123), 0, null, 1),
                        hierarchy = hierarchy,
                        expanded = mutableStateMapOf(),
                        isGroupDropTarget = false,
                        onBounds = {},
                        onToggleExpanded = {},
                        onMove = { _, _ -> },
                        onCreate = { _, _ -> },
                        onRenameTag = { _, _, _ -> },
                        onRenameGroup = { _, _, _ -> },
                        onDeleteTag = {},
                        onDeleteGroup = {},
                        onAddAll = { _, _ -> },
                    )
                }
            }
        }

        composeRule.onAllNodesWithText("タグ").assertCountEquals(0)
        composeRule.onAllNodesWithText("グループ").assertCountEquals(0)
        assertCompactLine(type = "group", id = 1)
        assertCompactLine(type = "tag", id = 2)
    }

    private fun assertCompactLine(type: String, id: Long) {
        val row = composeRule.onNodeWithTag("tag_row_${type}_$id").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val name = composeRule.onNodeWithTag("tag_row_name_${type}_$id").fetchSemanticsNode().boundsInRoot
        val count = composeRule.onNodeWithTag("tag_row_count_${type}_$id").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("tag_row_actions_${type}_$id").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = composeRule.density.density

        assertTrue("compact row grew to ${row.height / density}dp", row.height / density <= 48f)
        assertTrue(abs(name.center.y - count.center.y) <= density)
        assertTrue(abs(name.center.y - actions.center.y) <= density)
        assertTrue(actions.right <= row.right)
    }
}
