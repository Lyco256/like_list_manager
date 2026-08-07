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
        assertEquals(0f, index.achievableRowTop(0, 100f))
        assertEquals(80f, index.achievableRowTop(1, -100f))
    }

    @Test
    fun targetIndexUsesTheFullHeaderSequenceAndResetsRowsAtEachBucket() {
        val dates = List(12) { ordinal ->
            "2026-07-${(ordinal / 4 + 1).toString().padStart(2, '0')}T00:00:00Z"
        }
        val index = buildMediaGridMorphExactTargetLayoutIndex(
            frame = frame(count = dates.size, sort = ClassifiedSortBase.PostTime, dates = dates),
            targetColumnCount = 4,
            viewportWidthPx = 640,
            viewportHeightPx = 250,
            headerHeightPx = 40f,
        )

        assertEquals(3, index.headers.size)
        assertEquals(listOf(0, 4, 8), index.headers.map { it.firstMediaOrdinal })
        assertEquals(listOf(1, 6, 11), index.rowFirstItemIndex.toList())
        assertEquals(listOf(40f, 240f, 440f), index.rowTopAtScrollZero.toList())
        assertEquals(listOf(0, 1, 2), index.rowHeaderIndex.toList())
        assertArrayEquals(intArrayOf(0, 1, 2, 3), index.rowMediaOrdinals(0)!!)
        assertArrayEquals(intArrayOf(4, 5, 6, 7), index.rowMediaOrdinals(1)!!)
        assertArrayEquals(intArrayOf(8, 9, 10, 11), index.rowMediaOrdinals(2)!!)
        assertEquals(600f, index.contentHeightPx)
        assertEquals(350f, index.maxScrollPx)
        assertEquals(index.headers[1], index.headerForRow(1))
    }

    @Test
    fun fullViewportValidatorUsesViewportLocalRowsCellsAndHeadersAcrossBuckets() {
        val dates = List(12) { ordinal ->
            "2026-07-${(ordinal / 4 + 1).toString().padStart(2, '0')}T00:00:00Z"
        }
        val index = buildMediaGridMorphExactTargetLayoutIndex(
            frame = frame(count = dates.size, sort = ClassifiedSortBase.PostTime, dates = dates),
            targetColumnCount = 4,
            viewportWidthPx = 640,
            viewportHeightPx = 250,
            headerHeightPx = 40f,
        )
        val visible = viewportGeometry(index, scroll = 150f)

        assertEquals(-110f, visible.rows.first().rowTop, 0.0001f)
        assertEquals(50f, visible.headers.single().rect.top, 0.0001f)
        assertTrue(validateMediaGridMorphExactTargetViewport(index, visible))

        val badHeader = visible.copy(headers = visible.headers.map { it.copy(title = "wrong") })
        assertFalse(validateMediaGridMorphExactTargetViewport(index, badHeader))
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

    private fun frame(
        count: Int,
        sort: ClassifiedSortBase = ClassifiedSortBase.Default,
        dates: List<String> = List(count) { "2026-07-01T00:00:00Z" },
    ): MediaGridFrameData {
        val dataKey = MediaGridDataKey(
            sourceRevision = 1L,
            hierarchyRevision = 1L,
            filter = TweetFilterState(),
            sort = ClassifiedSortState(baseOrder = sort),
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
                    xCreatedAt = dates[index],
                    likeCount = index.toLong(),
                )
            },
            sort = dataKey.sort,
            columnCount = 4,
            dataKey = dataKey,
        )
    }

    private fun viewportGeometry(
        index: MediaGridMorphExactTargetLayoutIndex,
        scroll: Float,
    ): MediaGridMorphVisibleViewportGeometry {
        val viewport = Rect(
            0f,
            0f,
            index.viewportWidthPx.toFloat(),
            index.viewportHeightPx.toFloat(),
        )
        fun intersects(top: Float, bottom: Float): Boolean = bottom > 0f && top < viewport.height
        val rows = index.rowFirstItemIndex.indices.mapNotNull { rowId ->
            val top = index.rowTopAtScrollZero(rowId)!! - scroll
            if (!intersects(top, top + index.rowHeightPx)) return@mapNotNull null
            val ordinals = index.rowMediaOrdinals(rowId)!!.toList()
            MediaGridMorphVisibleRowGeometry(
                rowIndex = rowId,
                rowTop = top,
                cellWidth = index.cellSizePx,
                cellHeight = index.cellSizePx,
                mediaOrdinals = ordinals,
                headerKey = index.headerForRow(rowId)?.key,
                headerTitle = index.headerForRow(rowId)?.title,
                cells = ordinals.mapIndexed { column, ordinal ->
                    MediaGridMorphVisibleCellGeometry(
                        mediaOrdinal = ordinal,
                        itemIndex = index.itemIndexByMediaOrdinal[ordinal],
                        rect = Rect(
                            index.columnLeftPx[column].toFloat(),
                            top,
                            (index.columnLeftPx[column] + index.columnWidthPx[column]).toFloat(),
                            top + index.rowHeightPx,
                        ),
                    )
                },
            )
        }
        val headers = index.headers.mapNotNull { header ->
            val top = header.topAtScrollZero - scroll
            if (!intersects(top, top + header.height)) return@mapNotNull null
            MediaGridMorphVisibleHeaderGeometry(
                itemIndex = header.itemIndex,
                key = header.key,
                title = header.title,
                rect = Rect(0f, top, viewport.width, top + header.height),
            )
        }
        return MediaGridMorphVisibleViewportGeometry(viewport, rows, headers)
    }
}
