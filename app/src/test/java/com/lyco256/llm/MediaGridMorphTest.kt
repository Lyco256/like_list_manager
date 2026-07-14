package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridMorphTest {
    @Test
    fun targetIsAlwaysOneColumnAwayAndRespectsBounds() {
        assertEquals(5, mediaGridMorphTargetColumnCount(4, MediaGridMorphDirection.IncreaseColumns))
        assertEquals(3, mediaGridMorphTargetColumnCount(4, MediaGridMorphDirection.DecreaseColumns))
        assertEquals(ClassifiedMediaGridMaxColumnCount, mediaGridMorphTargetColumnCount(12, MediaGridMorphDirection.IncreaseColumns))
        assertEquals(ClassifiedMediaGridMinColumnCount, mediaGridMorphTargetColumnCount(2, MediaGridMorphDirection.DecreaseColumns))
    }

    @Test
    fun progressIsBoundedMonotonicAndReversible() {
        val increasing = listOf(1.12f, 1.2f, 1.4f, 2f).map {
            mediaGridMorphProgressForScale(it, MediaGridMorphDirection.IncreaseColumns)
        }
        assertEquals(0f, increasing.first(), 0.0001f)
        assertTrue(increasing.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(increasing.all { it in 0f..1f })
        assertEquals(0f, mediaGridMorphProgressForScale(1.08f, MediaGridMorphDirection.IncreaseColumns), 0.0001f)
        assertTrue(mediaGridMorphProgressForScale(1.2f, MediaGridMorphDirection.IncreaseColumns) > 0f)
    }

    @Test
    fun releaseUsesHalfThresholdAndTargetHandoffIsOneShot() {
        val session = trackingSession(4, 1.3f)
        val back = session.updateTracking(1.13f, 10L).release()
        assertEquals(MediaGridMorphPhase.SettlingToCurrent, back.phase)
        val target = trackingSession(4, 3f).release()
        assertEquals(MediaGridMorphPhase.SettlingToTarget, target.phase)
        val first = target.advanceSettle(180L, 10L)
        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, first.session.phase)
        assertEquals(5, first.targetColumnCountToHandoff)
        val second = first.session.advanceSettle(180L, 10L)
        assertEquals(null, second.targetColumnCountToHandoff)
    }

    @Test
    fun sourceRevisionChangeCancelsTrackingPlanToCurrentColumns() {
        val session = trackingSession(4, 1.3f)
        val cancelled = session.updateTracking(1.4f, 11L)
        assertEquals(MediaGridMorphPhase.Idle, cancelled.phase)
        assertEquals(4, cancelled.fromColumnCount)
        assertEquals(4, cancelled.toColumnCount)
        assertEquals(null, cancelled.plan)
    }

    @Test
    fun fourToFiveCreatesZeroWidthRightEdgeSlotAndKeepsAssetOrder() {
        val from = snapshot(4, (0 until 8).map { media(('A'.code + it).toChar().toString(), it, it / 4, it % 4) })
        val to = snapshot(5, (0 until 10).map { media(('A'.code + it).toChar().toString(), it, it / 5, it % 5) })
        val plan = MediaGridMorphPlan.create(from, to, Offset(75f, 25f), overscanRows = 1)
        val firstRow = plan.slots.take(5)
        assertEquals(listOf("A", "B", "C", "D", null), firstRow.map { it.startAssetKey })
        assertEquals(listOf("A", "B", "C", "D", "E"), firstRow.map { it.endAssetKey })
        assertEquals(0f, firstRow.last().startRect.width, 0.0001f)
        assertEquals(10, plan.slots.size)
        assertEquals(0, plan.slots.mapNotNull { it.startAssetKey }.groupingBy { it }.eachCount().values.maxOrNull()?.minus(1) ?: 0)
    }

    @Test
    fun headersKeepAddRemoveAndTitleChangeInformation() {
        val from = snapshot(
            4,
            listOf(
                header("day", "月", 0f, 40f),
                media("A", 0, 1, 0),
            ),
        )
        val to = snapshot(
            5,
            listOf(
                header("day", "週", 0f, 40f),
                header("month", "月", 40f, 40f),
                media("A", 0, 2, 0),
            ),
        )
        val headers = MediaGridMorphPlan.create(from, to, Offset(1f, 50f), overscanRows = 1).headers.associateBy { it.key }
        assertEquals("月", headers["day"]?.startTitle)
        assertEquals("週", headers["day"]?.endTitle)
        assertEquals(0f, headers["month"]?.startHeight ?: -1f, 0.0001f)
        assertEquals(40f, headers["month"]?.endHeight ?: -1f, 0.0001f)
    }

    @Test
    fun tenThousandItemsProduceOnlyViewportNeighborhoodPlan() {
        val fromItems = (0 until 10_000).map { media("asset-$it", it, it / 4, it % 4) }
        val toItems = (0 until 10_000).map { media("asset-$it", it, it / 5, it % 5) }
        val plan = MediaGridMorphPlan.create(
            snapshot(4, fromItems),
            snapshot(5, toItems),
            Offset(100f, 250f),
            overscanRows = 2,
        )
        assertTrue(plan.plannedItemCount < 100)
    }

    private fun trackingSession(columnCount: Int, scale: Float): MediaGridMorphSession = MediaGridMorphSession.begin(
        currentColumnCount = columnCount,
        scale = scale,
        pinchCenter = Offset(50f, 50f),
        sourceRevision = 10L,
        fromPlan = snapshot(columnCount, listOf(media("A", 0, 0, 0))),
        toPlan = snapshot(columnCount + 1, listOf(media("A", 0, 0, 0), media("B", 1, 0, 1))),
    )!!

    private fun snapshot(columns: Int, items: List<MediaGridMorphLayoutItem>): MediaGridMorphLayoutSnapshot =
        MediaGridMorphLayoutSnapshot(columns, Rect(0f, 0f, 400f, 400f), items)

    private fun media(key: String, index: Int, row: Int, column: Int): MediaGridMorphLayoutItem =
        MediaGridMorphLayoutItem.Media(MediaGridMorphMedia(key, index, Rect(column * 100f, row * 100f, column * 100f + 100f, row * 100f + 100f)))

    private fun header(key: String, title: String, top: Float, height: Float): MediaGridMorphLayoutItem =
        MediaGridMorphLayoutItem.Header(MediaGridMorphHeader(key, title, Rect(0f, top, 400f, top + height)))
}
