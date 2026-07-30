package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridMorphTest {
    @Test
    fun targetIsAlwaysOneColumnAwayAndRespectsBounds() {
        assertEquals(5, mediaGridMorphTargetColumnCount(4, MediaGridMorphDirection.IncreaseColumns))
        assertEquals(3, mediaGridMorphTargetColumnCount(4, MediaGridMorphDirection.DecreaseColumns))
        assertEquals(12, mediaGridMorphTargetColumnCount(12, MediaGridMorphDirection.IncreaseColumns))
        assertEquals(2, mediaGridMorphTargetColumnCount(2, MediaGridMorphDirection.DecreaseColumns))
    }

    @Test
    fun pinchReleaseKeepsCurrentBehaviorAndChangesAtMostOneColumn() {
        assertEquals(4, mediaGridColumnCountAfterPinchRelease(4, null))
        assertEquals(4, mediaGridColumnCountAfterPinchRelease(4, 1.01f))
        assertEquals(5, mediaGridColumnCountAfterPinchRelease(4, 1.04f))
        assertEquals(3, mediaGridColumnCountAfterPinchRelease(4, 0.96f))
        assertEquals(5, mediaGridColumnCountAfterPinchRelease(4, 2f))
        assertEquals(3, mediaGridColumnCountAfterPinchRelease(4, 0.25f))
        assertEquals(12, mediaGridColumnCountAfterPinchRelease(12, 2f))
        assertEquals(2, mediaGridColumnCountAfterPinchRelease(2, 0.25f))
    }

    @Test
    fun fourToFiveUsesRowAndColumnSlotsWithoutTrackingAssetAcrossRows() {
        val pair = pair(4, capture(columns = 4, count = 10))
        val firstRow = pair.slots.filter { it.row == 0 }
        val secondRow = pair.slots.filter { it.row == 1 }

        assertEquals(listOf(1L, 2L, 3L, 4L, null), firstRow.map { it.startAssetId })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), firstRow.map { it.endAssetId })
        assertEquals(listOf(5L, 6L, 7L, 8L, null), secondRow.map { it.startAssetId })
        assertEquals(listOf(6L, 7L, 8L, 9L, 10L), secondRow.map { it.endAssetId })
        assertEquals(0f, firstRow.last().startRect.width, 0.0001f)
        assertEquals(5L, secondRow.first().startAssetId)
        assertEquals(6L, secondRow.first().endAssetId)
    }

    @Test
    fun fiveToFourShrinksTheRightmostPositionToZeroWidth() {
        val pair = pair(5, capture(columns = 5, count = 10), MediaGridMorphDirection.DecreaseColumns)
        val firstRow = pair.slots.filter { it.row == 0 }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), firstRow.map { it.startAssetId })
        assertEquals(listOf(1L, 2L, 3L, 4L, null), firstRow.map { it.endAssetId })
        assertEquals(0f, firstRow.last().endRect.width, 0.0001f)
        assertEquals(pair.viewport.right, firstRow.last().endRect.left, 0.0001f)
    }

    @Test
    fun adjacentColumnPairsKeepPositionOrderAtRepresentativeBounds() {
        listOf(2 to 3, 4 to 5, 8 to 9, 11 to 12).forEach { (from, to) ->
            val prepared = pair(from, capture(columns = from, count = to * 2))
            assertEquals(to, prepared.toColumnCount)
            prepared.slots.groupBy { it.row }.values.forEach { row ->
                assertEquals(row.indices.toList(), row.map { it.column })
            }
        }
        listOf(3 to 2, 5 to 4, 9 to 8, 12 to 11).forEach { (from, to) ->
            val prepared = pair(from, capture(columns = from, count = from * 2), MediaGridMorphDirection.DecreaseColumns)
            assertEquals(to, prepared.toColumnCount)
            prepared.slots.groupBy { it.row }.values.forEach { row ->
                assertEquals(row.indices.toList(), row.map { it.column })
            }
        }
    }

    @Test
    fun eachSnapshotAssetAppearsInOnlyOneSlot() {
        val prepared = pair(4, capture(columns = 4, count = 40))
        val startIds = prepared.slots.mapNotNull { it.startAssetId }
        val endIds = prepared.slots.mapNotNull { it.endAssetId }

        assertEquals(startIds.size, startIds.toSet().size)
        assertEquals(endIds.size, endIds.toSet().size)
    }

    @Test
    fun slotRectAndImageCrossfadeAreContinuous() {
        val slot = MediaGridMorphSlot(
            row = 0,
            column = 0,
            startRect = Rect(0f, 10f, 100f, 110f),
            endRect = Rect(200f, 30f, 280f, 110f),
            startAssetId = 1L,
            endAssetId = 2L,
            startMediaOrdinal = 0,
            endMediaOrdinal = 1,
        )
        val expected = mapOf(
            0f to Rect(0f, 10f, 100f, 110f),
            0.25f to Rect(50f, 15f, 145f, 110f),
            0.5f to Rect(100f, 20f, 190f, 110f),
            0.75f to Rect(150f, 25f, 235f, 110f),
            1f to Rect(200f, 30f, 280f, 110f),
        )
        expected.forEach { (progress, rect) ->
            assertEquals(rect, mediaGridMorphRect(slot, progress))
            assertEquals(1f - progress, mediaGridMorphStartAlpha(slot, progress), 0.0001f)
            assertEquals(progress, mediaGridMorphEndAlpha(slot, progress), 0.0001f)
        }
        assertEquals(2, mediaGridMorphLayerCount(slot))
    }

    @Test
    fun identicalAssetInOnePositionUsesOneOpaqueLayer() {
        val slot = MediaGridMorphSlot(
            0,
            0,
            Rect(0f, 0f, 100f, 100f),
            Rect(0f, 0f, 80f, 80f),
            1L,
            1L,
            0,
            0,
        )
        assertEquals(1, mediaGridMorphLayerCount(slot))
        assertEquals(1f, mediaGridMorphStartAlpha(slot, 0.5f), 0.0001f)
        assertEquals(0f, mediaGridMorphEndAlpha(slot, 0.5f), 0.0001f)
    }

    @Test
    fun sameOrdinalBoundaryMatchesEvenWhenHeaderKeyAndTitleChange() {
        val prepared = pair(
            4,
            capture(
                columns = 4,
                count = 8,
                sortBase = ClassifiedSortBase.PostTime,
                dates = listOf(
                    "2026-07-06T00:00:00Z",
                    "2026-07-07T00:00:00Z",
                    "2026-07-08T00:00:00Z",
                    "2026-07-09T00:00:00Z",
                    "2026-07-10T00:00:00Z",
                    "2026-07-11T00:00:00Z",
                    "2026-07-12T00:00:00Z",
                    "2026-07-13T00:00:00Z",
                ),
            ),
        )
        val first = prepared.headers.single { it.startFirstMediaOrdinal == 0 }

        assertEquals(0, first.endFirstMediaOrdinal)
        assertTrue(first.startKey!!.contains("day"))
        assertTrue(first.endKey!!.contains("week"))
        assertFalse(first.startTitle == first.endTitle)
        assertEquals(2, mediaGridMorphTitleLayerCount(first))
    }

    @Test
    fun dateGranularityTransitionsDoNotReuseAnyHeaderBackground() {
        val dayToWeek = pair(
            4,
            capture(columns = 4, count = 20, sortBase = ClassifiedSortBase.PostTime),
        )
        val weekToDay = pair(
            5,
            capture(columns = 5, count = 20, sortBase = ClassifiedSortBase.PostTime),
            MediaGridMorphDirection.DecreaseColumns,
        )
        val weekToMonth = pair(
            8,
            capture(columns = 8, count = 32, sortBase = ClassifiedSortBase.PostTime),
        )
        val monthToWeek = pair(
            9,
            capture(columns = 9, count = 32, sortBase = ClassifiedSortBase.PostTime),
            MediaGridMorphDirection.DecreaseColumns,
        )

        listOf(dayToWeek, weekToDay, weekToMonth, monthToWeek).forEach(::assertHeaderInputsAreUnique)
    }

    @Test
    fun likeBucketChangeKeepsAddRemoveAndCrossfadeInformation() {
        val likes = listOf(950L, 850L, 750L, 650L, 550L, 450L, 350L, 250L, 150L, 50L)
        val prepared = pair(
            4,
            capture(columns = 4, count = likes.size, sortBase = ClassifiedSortBase.LikeCount, likes = likes),
        )

        assertTrue(prepared.headers.any {
            it.startFirstMediaOrdinal == it.endFirstMediaOrdinal &&
                it.startTitle != it.endTitle
        })
        assertTrue(prepared.headers.any { it.startRect.height == 0f || it.endRect.height == 0f })
        assertHeaderInputsAreUnique(prepared)
        val reverse = pair(
            5,
            capture(columns = 5, count = likes.size, sortBase = ClassifiedSortBase.LikeCount, likes = likes),
            MediaGridMorphDirection.DecreaseColumns,
        )
        assertHeaderInputsAreUnique(reverse)
    }

    @Test
    fun addedAndRemovedHeadersUseFullWidthZeroHeightGeometry() {
        val prepared = pair(
            4,
            capture(columns = 4, count = 18, sortBase = ClassifiedSortBase.PostTime),
        )
        val changed = prepared.headers.filter { it.startRect.height == 0f || it.endRect.height == 0f }
        assertTrue(changed.isNotEmpty())
        changed.forEach { band ->
            assertEquals(prepared.viewport.left, band.startRect.left, 0.0001f)
            assertEquals(prepared.viewport.right, band.startRect.right, 0.0001f)
            assertEquals(prepared.viewport.left, band.endRect.left, 0.0001f)
            assertEquals(prepared.viewport.right, band.endRect.right, 0.0001f)
        }
    }

    @Test
    fun identicalHeaderTitleUsesOneLayerAndDifferentTitlesCrossfade() {
        val same = headerBand("same", "same")
        val changed = headerBand("before", "after")

        assertEquals(1, mediaGridMorphTitleLayerCount(same))
        assertEquals(1f, mediaGridMorphStartTitleAlpha(same, 0.5f), 0.0001f)
        assertEquals(0f, mediaGridMorphEndTitleAlpha(same, 0.5f), 0.0001f)
        assertEquals(2, mediaGridMorphTitleLayerCount(changed))
        assertEquals(0.5f, mediaGridMorphStartTitleAlpha(changed, 0.5f), 0.0001f)
        assertEquals(0.5f, mediaGridMorphEndTitleAlpha(changed, 0.5f), 0.0001f)
    }

    @Test
    fun tenThousandItemRangeIsVisiblePlusTwoRowsOnEachSide() {
        val range = mediaGridMorphOrdinalRange(
            totalMedia = 10_000,
            firstVisibleMediaOrdinal = 5_000,
            lastVisibleMediaOrdinal = 5_019,
            columnCount = 4,
        )!!

        assertEquals(4_990, range.first)
        assertEquals(5_029, range.last)
        assertEquals(20 + (5 * 4), range.count())
    }

    @Test
    fun localRangeStartingInsideBucketDoesNotCreateFalseHeader() {
        val preceding = capturedMedia(
            assetId = 99L,
            ordinal = 99,
            date = "2026-07-06T00:00:00Z",
        )
        val local = capture(
            columns = 4,
            count = 8,
            startOrdinal = 100,
            sortBase = ClassifiedSortBase.PostTime,
            dates = List(8) { "2026-07-06T12:00:00Z" },
            precedingMedia = preceding,
        )
        val prepared = pair(4, local)

        assertTrue(prepared.startLayout.headers.isEmpty())
        assertTrue(prepared.targetLayout.headers.isEmpty())
    }

    @Test
    fun boundaryColumnsOnlyPrepareExistingDirection() {
        val atMinimum = buildMediaGridMorphPreparedPairs(capture(columns = 2, count = 20))
        val atMaximum = buildMediaGridMorphPreparedPairs(capture(columns = 12, count = 30))

        assertEquals(setOf(MediaGridMorphDirection.IncreaseColumns), atMinimum.keys)
        assertEquals(setOf(MediaGridMorphDirection.DecreaseColumns), atMaximum.keys)
    }

    @Test
    fun preparationCacheRunsOnceForInitialIdentityAndIgnoresPixelOnlyMovement() {
        val cache = MediaGridMorphPreparationCache()
        val identity = identity(columns = 4)

        assertTrue(cache.request(identity, false, false) != null)
        assertNull(cache.request(identity, false, false))
        assertNull(cache.request(identity, true, false))
        assertNull(cache.request(identity, false, true))
    }

    @Test
    fun idleBoundaryAndAllIdentityInputsRequestFreshPreparation() {
        val cache = MediaGridMorphPreparationCache()
        val base = identity(columns = 4)
        assertTrue(cache.request(base, false, false) != null)
        assertTrue(cache.request(base.copy(viewportSignature = base.viewportSignature.copy(firstVisibleMediaOrdinal = 1)), false, false) != null)
        assertTrue(cache.request(identity(columns = 5), false, false) != null)
        assertTrue(cache.request(identity(columns = 4, revision = 11L), false, false) != null)
        assertTrue(cache.request(identity(columns = 4, width = 900), false, false) != null)
        assertTrue(cache.request(identity(columns = 4, sort = ClassifiedSortBase.LikeCount), false, false) != null)
    }

    @Test
    fun staleCalculationCannotPublishOverNewerViewport() {
        val cache = MediaGridMorphPreparationCache()
        val oldCapture = capture(columns = 4, count = 20)
        val newCapture = capture(columns = 4, count = 20, firstVisibleOrdinal = 1)
        val oldToken = cache.request(oldCapture.identity, false, false)!!
        val newToken = cache.request(newCapture.identity, false, false)!!

        assertFalse(cache.publish(oldToken, buildMediaGridMorphPreparedPairs(oldCapture)))
        assertTrue(cache.publish(newToken, buildMediaGridMorphPreparedPairs(newCapture)))
        assertEquals(
            1,
            cache.snapshot().values.first().viewportSignature.firstVisibleMediaOrdinal,
        )
    }

    @Test
    fun preparedPairSelectionOnlyAddsAnchorAtGestureStart() {
        val prepared = pair(4, capture(columns = 4, count = 20))
        val plan = MediaGridMorphPlan.select(prepared, Offset(10f, 10f))

        assertTrue(plan.slots === prepared.slots)
        assertTrue(plan.headers === prepared.headers)
        assertEquals(1L, plan.anchor?.assetId)
    }

    @Test
    fun stateMachineUsesPreparedPairAndRejectsRevisionMismatch() {
        val prepared = pair(4, capture(columns = 4, count = 20))
        val session = MediaGridMorphSession.begin(
            currentColumnCount = 4,
            scale = 1.3f,
            pinchCenter = Offset(10f, 10f),
            sourceRevision = 10L,
            preparedPair = prepared,
        )!!
        assertEquals(MediaGridMorphPhase.Tracking, session.phase)
        assertEquals(MediaGridMorphPhase.Idle, session.updateTracking(11L).phase)
        assertNull(
            MediaGridMorphSession.begin(
                currentColumnCount = 4,
                scale = 1.3f,
                pinchCenter = Offset.Zero,
                sourceRevision = 11L,
                preparedPair = prepared,
            ),
        )
    }

    @Test
    fun settleTargetHandoffRemainsOneShot() {
        val prepared = pair(4, capture(columns = 4, count = 20))
        val session = MediaGridMorphSession.begin(4, 1.3f, Offset.Zero, 10L, prepared)!!.release(1f)
        val first = session.advanceSettle(1f, 180L, 10L)
        val second = first.session.advanceSettle(1f, 180L, 10L)

        assertEquals(5, first.targetColumnCountToHandoff)
        assertNull(second.targetColumnCountToHandoff)
    }

    private fun pair(
        columns: Int,
        capture: MediaGridMorphCapture,
        direction: MediaGridMorphDirection = MediaGridMorphDirection.IncreaseColumns,
    ): MediaGridMorphPreparedPair =
        buildMediaGridMorphPreparedPairs(capture).getValue(direction).also {
            assertEquals(columns, it.fromColumnCount)
        }

    private fun capture(
        columns: Int,
        count: Int,
        startOrdinal: Int = 0,
        firstVisibleOrdinal: Int = startOrdinal,
        sortBase: ClassifiedSortBase = ClassifiedSortBase.Default,
        dates: List<String> = List(count) { index -> "2026-07-${(index % 20 + 1).toString().padStart(2, '0')}T00:00:00Z" },
        likes: List<Long?> = List(count) { (count - it).toLong() * 100L },
        precedingMedia: MediaGridMorphCapturedMedia? = null,
    ): MediaGridMorphCapture {
        val identity = identity(
            columns = columns,
            firstVisibleOrdinal = firstVisibleOrdinal,
            lastVisibleOrdinal = (firstVisibleOrdinal + minOf(count, columns * 2) - 1),
            sort = sortBase,
        )
        val media = List(count) { index ->
            capturedMedia(
                assetId = (startOrdinal + index + 1).toLong(),
                ordinal = startOrdinal + index,
                date = dates[index],
                likeCount = likes[index],
            )
        }
        val visibleCount = minOf(count, columns * 2)
        val visibleRects = List(visibleCount) { index ->
            val ordinal = firstVisibleOrdinal + index
            val row = index / columns
            val column = index % columns
            MediaGridMorphCapturedRect(
                mediaOrdinal = ordinal,
                rect = Rect(
                    column * (1200f / columns),
                    row * 100f,
                    (column + 1) * (1200f / columns),
                    row * 100f + 100f,
                ),
            )
        }
        return MediaGridMorphCapture(
            identity = identity,
            viewport = Rect(0f, 0f, 1200f, 600f),
            cellSizePx = 100f,
            headerHeightPx = 40f,
            sortBase = sortBase,
            mediaOrdinalRange = startOrdinal..(startOrdinal + count - 1),
            media = media,
            precedingMedia = precedingMedia,
            visibleMediaRects = visibleRects,
            visibleHeaderRects = emptyList(),
        )
    }

    private fun capturedMedia(
        assetId: Long,
        ordinal: Int,
        date: String,
        likeCount: Long? = 0L,
    ) = MediaGridMorphCapturedMedia(
        assetId = assetId,
        mediaOrdinal = ordinal,
        itemIndex = ordinal,
        xCreatedAt = date,
        likeCount = likeCount,
    )

    private fun identity(
        columns: Int,
        revision: Long = 10L,
        width: Int = 1200,
        firstVisibleOrdinal: Int = 0,
        lastVisibleOrdinal: Int = 7,
        sort: ClassifiedSortBase = ClassifiedSortBase.Default,
    ): MediaGridMorphPreparationIdentity {
        val frameKey = MediaGridRenderKey(
            dataKey = MediaGridDataKey(
                sourceRevision = revision,
                hierarchyRevision = 1L,
                filter = TweetFilterState(),
                sort = ClassifiedSortState(baseOrder = sort),
            ),
            columnCount = columns,
        )
        val signature = MediaGridViewportSignature(
            renderKey = frameKey,
            firstVisibleItemIndex = firstVisibleOrdinal,
            lastVisibleItemIndex = lastVisibleOrdinal,
            firstVisibleMediaOrdinal = firstVisibleOrdinal,
            lastVisibleMediaOrdinal = lastVisibleOrdinal,
            viewportWidthPx = width,
            viewportHeightPx = 600,
            cellSizePx = 100,
            columnCount = columns,
        )
        return MediaGridMorphPreparationIdentity(revision, frameKey, columns, signature)
    }

    private fun headerBand(start: String?, end: String?) = MediaGridMorphHeaderBand(
        startKey = start,
        endKey = end,
        startTitle = start,
        endTitle = end,
        startRect = Rect(0f, 0f, 1200f, if (start == null) 0f else 40f),
        endRect = Rect(0f, 0f, 1200f, if (end == null) 0f else 40f),
        startFirstMediaOrdinal = 0,
        endFirstMediaOrdinal = 0,
        startFirstAssetId = 1L,
        endFirstAssetId = 1L,
    )

    private fun assertHeaderInputsAreUnique(pair: MediaGridMorphPreparedPair) {
        val startOrdinals = pair.headers.mapNotNull { it.startFirstMediaOrdinal }
        val endOrdinals = pair.headers.mapNotNull { it.endFirstMediaOrdinal }
        assertEquals(startOrdinals.size, startOrdinals.toSet().size)
        assertEquals(endOrdinals.size, endOrdinals.toSet().size)
    }
}
