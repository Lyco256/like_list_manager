package com.lyco256.llm

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridMorphExactTargetLayoutIndexTest {
    @Test
    fun targetIndexUsesExactItemSequenceRowsAndAchievableScrollBounds() {
        val frame = frame(count = 8)
        val index = buildMediaGridMorphExactTargetLayoutIndex(
            frame = frame,
            targetColumnCount = 5,
            viewportWidthPx = 600,
            viewportHeightPx = 200,
            headerHeightPx = 40f,
        )

        assertEquals(2, index.rowFirstItemIndex.size)
        assertEquals(0, index.rowFirstItemIndex(0))
        assertEquals(5, index.rowFirstItemIndex(1))
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), index.rowMediaOrdinals(0)!!)
        assertArrayEquals(intArrayOf(5, 6, 7), index.rowMediaOrdinals(1)!!)
        assertEquals(120f, index.rowTopAtScrollZero(1))
        assertEquals(240f, index.contentHeightPx)
        assertEquals(40f, index.maxScrollPx)
        assertEquals(80f, index.achievableRowTop(1, 80f))
        assertEquals(120f, index.achievableRowTop(1, 150f))
    }

    @Test
    fun fullViewportValidatorRejectsMissingOrMisplacedTargetCells() {
        val index = buildMediaGridMorphExactTargetLayoutIndex(
            frame = frame(count = 8),
            targetColumnCount = 5,
            viewportWidthPx = 600,
            viewportHeightPx = 200,
            headerHeightPx = 40f,
        )
        val visibleRows = index.rowFirstItemIndex.indices.map { rowId ->
            val ordinals = index.rowMediaOrdinals(rowId)!!.toList()
            val top = index.rowTopAtScrollZero(rowId)!! - 40f
            MediaGridMorphVisibleRowGeometry(
                rowIndex = rowId,
                rowTop = top,
                cellWidth = index.cellSizePx,
                cellHeight = index.cellSizePx,
                mediaOrdinals = ordinals,
                cells = ordinals.mapIndexed { column, ordinal ->
                    MediaGridMorphVisibleCellGeometry(
                        mediaOrdinal = ordinal,
                        itemIndex = index.itemIndexByMediaOrdinal[ordinal],
                        rect = Rect(
                            column * index.cellSizePx,
                            top,
                            (column + 1) * index.cellSizePx,
                            top + index.cellSizePx,
                        ),
                    )
                },
            )
        }
        val visible = MediaGridMorphVisibleViewportGeometry(
            viewport = Rect(0f, 0f, 600f, 200f),
            rows = visibleRows,
            headers = emptyList(),
        )
        assertTrue(validateMediaGridMorphExactTargetViewport(index, visible))
        val corrupted = visible.copy(rows = visible.rows.mapIndexed { index, row ->
            if (index == 0) row.copy(mediaOrdinals = row.mediaOrdinals.dropLast(1)) else row
        })
        assertFalse(validateMediaGridMorphExactTargetViewport(index, corrupted))
    }

    private fun frame(count: Int): MediaGridFrameData {
        val dataKey = MediaGridDataKey(
            sourceRevision = 1L,
            hierarchyRevision = 1L,
            filter = TweetFilterState(),
            sort = ClassifiedSortState(),
        )
        return buildMediaGridFrameData(
            entries = List(count) { index ->
                MediaGridEntry(
                    entryId = index.toLong() + 1L,
                    clipId = index.toLong() + 1L,
                    assetId = index.toLong() + 1L,
                    mediaKey = "media-$index",
                    mediaIndex = index,
                    type = "photo",
                    displayUrl = null,
                    downloadState = "downloaded",
                    localPath = null,
                    xCreatedAt = "2026-07-01T00:00:00Z",
                    likeCount = index.toLong(),
                )
            },
            sort = dataKey.sort,
            columnCount = 4,
            dataKey = dataKey,
        )
    }
}
