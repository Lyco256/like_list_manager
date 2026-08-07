package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class MediaGridMorphTest {
    @Test
    fun claimBundleLocksDirectionAndKeepsProtectionAcrossOppositeSideTracking() {
        val capture = withSourceRows(capture(columns = 4, count = 24), 4)
        val identity = capture.identity.toInteractionIdentity()
        val increasePair = pair(4, capture, MediaGridMorphDirection.IncreaseColumns)
        val decreasePair = pair(4, capture, MediaGridMorphDirection.DecreaseColumns)
        val increasePlan = MediaGridMorphPlan.select(increasePair, Offset(100f, 50f))
        val decreasePlan = MediaGridMorphPlan.select(decreasePair, Offset(100f, 50f))
        val increaseModel = completeTestRenderModel(increasePlan, longArrayOf(1L, 2L))
        val decreaseModel = completeTestRenderModel(decreasePlan, longArrayOf(2L, 3L))
        val completeness = completeImageCompleteness()
        val bundle = MediaGridMorphClaimBundle(
            generation = 17L,
            identity = identity,
            firstPointerId = 10L,
            secondPointerId = 20L,
            initialDistance = 100f,
            fixedInitialCenter = Offset(100f, 50f),
            preparedIndexIdentity = 23L,
            textResourceIdentity = MediaGridMorphTextResourceIdentity(emptyList(), 1f, 1f, 1200),
            directions = mapOf(
                MediaGridMorphDirection.IncreaseColumns to MediaGridMorphDirectionClaimBundle(
                    MediaGridMorphDirection.IncreaseColumns,
                    5,
                    increasePlan,
                    increaseModel,
                    completeness,
                    increaseModel.protectedAssetIds,
                ),
                MediaGridMorphDirection.DecreaseColumns to MediaGridMorphDirectionClaimBundle(
                    MediaGridMorphDirection.DecreaseColumns,
                    3,
                    decreasePlan,
                    decreaseModel,
                    completeness,
                    decreaseModel.protectedAssetIds,
                ),
            ),
            protectedAssetUnion = longArrayOf(1L, 2L, 3L),
        )
        assertTrue(mediaGridMorphClaimBundleMatchesIdentity(bundle, identity))
        assertFalse(
            mediaGridMorphClaimBundleMatchesIdentity(
                bundle,
                identity.copy(viewportSignature = identity.viewportSignature.copy(firstVisibleItemIndex = 1)),
            ),
        )
        val protected = ArrayList<LongArray>()
        val released = ArrayList<LongArray>()
        val controller = MediaGridMorphInteractionController()
        controller.setProtectionCallbacks({ protected += it }, { released += it })

        assertTrue(controller.claimPointers(bundle, Offset(55f, 50f), Offset(145f, 50f)))
        val claimed = controller.snapshot()
        assertEquals(MediaGridMorphPhase.Tracking, claimed.phase)
        assertEquals(MediaGridMorphDirection.IncreaseColumns, claimed.direction)
        assertSame(increasePlan, claimed.plan)
        assertSame(increaseModel, claimed.activeRenderModel)
        assertEquals(MediaGridMorphDrawMode.Morph, claimed.drawMode)
        assertSame(bundle, claimed.claimBundle)
        assertEquals(listOf(1L, 2L, 3L), protected.single().toList())

        controller.updatePointers(Offset(50f, 50f), Offset(150f, 50f))
        assertEquals(MediaGridMorphPhase.Tracking, controller.snapshot().phase)
        assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
        assertSame(increasePlan, controller.snapshot().plan)
        assertSame(increaseModel, controller.snapshot().activeRenderModel)
        assertEquals(MediaGridMorphDrawMode.Morph, controller.snapshot().drawMode)
        assertEquals(0f, controller.snapshot().progress, 0.001f)
        assertTrue(released.isEmpty())

        controller.updatePointers(Offset(45f, 50f), Offset(155f, 50f))
        assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
        assertSame(increasePlan, controller.snapshot().plan)
        assertSame(increaseModel, controller.snapshot().activeRenderModel)
        assertEquals(MediaGridMorphDrawMode.Morph, controller.snapshot().drawMode)
        assertEquals(0f, controller.snapshot().progress, 0.001f)
        assertEquals(1, protected.size)
        assertTrue(released.isEmpty())

        repeat(2) {
            controller.updatePointers(Offset(50f, 50f), Offset(150f, 50f))
            assertEquals(MediaGridMorphPhase.Tracking, controller.snapshot().phase)
            assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
            assertEquals(0f, controller.snapshot().progress, 0.001f)
            controller.updatePointers(Offset(55f, 50f), Offset(145f, 50f))
            assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
            assertSame(increaseModel, controller.snapshot().activeRenderModel)
            controller.updatePointers(Offset(50f, 50f), Offset(150f, 50f))
            assertEquals(MediaGridMorphPhase.Tracking, controller.snapshot().phase)
            assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
            controller.updatePointers(Offset(45f, 50f), Offset(155f, 50f))
            assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
            assertSame(increasePlan, controller.snapshot().plan)
            assertSame(increaseModel, controller.snapshot().activeRenderModel)
        }
        assertEquals(0L, controller.settleSignal.value)
        assertTrue(released.isEmpty())
        assertEquals(1, protected.size)

        controller.cancelPointers()
        assertEquals(1, released.size)
        assertEquals(listOf(1L, 2L, 3L), released.single().toList())
        controller.cancelPointers()
        assertEquals(1, released.size)
    }

    @Test
    fun selectedDirectionCanClaimWhenOppositeDirectionIsIncomplete() {
        val capture = withSourceRows(capture(columns = 4, count = 24), 4)
        val identity = capture.identity.toInteractionIdentity()
        val increasePlan = MediaGridMorphPlan.select(
            pair(4, capture, MediaGridMorphDirection.IncreaseColumns),
            Offset(100f, 50f),
        )
        val decreasePlan = MediaGridMorphPlan.select(
            pair(4, capture, MediaGridMorphDirection.DecreaseColumns),
            Offset(100f, 50f),
        )
        val increaseModel = completeTestRenderModel(increasePlan, longArrayOf(1L, 2L))
        val incompleteDecreaseModel = completeTestRenderModel(decreasePlan, longArrayOf(2L, 3L)).copy(isComplete = false)
        val completeness = completeImageCompleteness()
        val bundle = MediaGridMorphClaimBundle(
            generation = 19L,
            identity = identity,
            firstPointerId = 10L,
            secondPointerId = 20L,
            initialDistance = 100f,
            fixedInitialCenter = Offset(100f, 50f),
            preparedIndexIdentity = 23L,
            textResourceIdentity = MediaGridMorphTextResourceIdentity(emptyList(), 1f, 1f, 1200),
            directions = mapOf(
                MediaGridMorphDirection.IncreaseColumns to MediaGridMorphDirectionClaimBundle(
                    MediaGridMorphDirection.IncreaseColumns,
                    5,
                    increasePlan,
                    increaseModel,
                    completeness,
                    increaseModel.protectedAssetIds,
                ),
                MediaGridMorphDirection.DecreaseColumns to MediaGridMorphDirectionClaimBundle(
                    MediaGridMorphDirection.DecreaseColumns,
                    3,
                    decreasePlan,
                    incompleteDecreaseModel,
                    completeness,
                    incompleteDecreaseModel.protectedAssetIds,
                ),
            ),
            protectedAssetUnion = longArrayOf(1L, 2L, 3L),
        )
        assertTrue(bundle.isCompleteForCurrentColumns())
        assertTrue(bundle.isCompleteFor(MediaGridMorphDirection.IncreaseColumns))
        assertFalse(bundle.isCompleteFor(MediaGridMorphDirection.DecreaseColumns))

        val controller = MediaGridMorphInteractionController()
        assertTrue(controller.claimPointers(bundle, Offset(55f, 50f), Offset(145f, 50f)))
        assertEquals(MediaGridMorphDrawMode.Morph, controller.snapshot().drawMode)
        controller.updatePointers(Offset(45f, 50f), Offset(155f, 50f))
        assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
        assertSame(increasePlan, controller.snapshot().plan)
        assertSame(increaseModel, controller.snapshot().activeRenderModel)
        assertEquals(MediaGridMorphDrawMode.Morph, controller.snapshot().drawMode)
        assertEquals(0f, controller.snapshot().progress, 0.001f)
    }

    @Test
    fun actualSelectedPlanRejectsClaimWhenPreparedImageIsMissing() {
        val capture = withSourceRows(capture(columns = 4, count = 24), 4)
        val textResources = MediaGridMorphTextResourceIndex(
            identity = MediaGridMorphTextResourceIdentity(emptyList(), 1f, 1f, 1200),
            layoutsByTitle = emptyMap(),
            surfaceColor = Color.Transparent,
            placeholderColor = Color.Transparent,
            textColor = Color.Transparent,
            horizontalTextPaddingPx = 0f,
            verticalTextPaddingPx = 0f,
        )
        val bundle = buildMediaGridMorphClaimBundle(
            capture = capture,
            preparedIndex = MediaGridResidentCanvasPreparedIndex(1L, emptyMap()),
            textResources = textResources,
            generation = 1L,
            firstPointerId = 1L,
            secondPointerId = 2L,
            firstPosition = Offset(50f, 50f),
            secondPosition = Offset(150f, 50f),
        )

        assertNotNull(bundle)
        assertFalse(bundle!!.isCompleteForCurrentColumns())
        assertTrue(bundle.directions.values.any {
            it.completeness.requiredSourceImageCount > it.completeness.resolvedSourceImageCount ||
                it.completeness.requiredTargetImageCount > it.completeness.resolvedTargetImageCount
        })
        val protected = ArrayList<LongArray>()
        val controller = MediaGridMorphInteractionController()
        controller.setProtectionCallbacks({ protected += it }, {})
        assertFalse(controller.claimPointers(bundle, Offset(55f, 50f), Offset(145f, 50f)))
        assertTrue(protected.isEmpty())
    }

    @Test
    fun allPointerUpIsAReleaseEvenWhenOneTrackedChangeIsMissing() {
        val source = locateInteractionSource()
        assertTrue(source.contains("val allPointersUp = !hasPressedPointer(event)"))
        assertTrue(source.contains("val normalRelease = mediaGridMorphShouldRelease("))
        assertTrue(source.contains("firstChangedToUp = first?.changedToUp() == true"))
        assertTrue(source.contains("secondChangedToUp = second?.changedToUp() == true"))
        assertTrue(source.contains("eventIsRelease = event.type == PointerEventType.Release"))
        assertTrue(source.contains("event.changes.any { it.changedToUp() }"))
        assertTrue(source.contains("trackedPointerMissing && hasPressedPointer(event)"))
    }

    @Test
    fun pointerReleaseDecisionDistinguishesMissingPointerAndPhysicalUp() {
        assertFalse(
            mediaGridMorphShouldRelease(
                wasBothPressed = true,
                bothPressed = true,
                firstChangedToUp = false,
                secondChangedToUp = false,
                eventIsRelease = false,
                allPointersUp = false,
                anyChangedToUp = false,
            ),
        )
        assertTrue(
            mediaGridMorphShouldRelease(
                wasBothPressed = true,
                bothPressed = false,
                firstChangedToUp = true,
                secondChangedToUp = false,
                eventIsRelease = true,
                allPointersUp = false,
                anyChangedToUp = true,
            ),
        )
        assertTrue(
            mediaGridMorphShouldRelease(
                wasBothPressed = true,
                bothPressed = false,
                firstChangedToUp = false,
                secondChangedToUp = false,
                eventIsRelease = true,
                allPointersUp = true,
                anyChangedToUp = true,
            ),
        )
        assertFalse(
            mediaGridMorphShouldRelease(
                wasBothPressed = true,
                bothPressed = false,
                firstChangedToUp = false,
                secondChangedToUp = false,
                eventIsRelease = true,
                allPointersUp = true,
                anyChangedToUp = false,
            ),
        )
    }

    @Test
    fun imageCrossfadeUsesOpaqueSourceOverAndPlaceholderRules() {
        val imageA = MediaGridMorphSlotContent.Image(1L)
        val imageB = MediaGridMorphSlotContent.Image(2L)
        val placeholder = MediaGridMorphSlotContent.Placeholder
        assertEquals(MediaGridMorphCellBlend(false, 1f, 0.5f), mediaGridMorphCellBlend(imageA, imageB, 0.5f))
        assertEquals(MediaGridMorphCellBlend(false, 1f, null), mediaGridMorphCellBlend(imageA, imageA, 0.5f))
        assertEquals(MediaGridMorphCellBlend(true, 0.5f, null), mediaGridMorphCellBlend(imageA, placeholder, 0.5f))
        assertEquals(MediaGridMorphCellBlend(true, null, 0.5f), mediaGridMorphCellBlend(placeholder, imageB, 0.5f))
        assertEquals(MediaGridMorphCellBlend(true, null, null), mediaGridMorphCellBlend(placeholder, placeholder, 0.5f))
    }

    @Test
    fun candidateClaimUsesDeadZoneAndSpanSlopWithoutCentroidRatio() {
        assertNull(
            mediaGridMorphCandidateDirection(
                initialDistance = 100f,
                currentDistance = 98f,
                touchSlop = 10f,
            ),
        )
        assertEquals(
            MediaGridMorphDirection.IncreaseColumns,
            mediaGridMorphCandidateDirection(
                initialDistance = 100f,
                currentDistance = 94f,
                touchSlop = 10f,
            ),
        )
        assertEquals(
            MediaGridMorphDirection.DecreaseColumns,
            mediaGridMorphCandidateDirection(
                initialDistance = 100f,
                currentDistance = 106f,
                touchSlop = 10f,
            ),
        )
        assertNull(
            mediaGridMorphCandidateDirection(
                initialDistance = 100f,
                currentDistance = 100f,
                touchSlop = 10f,
            ),
        )
    }

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
        assertEquals(4, mediaGridColumnCountAfterPinchRelease(4, 1.04f))
        assertEquals(4, mediaGridColumnCountAfterPinchRelease(4, 0.96f))
        assertEquals(5, mediaGridColumnCountAfterPinchRelease(4, 1.12f))
        assertEquals(3, mediaGridColumnCountAfterPinchRelease(4, 0.84f))
        assertEquals(5, mediaGridColumnCountAfterPinchRelease(4, 2f))
        assertEquals(3, mediaGridColumnCountAfterPinchRelease(4, 0.25f))
        assertEquals(12, mediaGridColumnCountAfterPinchRelease(12, 2f))
        assertEquals(2, mediaGridColumnCountAfterPinchRelease(2, 0.25f))
    }

    @Test
    fun legacyReleasePinchUsesOneAdjacentColumnAndClampsBounds() {
        assertEquals(5, mediaGridLegacyColumnCountForScale(4, 1.2f))
        assertEquals(3, mediaGridLegacyColumnCountForScale(4, 0.8f))
        assertEquals(4, mediaGridLegacyColumnCountForScale(4, 1.05f))
        assertEquals(12, mediaGridLegacyColumnCountForScale(12, 2f))
        assertEquals(2, mediaGridLegacyColumnCountForScale(2, 0.2f))
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
    fun edgeSlotsHaveExplicitPlaceholderEndpointsAndFixedImageRects() {
        val increase = pair(4, capture(columns = 4, count = 10))
            .slots.first { it.startAssetId == null && it.endAssetId != null }
        assertEquals(MediaGridMorphSlotContent.Placeholder, increase.startContent)
        assertEquals(MediaGridMorphSlotContent.Image(increase.endAssetId!!), increase.endContent)
        assertEquals(MediaGridMorphSlotEdge.Increase, increase.edge)
        assertEquals(increase.endRect, increase.endImageDrawingRect)

        val decrease = pair(5, capture(columns = 5, count = 10), MediaGridMorphDirection.DecreaseColumns)
            .slots.first { it.startAssetId != null && it.endAssetId == null }
        assertEquals(MediaGridMorphSlotContent.Image(decrease.startAssetId!!), decrease.startContent)
        assertEquals(MediaGridMorphSlotContent.Placeholder, decrease.endContent)
        assertEquals(MediaGridMorphSlotEdge.Decrease, decrease.edge)
        assertEquals(decrease.startRect, decrease.startImageDrawingRect)
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

    @Test
    fun directDistanceScaleAndExistingProgressFunctionsCoverBothDirections() {
        assertEquals(2f, mediaGridMorphScale(100f, 50f)!!, 0.001f)
        assertNull(mediaGridMorphScale(100f, 0f))
        assertNull(mediaGridMorphScale(100f, Float.NaN))
        assertNull(mediaGridMorphScale(100f, Float.POSITIVE_INFINITY))
        val deadZone = MediaGridMorphDefaults.DeadZoneScale
        val inverseDeadZone = 1f / deadZone
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { expected ->
            val increaseScale = deadZone + expected * deadZone * (deadZone - 1f)
            val decreaseScale = inverseDeadZone - expected * (1f - inverseDeadZone)
            assertEquals(
                expected,
                mediaGridMorphProgressForScale(increaseScale, MediaGridMorphDirection.IncreaseColumns),
                0.001f,
            )
            assertEquals(
                expected,
                mediaGridMorphProgressForScale(decreaseScale, MediaGridMorphDirection.DecreaseColumns),
                0.001f,
            )
        }
        assertNull(mediaGridMorphDirectionForScale(1.01f))
    }

    @Test
    fun consumePolicyAcceptsOnlyTheTwoFixedPointers() {
        assertFalse(mediaGridMorphShouldConsumePointer(false, 1L, 1L, 2L))
        assertTrue(mediaGridMorphShouldConsumePointer(true, 1L, 1L, 2L))
        assertTrue(mediaGridMorphShouldConsumePointer(true, 2L, 1L, 2L))
        assertFalse(mediaGridMorphShouldConsumePointer(true, 3L, 1L, 2L))
    }

    @Test
    fun controllerDoesNotAcceptPreparedDirectionBeyondColumnBounds() {
        listOf(
            2 to MediaGridMorphDirection.DecreaseColumns,
            12 to MediaGridMorphDirection.IncreaseColumns,
        ).forEach { (columns, blockedDirection) ->
            val capture = capture(columns = columns, count = 24)
            val pairs = buildMediaGridMorphPreparedPairs(capture)
            assertFalse(pairs.containsKey(blockedDirection))
            val controller = MediaGridMorphInteractionController()
            assertTrue(
                controller.beginPointers(
                    capture.identity.toInteractionIdentity(),
                    pairs,
                    1L,
                    2L,
                    Offset(0f, 0f),
                    Offset(100f, 0f),
                ),
            )
            val blockedScale = if (blockedDirection == MediaGridMorphDirection.IncreaseColumns) 1.5f else 0.5f
            val distance = 100f / blockedScale
            controller.updatePointers(
                Offset(50f - distance / 2f, 0f),
                Offset(50f + distance / 2f, 0f),
            )
            assertNull(controller.snapshot().plan)
            assertEquals(0f, controller.snapshot().progress, 0.001f)
        }
    }

    @Test
    fun controllerReturnsThroughDeadZoneWithoutSwitchingLockedDirection() {
        val capture = capture(columns = 4, count = 24)
        val pairs = buildMediaGridMorphPreparedPairs(capture)
        val controller = MediaGridMorphInteractionController()
        val identity = capture.identity.toInteractionIdentity()
        assertTrue(
            controller.beginPointers(
                identity,
                pairs,
                10L,
                20L,
                Offset(50f, 50f),
                Offset(150f, 50f),
            ),
        )

        controller.updatePointers(Offset(60f, 50f), Offset(140f, 50f))
        val increasePlan = controller.snapshot().plan
        assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
        assertTrue(controller.snapshot().progress > 0f)

        controller.updatePointers(Offset(50f, 50f), Offset(150f, 50f))
        assertSame(increasePlan, controller.snapshot().plan)
        assertEquals(0f, controller.snapshot().progress, 0.001f)
        assertEquals(Offset.Zero, controller.snapshot().correction)
        assertEquals(MediaGridMorphPhase.Tracking, controller.snapshot().phase)
        assertFalse(controller.isSettling(controller.snapshot().interactionGeneration))

        val inverseDeadZone = 1f / MediaGridMorphDefaults.DeadZoneScale
        val distance = 100f / inverseDeadZone
        controller.updatePointers(
            Offset(100f - distance / 2f, 50f),
            Offset(100f + distance / 2f, 50f),
        )
        assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
        assertEquals(0f, controller.snapshot().progress, 0.001f)
        assertEquals(Offset.Zero, controller.snapshot().correction)
        assertEquals(MediaGridMorphPhase.Tracking, controller.snapshot().phase)
        assertSame(increasePlan, controller.snapshot().plan)

        val beforeInvalidDistance = controller.snapshot()
        controller.updatePointers(Offset(100f, 50f), Offset(100f, 50f))
        assertSame(beforeInvalidDistance, controller.snapshot())
    }

    @Test
    fun focalAnchorUsesFixedInitialCenterWithoutCentroidTranslation() {
        val capture = capture(columns = 4, count = 24)
        val pair = pair(4, capture)
        val identity = capture.identity.toInteractionIdentity()
        val initialCenter = Offset(100f, 80f)
        val controller = MediaGridMorphInteractionController()
        assertTrue(
            controller.beginPointers(
                identity = identity,
                preparedPairsSnapshot = mapOf(MediaGridMorphDirection.IncreaseColumns to pair),
                firstPointerId = 1L,
                secondPointerId = 2L,
                firstPosition = initialCenter - Offset(50f, 0f),
                secondPosition = initialCenter + Offset(50f, 0f),
            ),
        )
        val progress = 0.6f
        val deadZone = MediaGridMorphDefaults.DeadZoneScale
        val scale = deadZone + progress * deadZone * (deadZone - 1f)
        val distance = 100f / scale
        controller.updatePointers(
            initialCenter - Offset(distance / 2f, 0f),
            initialCenter + Offset(distance / 2f, 0f),
        )
        val plan = controller.snapshot().plan!!
        val anchor = plan.anchor!!
        val fixedCorrection = controller.snapshot().correction

        val movedCenter = initialCenter + Offset(240f, -130f)
        controller.updatePointers(
            movedCenter - Offset(distance / 2f, 0f),
            movedCenter + Offset(distance / 2f, 0f),
        )
        assertEquals(movedCenter, controller.snapshot().currentPinchCenter)
        assertEquals(fixedCorrection.x, controller.snapshot().correction.x, 0.001f)
        assertEquals(fixedCorrection.y, controller.snapshot().correction.y, 0.001f)

        val rect = mediaGridMorphRect(anchor.slot, progress)
        val focal = Offset(
            rect.left + rect.width * anchor.focalU,
            rect.top + rect.height * anchor.focalV,
        ) - plan.viewport.topLeft + fixedCorrection
        assertEquals(initialCenter.x, focal.x, 0.001f)
        assertEquals(initialCenter.y, focal.y, 0.001f)
    }

    @Test
    fun candidateTrackingDoesNotResetBeforeAPlanIsSelected() {
        val capture = capture(columns = 4, count = 24)
        val identity = capture.identity.toInteractionIdentity()
        val controller = MediaGridMorphInteractionController()
        assertTrue(
            controller.beginPointers(
                identity = identity,
                preparedPairsSnapshot = buildMediaGridMorphPreparedPairs(capture),
                firstPointerId = 1L,
                secondPointerId = 2L,
                firstPosition = Offset(50f, 80f),
                secondPosition = Offset(150f, 80f),
            ),
        )

        controller.updateIdentity(identity)

        assertEquals(MediaGridMorphPhase.Tracking, controller.snapshot().phase)
        assertNull(controller.snapshot().plan)
        assertEquals(0f, controller.snapshot().progress, 0.001f)
    }

    @Test
    fun slotOutsidePinchAndNonZeroViewportOriginDoNotJumpAtProgressZero() {
        val base = pair(4, capture(columns = 4, count = 24))
        val shifted = base.copy(
            viewport = Rect(20f, -40f, 1220f, 560f),
            slots = base.slots.map {
                it.copy(
                    startRect = it.startRect.translate(Offset(20f, -40f)),
                    endRect = it.endRect.translate(Offset(20f, -40f)),
                )
            },
        )
        val outsideCenter = Offset(-30f, 700f)
        val plan = MediaGridMorphPlan.select(shifted, outsideCenter)

        assertTrue(plan.anchor!!.focalU !in 0f..1f || plan.anchor!!.focalV !in 0f..1f)
        val initialCorrection = mediaGridMorphFocalCorrection(plan, 0f, outsideCenter)
        assertEquals(0f, initialCorrection.x, 0.001f)
        assertEquals(0f, initialCorrection.y, 0.001f)
    }

    @Test
    fun elapsedSettleIsLinearForCurrentAndTargetAndHandoffIsExactlyOnce() {
        val capture = capture(columns = 4, count = 24)
        val pairs = buildMediaGridMorphPreparedPairs(capture)
        val identity = capture.identity.toInteractionIdentity()
        val requests = ArrayList<MediaGridMorphHandoffRequest>()
        val target = MediaGridMorphInteractionController(requests::add)
        beginAtProgress(target, identity, pairs, 0.5f)
        target.releasePointers()
        val generation = target.snapshot().interactionGeneration
        val expected = listOf(0L to 0.5f, 45L to 0.625f, 90L to 0.75f, 135L to 0.875f)
        expected.forEach { (elapsed, progress) ->
            target.advanceSettleElapsed(generation, elapsed)
            assertEquals(progress, target.snapshot().progress, 0.001f)
        }
        target.advanceSettleElapsed(generation, 157L)
        assertEquals(0.5f + 0.5f * (157f / 180f), target.snapshot().progress, 0.001f)
        target.advanceSettleElapsed(generation, 180L)
        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, target.snapshot().phase)
        assertEquals(1f, target.snapshot().progress, 0.001f)
        val expectedFinalCorrection = mediaGridMorphFocalCorrection(
            target.snapshot().plan!!,
            1f,
            target.snapshot().currentPinchCenter!!,
        )
        assertEquals(expectedFinalCorrection.x, target.snapshot().correction.x, 0.001f)
        assertEquals(expectedFinalCorrection.y, target.snapshot().correction.y, 0.001f)
        assertEquals(1, requests.size)
        target.advanceSettleElapsed(generation, 360L)
        assertEquals(1, requests.size)
        assertSame(requests.single(), target.snapshot().handoffRequest)
        target.completeHandoff(generation)
        assertEquals(MediaGridMorphPhase.Idle, target.snapshot().phase)
        assertEquals(5, target.snapshot().fromColumnCount)

        val current = MediaGridMorphInteractionController()
        beginAtProgress(current, identity, pairs, 0.25f)
        val releaseCorrection = current.snapshot().correction
        current.releasePointers()
        val currentGeneration = current.snapshot().interactionGeneration
        current.advanceSettleElapsed(currentGeneration, 90L)
        assertEquals(0.125f, current.snapshot().progress, 0.001f)
        assertEquals(releaseCorrection.x / 2f, current.snapshot().correction.x, 0.001f)
        assertEquals(releaseCorrection.y / 2f, current.snapshot().correction.y, 0.001f)
        current.advanceSettleElapsed(currentGeneration, 180L)
        assertEquals(MediaGridMorphPhase.RevealingCurrent, current.snapshot().phase)
        assertEquals(MediaGridMorphDrawMode.RevealCurrent, current.snapshot().drawMode)
        assertEquals(Offset.Zero, current.snapshot().correction)
        current.acknowledgeCurrentReveal(currentGeneration)
        assertEquals(MediaGridMorphPhase.Idle, current.snapshot().phase)
    }

    @Test
    fun frameSettleUsesTheComposeFrameClockAfterPointerRelease() {
        val capture = capture(columns = 4, count = 24)
        val pairs = buildMediaGridMorphPreparedPairs(capture)
        val identity = capture.identity.toInteractionIdentity()
        val controller = MediaGridMorphInteractionController()
        beginAtProgress(controller, identity, pairs, 0.75f)
        controller.releasePointers()

        controller.advanceSettleFrame(10_000_000_000L)
        assertEquals(MediaGridMorphPhase.SettlingToTarget, controller.snapshot().phase)
        controller.advanceSettleFrame(10_180_000_000L)
        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, controller.snapshot().phase)
    }

    @Test
    fun awaitingHandoffCanCancelToFromAndStaleIdentityInvalidatesRequest() {
        val capture = capture(columns = 4, count = 24)
        val pairs = buildMediaGridMorphPreparedPairs(capture)
        val identity = capture.identity.toInteractionIdentity()

        val cancelled = MediaGridMorphInteractionController()
        beginAtProgress(cancelled, identity, pairs, 0.75f)
        cancelled.releasePointers()
        val cancelGeneration = cancelled.snapshot().interactionGeneration
        cancelled.advanceSettleElapsed(cancelGeneration, 180L)
        cancelled.cancelHandoff(cancelGeneration)
        assertEquals(MediaGridMorphPhase.Idle, cancelled.snapshot().phase)
        assertEquals(4, cancelled.snapshot().fromColumnCount)
        assertNull(cancelled.snapshot().handoffRequest)

        val stale = MediaGridMorphInteractionController()
        beginAtProgress(stale, identity, pairs, 0.75f)
        stale.releasePointers()
        val staleGeneration = stale.snapshot().interactionGeneration
        stale.advanceSettleElapsed(staleGeneration, 180L)
        stale.updateIdentity(identity.copy(frameKey = identity.frameKey.copy(columnCount = 9)))
        assertEquals(MediaGridMorphPhase.Failed, stale.snapshot().phase)
        assertEquals(MediaGridMorphFailureReason.IdentityMismatch, stale.snapshot().failureReason)
        assertNull(stale.snapshot().handoffRequest)
    }

    @Test
    fun staleIdentityAndCancelPreventOldSettleAndHandoff() {
        val capture = capture(columns = 4, count = 24)
        val pairs = buildMediaGridMorphPreparedPairs(capture)
        val requests = ArrayList<MediaGridMorphHandoffRequest>()
        val controller = MediaGridMorphInteractionController(requests::add)
        val identity = capture.identity.toInteractionIdentity()
        beginAtProgress(controller, identity, pairs, 0.75f)
        controller.releasePointers()
        val oldGeneration = controller.snapshot().interactionGeneration
        controller.updateIdentity(identity.copy(sourceRevision = 11L))
        controller.advanceSettleElapsed(oldGeneration, 180L)

        assertEquals(MediaGridMorphPhase.Failed, controller.snapshot().phase)
        assertEquals(MediaGridMorphFailureReason.IdentityMismatch, controller.snapshot().failureReason)
        assertEquals(0f, controller.snapshot().progress, 0.001f)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun everyTargetLayoutFromTwoThroughTwelveUsesItsOwnSquareCellSize() {
        for (fromColumns in 2..12) {
            for (direction in MediaGridMorphDirection.entries) {
                val toColumns = mediaGridMorphTargetColumnCount(fromColumns, direction)
                if (toColumns == fromColumns) continue
                val prepared = pair(
                    columns = fromColumns,
                    capture = capture(columns = fromColumns, count = 72),
                    direction = direction,
                )
                val expectedSize = prepared.viewport.width / toColumns
                prepared.targetLayout.media.forEach { media ->
                    assertEquals("$fromColumns->$toColumns width", expectedSize, media.rect.width, 0.001f)
                    assertEquals("$fromColumns->$toColumns height", expectedSize, media.rect.height, 0.001f)
                }
            }
        }
    }

    @Test
    fun defaultTargetLayoutKeepsGlobalColumnAndBottomAlignmentAtDatasetEnd() {
        val base = capture(columns = 2, count = 10, startOrdinal = 62)
        val endVisible = (66..71).mapIndexed { index, ordinal ->
            val row = index / 2
            val column = index % 2
            MediaGridMorphCapturedRect(
                mediaOrdinal = ordinal,
                rect = Rect(
                    column * 600f,
                    row * 200f,
                    (column + 1) * 600f,
                    row * 200f + 200f,
                ),
            )
        }
        val prepared = pair(
            columns = 2,
            capture = base.copy(visibleMediaRects = endVisible),
            direction = MediaGridMorphDirection.IncreaseColumns,
        )

        val first = prepared.targetLayout.media.first { it.mediaOrdinal == 62 }
        val last = prepared.targetLayout.media.first { it.mediaOrdinal == 71 }
        assertEquals(2, first.column)
        assertEquals(800f, first.rect.left, 0.001f)
        assertEquals(prepared.viewport.bottom, last.rect.bottom, 0.001f)
    }

    @Test
    fun capturedStartCellsStayMeasuredWhileStartOverscanUsesCurrentSquareSize() {
        val prepared = pair(4, capture(columns = 4, count = 24))
        val visible = prepared.startLayout.media.first { it.mediaOrdinal == 0 }
        val overscan = prepared.startLayout.media.first { it.mediaOrdinal == 12 }

        assertEquals(300f, visible.rect.width, 0.001f)
        assertEquals(100f, visible.rect.height, 0.001f)
        assertEquals(300f, overscan.rect.width, 0.001f)
        assertEquals(300f, overscan.rect.height, 0.001f)
    }

    @Test
    fun requestedAdjacentTransitionsRemainContinuousAndNonOverlapping() {
        val transitions = listOf(
            2 to MediaGridMorphDirection.IncreaseColumns,
            3 to MediaGridMorphDirection.DecreaseColumns,
            4 to MediaGridMorphDirection.IncreaseColumns,
            5 to MediaGridMorphDirection.DecreaseColumns,
            8 to MediaGridMorphDirection.IncreaseColumns,
            9 to MediaGridMorphDirection.DecreaseColumns,
            11 to MediaGridMorphDirection.IncreaseColumns,
            12 to MediaGridMorphDirection.DecreaseColumns,
        )
        transitions.forEach { (fromColumns, direction) ->
            val prepared = pair(fromColumns, capture(columns = fromColumns, count = 72), direction)
            listOf(0f, 0.5f, 1f).forEach { progress ->
                val rects = prepared.slots.map { mediaGridMorphRect(it, progress) }
                    .filter { it.width > 0f && it.height > 0f }
                rects.forEachIndexed { index, first ->
                    for (otherIndex in index + 1 until rects.size) {
                        val second = rects[otherIndex]
                        val overlapWidth = minOf(first.right, second.right) - maxOf(first.left, second.left)
                        val overlapHeight = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
                        assertTrue(
                            "$fromColumns progress=$progress slots $index/$otherIndex overlap",
                            overlapWidth <= 0.001f || overlapHeight <= 0.001f,
                        )
                    }
                }
                prepared.slots.forEach { slot ->
                    val rect = mediaGridMorphRect(slot, progress)
                    assertTrue(rect.left.isFinite() && rect.top.isFinite())
                    assertTrue(rect.right.isFinite() && rect.bottom.isFinite())
                }
            }
        }
    }

    @Test
    fun canvasCoordinatesSubtractAnyViewportOriginAndApplyCorrection() {
        val viewport = Rect(20f, -30f, 220f, 170f)
        val local = mediaGridMorphCanvasRect(
            startRect = Rect(40f, -10f, 80f, 30f),
            endRect = Rect(60f, 10f, 120f, 70f),
            viewport = viewport,
            correction = Offset(3f, -4f),
            progress = 0.5f,
        )

        assertEquals(33f, local.left, 0.001f)
        assertEquals(26f, local.top, 0.001f)
        assertEquals(83f, local.right, 0.001f)
        assertEquals(76f, local.bottom, 0.001f)
    }

    @Test
    fun alignedRowsRestoreEveryStartOffsetWithoutLeftPadding() {
        (ClassifiedMediaGridMinColumnCount..ClassifiedMediaGridMaxColumnCount).forEach { columns ->
            (0 until columns).forEach { offset ->
                val capture = withSourceRows(capture(
                    columns = columns,
                    count = columns * 3,
                    startOrdinal = offset,
                ), columns)
                val direction = if (columns == ClassifiedMediaGridMaxColumnCount) {
                    MediaGridMorphDirection.DecreaseColumns
                } else {
                    MediaGridMorphDirection.IncreaseColumns
                }
                val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(direction)
                val rows = requireNotNull(pair.viewportPlanTemplate).sourceCanonicalRows
                val first = rows.first()
                assertEquals(offset, first.cells.first().column)
                assertEquals((offset until columns).toList(), first.cells.map { it.column })
                assertEquals((offset until columns).toList(), first.cells.map { it.mediaOrdinal })
                assertEquals((0 until columns).toList(), rows[1].cells.map { it.column })
            }
        }
    }

    @Test
    fun bucketAlignedRowsStopOffsetAtBucketBoundary() {
        val current = capturedMedia(99L, 9, "2026-07-08T00:00:00Z")
        val sameBucket = listOf(
            capturedMedia(97L, 7, "2026-07-08T00:00:00Z"),
            capturedMedia(98L, 8, "2026-07-08T00:00:00Z"),
        )
        val capture = capture(
            columns = 4,
            count = 12,
            startOrdinal = 9,
            sortBase = ClassifiedSortBase.PostTime,
        ).copy(
            media = listOf(current) + (10..20).map { ordinal ->
                capturedMedia(ordinal.toLong(), ordinal, "2026-07-08T00:00:00Z")
            },
            precedingMedia = sameBucket.last(),
            precedingMediaWindow = sameBucket,
        )
        assertEquals(2, capture.startColumnOffset(4))

        val boundaryCapture = capture.copy(
            precedingMedia = capturedMedia(96L, 6, "2026-07-07T00:00:00Z"),
            precedingMediaWindow = listOf(capturedMedia(96L, 6, "2026-07-07T00:00:00Z")),
        )
        assertEquals(0, boundaryCapture.startColumnOffset(4))
    }

    @Test
    fun bucketAlignedRowsWrapAfterACompletePrecedingRow() {
        val bucketDate = "2026-07-08T00:00:00Z"
        val capture = capture(
            columns = 2,
            count = 4,
            startOrdinal = 2,
            sortBase = ClassifiedSortBase.PostTime,
            dates = List(4) { bucketDate },
        ).copy(
            precedingMedia = capturedMedia(2L, 1, bucketDate),
            precedingMediaWindow = listOf(
                capturedMedia(1L, 0, bucketDate),
                capturedMedia(2L, 1, bucketDate),
            ),
        )

        assertEquals(0, capture.startColumnOffset(2))
    }

    @Test
    fun headerlessFourColumnOffsetTwoKeeps0011And1100SourceImagesAtEveryProgress() {
        val capture = withSourceRows(capture(columns = 4, count = 20, startOrdinal = 2), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture)
            .getValue(MediaGridMorphDirection.IncreaseColumns)
        val template = requireNotNull(pair.viewportPlanTemplate)
        assertEquals(listOf(2, 3), template.sourceCanonicalRows.first().cells.map { it.column })
        val plan = template.select(Offset(600f, 150f))
        val sourceAssets = capture.sourceRows
            .flatMap { it.cells }
            .map { it.assetId }
            .toSet()
        val planSourceAssets = plan.rowPlans
            .flatMap { it.cells }
            .mapNotNull { (it.startContent as? MediaGridMorphSlotContent.Image)?.assetId }
            .toSet()
        assertTrue(sourceAssets.isNotEmpty())
        assertTrue("source=$sourceAssets plan=$planSourceAssets", planSourceAssets.containsAll(sourceAssets))
        listOf(0.01f, 0.1f, 0.25f, 0.5f, 0.75f).forEach { progress ->
            plan.rowPlans.flatMap { it.cells }
                .filter { it.startContent is MediaGridMorphSlotContent.Image }
                .forEach { cell ->
                    val rect = mediaGridMorphRowCellRect(plan, cell, progress)
                    assertTrue("source cell vanished at progress=$progress", rect.width > 0f && rect.height > 0f)
                }
        }
    }

    @Test
    fun rowReflowUsesUniformLatticeForRequiredAdjacentColumnPairs() {
        listOf(2 to 3, 4 to 5, 8 to 9, 11 to 12).forEach { (from, to) ->
            val pair = buildMediaGridMorphRowPreparedPairs(withSourceRows(capture(from, to * 3), from))
                .getValue(MediaGridMorphDirection.IncreaseColumns)
            val plan = requireNotNull(pair.viewportPlanTemplate).select(Offset(600f, 150f))
            assertEquals(pair.viewport.width / from, plan.sourceCellSize, 0.0001f)
            assertEquals(pair.viewport.width / to, plan.targetCellSize, 0.0001f)
            assertTrue(plan.relativeRowRange.first <= 0 && 0 <= plan.relativeRowRange.last)
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
                val cellSize = mediaGridMorphCurrentCellSize(plan, progress)
                plan.rowPlans.flatMap { it.cells }.forEach { cell ->
                    val rect = mediaGridMorphRowCellRect(plan, cell, progress)
                    assertEquals(cellSize, rect.width, 0.0001f)
                    assertEquals(cellSize, rect.height, 0.0001f)
                    assertEquals(
                        pair.viewport.left + cell.column * cellSize,
                        rect.left,
                        0.0001f,
                    )
                }
            }
            val added = plan.rowPlans.first { it.relativeRow == 0 }.cells.last()
            assertTrue(added.startContent is MediaGridMorphSlotContent.NoMedia)
        }
        listOf(3 to 2, 5 to 4, 9 to 8, 12 to 11).forEach { (from, to) ->
            val pair = buildMediaGridMorphRowPreparedPairs(withSourceRows(capture(from, from * 3), from))
                .getValue(MediaGridMorphDirection.DecreaseColumns)
            val plan = requireNotNull(pair.viewportPlanTemplate).select(Offset(600f, 150f))
            val removed = plan.rowPlans.first { it.relativeRow == 0 }.cells.last()
            assertEquals(from, plan.rowPlans.first { it.relativeRow == 0 }.cells.size)
            assertTrue(removed.endContent is MediaGridMorphSlotContent.NoMedia)
            listOf(0f, 0.5f, 1f).forEach { progress ->
                val cellSize = mediaGridMorphCurrentCellSize(plan, progress)
                plan.rowPlans.flatMap { it.cells }.forEach { cell ->
                    val rect = mediaGridMorphRowCellRect(plan, cell, progress)
                    assertEquals(cellSize, rect.width, 0.0001f)
                    assertEquals(cellSize, rect.height, 0.0001f)
                }
            }
        }
    }

    @Test
    fun requiredRenderSetOnlyProtectsSweptViewportCellsAndVisibleHeaders() {
        val capture = withSourceRows(capture(4, 40), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture)
            .getValue(MediaGridMorphDirection.IncreaseColumns)
        val template = requireNotNull(pair.viewportPlanTemplate)
        val centers = mediaGridMorphPossibleFocalCenters(pair)
        assertEquals(template.sourceRows.distinctBy { it.rowKey }.size, centers.size)

        val plan = MediaGridMorphPlan.selectRowReflow(pair, Offset(600f, 150f))
        val selected = requireNotNull(plan.viewportPlan)
        val required = plan.requiredRenderSet()
        val allCellIdentities = selected.rowPlans.flatMap { row ->
            row.cells.map { MediaGridMorphRequiredCellIdentity(it.relativeRow, it.column) }
        }.toSet()
        assertTrue(required.requiredCellIdentities.isNotEmpty())
        assertTrue(required.requiredCellIdentities.size < allCellIdentities.size)
        assertEquals(
            required.protectedAssetIds,
            required.requiredSourceAssetIds + required.requiredTargetAssetIds,
        )
        assertEquals(allCellIdentities.size - required.requiredCellIdentities.size, required.optionalCellCount)

        val incoming = selected.rowPlans
            .flatMap { it.cells }
            .firstOrNull { it.startContent is MediaGridMorphSlotContent.NoMedia && it.endContent is MediaGridMorphSlotContent.Image }
            ?: error("increase pair did not contain an incoming right-edge cell")
        assertTrue(
            MediaGridMorphRequiredCellIdentity(incoming.relativeRow, incoming.column) in
                required.requiredCellIdentities,
        )
        val imageToImage = selected.rowPlans
            .flatMap { it.cells }
            .firstOrNull {
                it.startContent is MediaGridMorphSlotContent.Image &&
                    it.endContent is MediaGridMorphSlotContent.Image
            }
            ?: error("selected plan did not contain an Image-to-Image cell")
        val imageIdentity = MediaGridMorphRequiredCellIdentity(imageToImage.relativeRow, imageToImage.column)
        assertTrue(imageIdentity in required.requiredCellIdentities)
        assertTrue((imageToImage.startContent as MediaGridMorphSlotContent.Image).assetId in required.requiredSourceAssetIds)
        assertTrue((imageToImage.endContent as MediaGridMorphSlotContent.Image).assetId in required.requiredTargetAssetIds)
        assertTrue(
            selected.rowPlans.flatMap { it.cells }.any {
                MediaGridMorphRequiredCellIdentity(it.relativeRow, it.column) !in required.requiredCellIdentities
            },
        )
        assertEquals(required.protectedAssetIds.size, required.protectedAssetIds.distinct().size)
    }

    @Test
    fun canonicalOverscanNullKeyDoesNotRejectActualVisibleRows() {
        val capture = withSourceRows(capture(4, 40), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture)
            .getValue(MediaGridMorphDirection.IncreaseColumns)
        val template = requireNotNull(pair.viewportPlanTemplate)
        val extraOverscan = template.sourceCanonicalRows.last().copy(rowKey = null)
        val selected = template.copy(
            sourceCanonicalRows = template.sourceCanonicalRows + extraOverscan,
        ).select(Offset(600f, 150f))
        assertTrue(selected.rowPlans.isNotEmpty())
    }

    @Test
    fun requiredRenderSetDoesNotProtectHeadersOutsideSweptViewport() {
        val dates = List(40) { index ->
            "2026-07-${(index / 4 + 1).toString().padStart(2, '0')}T00:00:00Z"
        }
        val capture = withSourceRows(
            capture(4, dates.size, sortBase = ClassifiedSortBase.PostTime, dates = dates),
            4,
        )
        val pair = buildMediaGridMorphRowPreparedPairs(capture)
            .getValue(MediaGridMorphDirection.IncreaseColumns)
        val plan = MediaGridMorphPlan.selectRowReflow(pair, Offset(600f, 150f))
        val selected = requireNotNull(plan.viewportPlan)
        val required = plan.requiredRenderSet()
        val optionalHeaders = selected.headerPlans.filter { header ->
            MediaGridMorphRequiredHeaderIdentity(header.relativeRow, header.startKey, header.endKey) !in
                required.requiredHeaderIdentities
        }
        assertTrue(optionalHeaders.isNotEmpty())
        assertEquals(optionalHeaders.size, required.optionalHeaderCount)
        optionalHeaders.flatMap { listOfNotNull(it.startTitle, it.endTitle) }.forEach { title ->
            assertTrue(title !in required.requiredSourceHeaderTitles)
            assertTrue(title !in required.requiredTargetHeaderTitles)
        }
    }

    @Test
    fun sourceRowsAreMatchedByExactCanonicalRowIdentityWithoutGlobalRowCountEquality() {
        val capture = withSourceRows(capture(4, 40), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture)
            .getValue(MediaGridMorphDirection.IncreaseColumns)
        val template = requireNotNull(pair.viewportPlanTemplate)
        val valid = template.select(Offset(600f, 150f))
        assertTrue(valid.rowPlans.isNotEmpty())
        val visibleKey = template.sourceRows.first().rowKey ?: error("visible row key missing")
        val corruptedCanonical = template.sourceCanonicalRows.map { row ->
            if (row.rowKey == visibleKey) {
                row.copy(cells = row.cells.mapIndexed { index, cell ->
                    if (index == 0) cell.copy(assetId = cell.assetId + 1000L) else cell
                })
            } else {
                row
            }
        }
        val invalid = template.copy(sourceCanonicalRows = corruptedCanonical).select(Offset(600f, 150f))
        assertTrue(invalid.rowPlans.isEmpty())
    }

    @Test
    fun rowReflowUsesFixedFocalYWithoutCentroidTranslation() {
        val capture = withSourceRows(capture(4, 20), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(MediaGridMorphDirection.IncreaseColumns)
        val plan = requireNotNull(pair.viewportPlanTemplate).select(Offset(600f, 450f))
        val sourceFocal = capture.sourceRows[1]
        val focal = plan.rowPlans.first { it.relativeRow == 0 }
        assertEquals(0.5f, plan.focalV, 0.0001f)
        assertEquals(sourceFocal.top, mediaGridMorphRowCellRect(plan, focal.cells.first(), 0f).top, 0.0001f)
        assertEquals(
            plan.fixedFocalCenterY,
            mediaGridMorphRowCellRect(plan, focal.cells.first(), 0.5f).top +
                plan.focalV * mediaGridMorphCurrentCellSize(plan, 0.5f),
            0.0001f,
        )
        val rowPlan = MediaGridMorphPlan.selectRowReflow(pair, Offset(600f, 150f))
        assertEquals(Offset.Zero, mediaGridMorphFocalCorrection(rowPlan, 0.5f, Offset(620f, 400f)))
    }

    @Test
    fun rowReflowProgressUsesOnlyDistanceAndCellWidthRatio() {
        listOf(
            Triple(2, 3, 2f / 3f),
            Triple(4, 5, 4f / 5f),
            Triple(5, 4, 5f / 4f),
            Triple(8, 9, 8f / 9f),
            Triple(11, 12, 11f / 12f),
        ).forEach { (from, to, targetRatio) ->
            assertEquals(0f, mediaGridMorphProgressForDistance(100f, 100f, from, to), 0.0001f)
            assertEquals(1f, mediaGridMorphProgressForDistance(100f, 100f * targetRatio, from, to), 0.0001f)
            assertEquals(0.5f, mediaGridMorphProgressForDistance(100f, 100f * (1f + targetRatio) / 2f, from, to), 0.0001f)
        }
        assertEquals(1f, mediaGridMorphProgressForDistance(100f, 80f, 4, 5), 0.0001f)
        assertEquals(1f, mediaGridMorphProgressForDistance(100f, 125f, 5, 4), 0.0001f)
    }

    @Test
    fun rowReflowSelectsFocalOrdinalFirstAndUsesFractionOnlyWhenMissing() {
        val directCapture = withSourceRows(capture(4, 24), 4)
        val directPair = buildMediaGridMorphRowPreparedPairs(directCapture)
            .getValue(MediaGridMorphDirection.IncreaseColumns)
        val direct = requireNotNull(directPair.viewportPlanTemplate).select(Offset(750f, 450f))
        assertFalse(direct.usedOrdinalFractionFallback)
        assertEquals(directCapture.sourceRows[1].cells[2].mediaOrdinal, direct.focalMediaOrdinal)

        val missingSourceRow = directCapture.sourceRows[1].copy(
            cells = directCapture.sourceRows[1].cells.map { it.copy(mediaOrdinal = 99, assetId = 999) },
        )
        val fallbackCapture = directCapture.copy(sourceRows = directCapture.sourceRows.mapIndexed { index, row ->
            if (index == 1) missingSourceRow else row
        })
        val fallbackPair = buildMediaGridMorphRowPreparedPairs(fallbackCapture)
            .getValue(MediaGridMorphDirection.IncreaseColumns)
        val fallback = requireNotNull(fallbackPair.viewportPlanTemplate).select(Offset(750f, 450f))
        assertTrue(fallback.rowPlans.isEmpty())
        assertTrue(fallback.relativeRowRange.isEmpty())
    }

    @Test
    fun rowReflowHeaderPlansAreIndependentBandsAndBoundedToRows() {
        val dates = List(24) { index -> "2026-07-${(index / 4 + 1).toString().padStart(2, '0')}T00:00:00Z" }
        val capture = withSourceRows(capture(4, dates.size, sortBase = ClassifiedSortBase.PostTime, dates = dates), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(MediaGridMorphDirection.IncreaseColumns)
        val plan = requireNotNull(pair.viewportPlanTemplate).select(Offset(600f, 150f))
        assertTrue(plan.headerPlans.isNotEmpty())
        plan.headerPlans.forEach { header ->
            assertTrue(header.startHeightPx >= 0f)
            assertTrue(header.endHeightPx >= 0f)
        }
        assertTrue(plan.rowPlans.size <= capture.sourceRows.size + capture.media.size + 8)
    }

    @Test
    fun rowReflowCrossfadeKeepsRightEdgeContentInUniformCurrentCell() {
        val capture = withSourceRows(capture(4, 10), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(MediaGridMorphDirection.IncreaseColumns)
        val plan = requireNotNull(pair.viewportPlanTemplate).select(Offset(600f, 150f))
        val newRight = plan.rowPlans.first { it.relativeRow == 0 }.cells.last()
        val half = mediaGridMorphRowCellRect(plan, newRight, 0.5f)
        assertEquals(mediaGridMorphCurrentCellSize(plan, 0.5f), half.width, 0.0001f)
        assertTrue(half.left >= plan.viewport.right - half.width)
        assertTrue(newRight.startContent is MediaGridMorphSlotContent.NoMedia)
        assertTrue(newRight.endContent is MediaGridMorphSlotContent.Image)
    }

    @Test
    fun rowReflowKeepsRemovedRightEdgeCellSquareAndOutsideViewport() {
        val capture = withSourceRows(capture(5, 15), 5)
        val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(MediaGridMorphDirection.DecreaseColumns)
        val plan = requireNotNull(pair.viewportPlanTemplate).select(Offset(600f, 150f))
        val removed = plan.rowPlans.first { it.relativeRow == 0 }.cells.last()

        listOf(0f, 0.5f, 1f).forEach { progress ->
            val rect = mediaGridMorphRowCellRect(plan, removed, progress)
            val size = mediaGridMorphCurrentCellSize(plan, progress)
            assertEquals(size, rect.width, 0.0001f)
            assertEquals(size, rect.height, 0.0001f)
            assertEquals(
                plan.viewport.left + removed.column * size,
                rect.left,
                0.0001f,
            )
            assertEquals(rect.left + size, rect.right, 0.0001f)
        }
        val end = mediaGridMorphRowCellRect(plan, removed, 1f)
        assertEquals(plan.viewport.right, end.left, 0.0001f)
        assertTrue(end.right > plan.viewport.right)
        assertTrue(removed.endContent is MediaGridMorphSlotContent.NoMedia)
    }

    @Test
    fun rowReflowHeaderHeightDoesNotChangeMediaCellSizeOrInsertInteriorZeroRow() {
        val dates = List(24) { index -> "2026-07-${(index / 4 + 1).toString().padStart(2, '0')}T00:00:00Z" }
        val capture = withSourceRows(
            capture(4, dates.size, sortBase = ClassifiedSortBase.PostTime, dates = dates),
            4,
        )
        val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(MediaGridMorphDirection.IncreaseColumns)
        val plan = requireNotNull(pair.viewportPlanTemplate).select(Offset(600f, 450f))

        assertTrue(plan.headerPlans.any { it.startHeightPx != it.endHeightPx })
        listOf(0f, 0.5f, 1f).forEach { progress ->
            val size = mediaGridMorphCurrentCellSize(plan, progress)
            plan.rowPlans.flatMap { it.cells }.forEach { cell ->
                val rect = mediaGridMorphRowCellRect(plan, cell, progress)
                assertEquals(size, rect.height, 0.0001f)
                assertEquals(rect.top + size, rect.bottom, 0.0001f)
            }
        }
    }

    @Test
    fun rowReflowPairDoesNotBuildLegacyDatasetSlots() {
        val capture = withSourceRows(capture(4, 20), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture)
            .getValue(MediaGridMorphDirection.IncreaseColumns)

        assertTrue(pair.slots.isEmpty())
        assertTrue(pair.headers.isEmpty())
        assertTrue(pair.startLayout.media.isEmpty())
        assertTrue(pair.targetLayout.media.isEmpty())
        assertNotNull(MediaGridMorphPlan.selectRowReflow(pair, Offset(600f, 150f)).viewportPlan)
    }

    @Test
    fun claimIdentityMustMatchTheCaptureViewportRatherThanAStaleComposeSnapshot() {
        val capture = withSourceRows(capture(4, 20), 4)
        val pair = buildMediaGridMorphPreparedPairs(capture).getValue(MediaGridMorphDirection.IncreaseColumns)
        val pairs = mapOf(MediaGridMorphDirection.IncreaseColumns to pair)
        val capturedIdentity = capture.identity.toInteractionIdentity()
        val staleIdentity = capturedIdentity.copy(
            viewportSignature = capturedIdentity.viewportSignature.copy(firstVisibleItemIndex = 1),
        )

        val staleController = MediaGridMorphInteractionController()
        assertFalse(
            staleController.beginPointers(
                identity = staleIdentity,
                preparedPairsSnapshot = pairs,
                firstPointerId = 1L,
                secondPointerId = 2L,
                firstPosition = Offset(100f, 150f),
                secondPosition = Offset(300f, 150f),
            ),
        )

        val capturedController = MediaGridMorphInteractionController()
        assertTrue(
            capturedController.beginPointers(
                identity = capturedIdentity,
                preparedPairsSnapshot = pairs,
                firstPointerId = 1L,
                secondPointerId = 2L,
                firstPosition = Offset(100f, 150f),
                secondPosition = Offset(300f, 150f),
            ),
        )
    }

    @Test
    fun capturedRowPlanReachesTargetHandoffAfterReleaseSettle() {
        val capture = withSourceRows(capture(4, 20), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(MediaGridMorphDirection.IncreaseColumns)
        var handoffRequests = 0
        val controller = MediaGridMorphInteractionController { handoffRequests++ }
        val identity = capture.identity.toInteractionIdentity()
        assertTrue(
            controller.beginPointers(
                identity = identity,
                preparedPairsSnapshot = mapOf(MediaGridMorphDirection.IncreaseColumns to pair),
                firstPointerId = 1L,
                secondPointerId = 2L,
                firstPosition = Offset(100f, 150f),
                secondPosition = Offset(300f, 150f),
            ),
        )
        controller.updatePointers(Offset(100f, 150f), Offset(120f, 150f))
        assertEquals(MediaGridMorphDirection.IncreaseColumns, controller.snapshot().direction)
        assertTrue(controller.snapshot().progress >= MediaGridMorphDefaults.ReleaseThreshold)

        val generation = controller.snapshot().interactionGeneration
        controller.releasePointers()
        controller.advanceSettleElapsed(generation, MediaGridMorphDefaults.SettleDurationMillis)

        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, controller.snapshot().phase)
        assertEquals(1, handoffRequests)
        assertEquals(
            controller.snapshot().plan?.viewportPlan?.targetFocalMediaOrdinal,
            controller.snapshot().handoffRequest?.targetAnchor?.mediaOrdinal,
        )
    }

    @Test
    fun activeClaimKeepsCapturedPlanWhenLazyGridViewportSignatureMoves() {
        val capture = withSourceRows(capture(4, 20), 4)
        val identity = capture.identity.toInteractionIdentity()
        val controller = MediaGridMorphInteractionController()
        assertTrue(
            controller.beginPointers(
                identity = identity,
                preparedPairsSnapshot = buildMediaGridMorphRowPreparedPairs(capture),
                firstPointerId = 1L,
                secondPointerId = 2L,
                firstPosition = Offset(100f, 150f),
                secondPosition = Offset(300f, 150f),
            ),
        )
        controller.updatePointers(Offset(100f, 150f), Offset(120f, 150f))
        controller.updateIdentity(
            identity.copy(
                viewportSignature = identity.viewportSignature.copy(
                    firstVisibleItemIndex = identity.viewportSignature.firstVisibleItemIndex + 1,
                    firstVisibleMediaOrdinal = identity.viewportSignature.firstVisibleMediaOrdinal + 1,
                ),
            ),
        )
        assertEquals(MediaGridMorphPhase.Tracking, controller.snapshot().phase)
        assertNotNull(controller.snapshot().plan)
    }

    @Test
    fun capturedRowPlanAtDatasetEndStillProducesTargetAnchor() {
        val capture = withSourceRows(capture(4, 3, firstVisibleOrdinal = 2), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(MediaGridMorphDirection.IncreaseColumns)
        var handoffRequests = 0
        val controller = MediaGridMorphInteractionController { handoffRequests++ }
        val identity = capture.identity.toInteractionIdentity()
        assertTrue(
            controller.beginPointers(
                identity = identity,
                preparedPairsSnapshot = mapOf(MediaGridMorphDirection.IncreaseColumns to pair),
                firstPointerId = 1L,
                secondPointerId = 2L,
                firstPosition = Offset(100f, 300f),
                secondPosition = Offset(300f, 300f),
            ),
        )
        controller.updatePointers(Offset(100f, 300f), Offset(120f, 300f))
        controller.releasePointers()
        val generation = controller.snapshot().interactionGeneration
        controller.advanceSettleElapsed(generation, MediaGridMorphDefaults.SettleDurationMillis)

        assertEquals(MediaGridMorphPhase.AwaitingGridHandoff, controller.snapshot().phase)
        assertEquals(1, handoffRequests)
    }

    @Test
    fun targetViewportClampsAShortDatasetToTheRealGridTop() {
        val capture = withSourceRows(capture(4, 3), 4)
        val pair = buildMediaGridMorphRowPreparedPairs(capture).getValue(MediaGridMorphDirection.IncreaseColumns)
        val plan = requireNotNull(pair.viewportPlanTemplate).select(Offset(600f, 300f))

        assertEquals(0f, plan.targetAnchorRowTop, 0.0001f)
        val focal = plan.rowPlans.first { it.relativeRow == 0 }.cells.first()
        assertEquals(plan.targetCellSize, mediaGridMorphRowCellRect(plan, focal, 1f).height, 0.0001f)
    }

    private fun pair(
        columns: Int,
        capture: MediaGridMorphCapture,
        direction: MediaGridMorphDirection = MediaGridMorphDirection.IncreaseColumns,
    ): MediaGridMorphPreparedPair =
        buildMediaGridMorphPreparedPairs(capture).getValue(direction).also {
            assertEquals(columns, it.fromColumnCount)
        }

    private fun completeTestRenderModel(
        plan: MediaGridMorphPlan,
        protectedAssetIds: LongArray,
    ) = MediaGridMorphRowRenderModel(
        viewport = plan.viewport,
        sourceCellSize = 100f,
        targetCellSize = 100f,
        fixedFocalCenterY = plan.viewport.center.y,
        focalV = 0.5f,
        rows = emptyList(),
        cells = emptyList(),
        headers = emptyList(),
        surfaceColor = Color.Transparent,
        placeholderColor = Color.Transparent,
        textColor = Color.Transparent,
        horizontalTextPaddingPx = 0f,
        verticalTextPaddingPx = 0f,
        protectedAssetIds = protectedAssetIds,
        requiredSourceImageCount = 0,
        resolvedSourceImageCount = 0,
        requiredTargetImageCount = 0,
        resolvedTargetImageCount = 0,
        unresolvedRequiredAssetId = null,
        headerTextComplete = true,
        isComplete = true,
    )

    private fun completeImageCompleteness() = MediaGridMorphImageCompleteness(
        requiredSourceImageCount = 0,
        resolvedSourceImageCount = 0,
        requiredTargetImageCount = 0,
        resolvedTargetImageCount = 0,
        unresolvedRequiredAssetId = null,
        headerTextComplete = true,
        geometryComplete = true,
    )

    private fun locateInteractionSource(): String = java.io.File(
        "src/main/java/com/lyco256/llm/MediaGridMorphInteraction.kt",
    ).let { file ->
        if (file.isFile) file.readText() else java.io.File(
            "app/src/main/java/com/lyco256/llm/MediaGridMorphInteraction.kt",
        ).readText()
    }

    private fun withSourceRows(capture: MediaGridMorphCapture, columns: Int): MediaGridMorphCapture {
        val byOrdinal = capture.media.associateBy { it.mediaOrdinal }
        val sourceCellSize = capture.viewport.width / columns
        val rows = capture.visibleMediaRects
            .groupBy { it.rect.top to it.rect.bottom }
            .entries
            .sortedBy { it.key.first }
            .mapIndexedNotNull outer@{ rowIndex, (_, rects) ->
                val cells = rects.sortedBy { it.rect.left }.mapIndexedNotNull { _, rect ->
                    val media = byOrdinal[rect.mediaOrdinal] ?: return@mapIndexedNotNull null
                    val actualColumn = (rect.rect.left / sourceCellSize).roundToInt()
                        .coerceIn(0, columns - 1)
                    MediaGridMorphCapturedCell(
                        column = actualColumn,
                        mediaOrdinal = rect.mediaOrdinal,
                        assetId = media.assetId,
                        rect = rect.rect,
                        isPartiallyVisible = rect.isPartiallyVisible,
                    )
                }
                if (cells.isEmpty()) return@outer null
                val top = rowIndex * sourceCellSize
                MediaGridMorphCapturedRow(
                    visibleRow = rowIndex,
                    top = top,
                    bottom = top + sourceCellSize,
                    cells = cells.map { cell ->
                        cell.copy(rect = Rect(cell.rect.left, top, cell.rect.right, top + sourceCellSize))
                    },
                    isPartiallyVisible = rects.any { it.isPartiallyVisible },
                    isActualVisibleSourceRow = true,
                )
            }
        return capture.copy(sourceRows = rows, totalMediaCount = maxOf(capture.totalMediaCount, capture.mediaOrdinalRange.last + 1))
    }

    private fun MediaGridMorphPreparationIdentity.toInteractionIdentity() =
        MediaGridMorphInteractionIdentity(
            sourceRevision = sourceRevision,
            frameKey = frameKey,
            currentColumnCount = columnCount,
            viewportSignature = viewportSignature,
        )

    private fun beginAtProgress(
        controller: MediaGridMorphInteractionController,
        identity: MediaGridMorphInteractionIdentity,
        pairs: Map<MediaGridMorphDirection, MediaGridMorphPreparedPair>,
        progress: Float,
    ) {
        val initialDistance = 100f
        assertTrue(
            controller.beginPointers(
                identity,
                pairs,
                1L,
                2L,
                Offset(100f - initialDistance / 2f, 80f),
                Offset(100f + initialDistance / 2f, 80f),
            ),
        )
        val deadZone = MediaGridMorphDefaults.DeadZoneScale
        val scale = deadZone + progress * deadZone * (deadZone - 1f)
        val distance = initialDistance / scale
        controller.updatePointers(
            Offset(100f - distance / 2f, 80f),
            Offset(100f + distance / 2f, 80f),
        )
        assertEquals(progress, controller.snapshot().progress, 0.001f)
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
            val row = ordinal / columns
            val column = ordinal % columns
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
        val frameEntries = List((startOrdinal + count).coerceAtLeast(1)) { ordinal ->
            val localIndex = (ordinal - startOrdinal).coerceAtLeast(0)
            MediaGridEntry(
                entryId = ordinal.toLong() + 1L,
                clipId = ordinal.toLong() + 1L,
                assetId = ordinal.toLong() + 1L,
                mediaKey = "media-$ordinal",
                mediaIndex = ordinal,
                type = "photo",
                displayUrl = null,
                downloadState = "downloaded",
                localPath = null,
                xCreatedAt = dates.getOrElse(localIndex) { dates.last() },
                likeCount = likes.getOrElse(localIndex) { likes.lastOrNull() },
            )
        }
        val exactFrame = buildMediaGridFrameData(
            entries = frameEntries,
            sort = identity.frameKey.dataKey.sort,
            columnCount = columns,
            dataKey = identity.frameKey.dataKey,
        )
        val exactIndexes = listOf(columns - 1, columns + 1)
            .filter { it in ClassifiedMediaGridMinColumnCount..ClassifiedMediaGridMaxColumnCount }
            .associateWith { targetColumns ->
                buildMediaGridMorphExactTargetLayoutIndex(
                    frame = exactFrame,
                    targetColumnCount = targetColumns,
                    viewportWidthPx = 1200,
                    viewportHeightPx = 600,
                    headerHeightPx = 40f,
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
            exactTargetLayoutIndexes = exactIndexes,
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
