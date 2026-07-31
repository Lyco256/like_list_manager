package com.lyco256.llm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGridMorphTest {
    @Test
    fun candidateClaimUsesDeadZoneTouchSlopAndCentroidArbitration() {
        assertNull(
            mediaGridMorphCandidateDirection(
                initialDistance = 100f,
                currentDistance = 98f,
                initialCentroid = Offset.Zero,
                currentCentroid = Offset.Zero,
                touchSlop = 10f,
            ),
        )
        assertNull(
            mediaGridMorphCandidateDirection(
                initialDistance = 100f,
                currentDistance = 94f,
                initialCentroid = Offset.Zero,
                currentCentroid = Offset(0f, 20f),
                touchSlop = 10f,
            ),
        )
        assertEquals(
            MediaGridMorphDirection.IncreaseColumns,
            mediaGridMorphCandidateDirection(
                initialDistance = 100f,
                currentDistance = 94f,
                initialCentroid = Offset.Zero,
                currentCentroid = Offset(0f, 2f),
                touchSlop = 10f,
            ),
        )
        assertEquals(
            MediaGridMorphDirection.DecreaseColumns,
            mediaGridMorphCandidateDirection(
                initialDistance = 100f,
                currentDistance = 106f,
                initialCentroid = Offset.Zero,
                currentCentroid = Offset.Zero,
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
    fun controllerReturnsThroughDeadZoneAndSwitchesPreparedDirectionContinuously() {
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

        val inverseDeadZone = 1f / MediaGridMorphDefaults.DeadZoneScale
        val distance = 100f / inverseDeadZone
        controller.updatePointers(
            Offset(100f - distance / 2f, 50f),
            Offset(100f + distance / 2f, 50f),
        )
        assertEquals(MediaGridMorphDirection.DecreaseColumns, controller.snapshot().direction)
        assertEquals(0f, controller.snapshot().progress, 0.001f)
        assertEquals(Offset.Zero, controller.snapshot().correction)
        assertTrue(controller.snapshot().plan !== increasePlan)

        val beforeInvalidDistance = controller.snapshot()
        controller.updatePointers(Offset(100f, 50f), Offset(100f, 50f))
        assertSame(beforeInvalidDistance, controller.snapshot())
    }

    @Test
    fun focalAnchorKeepsNormalizedPointAndTracksCenterInTwoDimensions() {
        val pair = pair(4, capture(columns = 4, count = 24))
        val initialCenter = Offset(80f, 60f)
        val plan = MediaGridMorphPlan.select(pair, initialCenter)
        val anchor = plan.anchor!!

        val initialCorrection = mediaGridMorphFocalCorrection(plan, 0f, initialCenter)
        assertEquals(0f, initialCorrection.x, 0.001f)
        assertEquals(0f, initialCorrection.y, 0.001f)
        val progress = 0.6f
        val fixedCorrection = mediaGridMorphFocalCorrection(plan, progress, initialCenter)
        val movedCorrection = mediaGridMorphFocalCorrection(
            plan,
            progress,
            initialCenter + Offset(23f, -17f),
        )
        assertEquals(23f, movedCorrection.x - fixedCorrection.x, 0.001f)
        assertEquals(-17f, movedCorrection.y - fixedCorrection.y, 0.001f)

        val rect = mediaGridMorphRect(anchor.slot, progress)
        val focal = Offset(
            rect.left + rect.width * anchor.focalU,
            rect.top + rect.height * anchor.focalV,
        ) - plan.viewport.topLeft + fixedCorrection
        assertEquals(initialCenter.x, focal.x, 0.001f)
        assertEquals(initialCenter.y, focal.y, 0.001f)
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
        assertEquals(MediaGridMorphPhase.Idle, current.snapshot().phase)
        assertEquals(Offset.Zero, current.snapshot().correction)
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
        assertEquals(MediaGridMorphPhase.Idle, stale.snapshot().phase)
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

        assertEquals(MediaGridMorphPhase.Idle, controller.snapshot().phase)
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

    private fun pair(
        columns: Int,
        capture: MediaGridMorphCapture,
        direction: MediaGridMorphDirection = MediaGridMorphDirection.IncreaseColumns,
    ): MediaGridMorphPreparedPair =
        buildMediaGridMorphPreparedPairs(capture).getValue(direction).also {
            assertEquals(columns, it.fromColumnCount)
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
