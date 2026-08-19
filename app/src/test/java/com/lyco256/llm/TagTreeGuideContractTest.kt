package com.lyco256.llm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagTreeGuideContractTest {
    private val source by lazy {
        locateSource("src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt").readText()
    }

    @Test
    fun treeGuideRendererDrawsVerticalSegmentsWithoutHorizontalBranches() {
        val renderer = source
            .substringAfter("private fun Modifier.drawTagTreeGuides(")
            .substringBefore("@Composable\nprivate fun TagDragPreview")

        assertTrue(renderer.contains("drawLine("))
        assertTrue(renderer.contains("start = Offset(x,"))
        assertTrue(renderer.contains("end = Offset(x,"))
        assertFalse(renderer.contains("drawPath("))
    }

    private fun locateSource(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("app", relativePath.removePrefix("src/")),
    ).firstOrNull(File::isFile)
        ?: error("Source file not found: $relativePath")
}
