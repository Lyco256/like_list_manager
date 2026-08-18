package com.lyco256.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTagDraftStateTest {
    @Test
    fun applyIsEnabledOnlyWhileDraftDiffersFromPersistedTags() {
        val persisted = setOf(1L, 2L)
        val initial = ClipTagDraftState.fromPersisted(persisted)

        assertFalse(initial.canApply)
        assertTrue(initial.edit(setOf(1L, 2L, 3L)).canApply)
        assertFalse(initial.edit(setOf(1L, 2L, 3L)).edit(persisted).canApply)
        assertTrue(initial.edit(emptySet()).canApply)
    }

    @Test
    fun cleanDraftTracksRoomUpdatesButDirtyDraftSurvivesUnrelatedUpdates() {
        val initial = ClipTagDraftState.fromPersisted(setOf(1L))

        assertEquals(
            setOf(2L),
            initial.reconcilePersisted(setOf(2L)).draftTagIds,
        )

        val dirty = initial.edit(setOf(1L, 3L))
        val reconciled = dirty.reconcilePersisted(setOf(1L, 2L))
        assertEquals(setOf(1L, 3L), reconciled.draftTagIds)
        assertEquals(setOf(1L, 2L), reconciled.persistedTagIds)
        assertTrue(reconciled.canApply)
    }

    @Test
    fun failedApplyKeepsDraftForRetry() {
        val applying = ClipTagDraftState.fromPersisted(setOf(1L))
            .edit(setOf(2L))
            .beginApply()
        val failed = applying.applyFailed("failure")

        assertEquals(setOf(2L), failed.draftTagIds)
        assertEquals(setOf(1L), failed.persistedTagIds)
        assertEquals("failure", failed.errorMessage)
        assertTrue(failed.canApply)
    }

    @Test
    fun successfulApplyIgnoresStaleSourceUntilRoomPublishesCommittedTags() {
        val succeeded = ClipTagDraftState.fromPersisted(setOf(1L))
            .edit(setOf(2L))
            .beginApply()
            .applySucceeded()

        assertFalse(succeeded.canApply)
        assertEquals(setOf(2L), succeeded.reconcilePersisted(setOf(1L)).draftTagIds)

        val published = succeeded.reconcilePersisted(setOf(2L))
        assertEquals(setOf(2L), published.persistedTagIds)
        assertEquals(setOf(2L), published.draftTagIds)
        assertFalse(published.isDirty)
    }

    @Test
    fun successfulApplyAcceptsFutureRoomUpdatesWhenCommittedTagsArrivedFirst() {
        val roomUpdatedWhileApplying = ClipTagDraftState.fromPersisted(setOf(1L))
            .edit(setOf(2L))
            .beginApply()
            .reconcilePersisted(setOf(2L))
        val succeeded = roomUpdatedWhileApplying.applySucceeded()

        val laterRoomUpdate = succeeded.reconcilePersisted(setOf(3L))
        assertEquals(setOf(3L), laterRoomUpdate.persistedTagIds)
        assertEquals(setOf(3L), laterRoomUpdate.draftTagIds)
    }

    @Test
    fun reconcileRemovesDraftsForClipsNoLongerInTheList() {
        val drafts = mapOf(
            1L to ClipTagDraftState.fromPersisted(setOf(1L)).edit(setOf(2L)),
            2L to ClipTagDraftState.fromPersisted(setOf(3L)),
        )

        val reconciled = reconcileClipTagDrafts(
            drafts = drafts,
            persistedTagIdsByClip = mapOf(1L to setOf(1L)),
        )

        assertEquals(setOf(1L), reconciled.keys)
        assertEquals(setOf(2L), reconciled.getValue(1L).draftTagIds)
    }
}
