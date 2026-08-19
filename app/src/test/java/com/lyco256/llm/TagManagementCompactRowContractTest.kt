package com.lyco256.llm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagManagementCompactRowContractTest {
    private val source by lazy {
        locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
    }

    @Test
    fun managementRowKeepsNameCountAndActionsInOneCompactLine() {
        val content = source
            .substringAfter("// Compact row content: node kind remains identifiable by icon and hierarchy.")
            .substringBefore("// End compact row content.")

        assertFalse(content.contains("Column("))
        assertFalse(content.contains("if (row.node is TagGroupNode) \"グループ\" else \"タグ\""))
        assertTrue(content.contains("maxLines = 1"))
        assertTrue(content.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(content.contains(".weight(1f)"))
        assertTrue(content.contains("tag_row_count_"))
        assertTrue(content.contains("tag_row_actions_"))

        val row = source.substringAfter("private fun TagManagementRow(").substringBefore("if (renameOpen)")
        assertTrue(row.contains(".heightIn(min = 40.dp)"))
        assertTrue(row.contains(".padding(horizontal = 8.dp, vertical = 4.dp)"))
    }

    @Test
    fun dragUsesMeasuredCompactBoundsForPlaceholderAndPreview() {
        val dragStart = source
            .substringAfter("onDragStart = { offset ->")
            .substringBefore("onDragCancel =")
        assertTrue(dragStart.contains("itemWidth = bounds.width"))
        assertTrue(dragStart.contains("itemHeight = bounds.height"))

        val placeholder = source
            .substringAfter("private fun TagPlaceholderSpacer(")
            .substringBefore("@Composable\nprivate fun TagDragPreview")
        assertTrue(placeholder.contains("item.heightPx.toDp()"))

        val preview = source
            .substringAfter("private fun TagDragPreview(")
            .substringBefore("internal fun TagHierarchy.visibleRows")
        assertTrue(preview.contains("state.itemHeight.toDp()"))
        assertTrue(preview.contains("padding(horizontal = 8.dp, vertical = 4.dp)"))
        assertTrue(preview.contains("maxLines = 1"))
    }

    private fun locateSource(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("app", relativePath.removePrefix("src/")),
    ).firstOrNull(File::isFile)
        ?: error("Source file not found: $relativePath")
}
