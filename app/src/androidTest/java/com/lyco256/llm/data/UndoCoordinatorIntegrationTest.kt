package com.lyco256.llm.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UndoCoordinatorIntegrationTest {
    private lateinit var database: LikeListDatabase
    private lateinit var provider: TestDatabaseProvider

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LikeListDatabase::class.java,
        ).build()
        provider = TestDatabaseProvider(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveObserveUndoAndClearOnlyReversesPayloadField() = runBlocking {
        database.clipDao().insertClip(clip(summary = "before", likeCount = 1))
        val coordinator = coordinatorWithSummaryHandler()

        coordinator.commitDatabaseEdit(SummaryEditedUndoPayload(CLIP_ID, "before"), "概要を保存しました") {
            it.clipDao().updateClip(onlyClip().copy(summary = "after"))
        }
        val observed = coordinator.pendingUndo.first { it != null }
        assertEquals("概要を保存しました", observed?.message)

        // This is an internal update and deliberately does not call the coordinator.
        database.clipDao().updateLikeCount(CLIP_ID, 99, "internal")
        assertSame(UndoCoordinatorResult.Success, coordinator.undo())

        assertEquals("before", onlyClip().summary)
        assertEquals(99L, onlyClip().likeCount)
        assertNull(coordinator.current())
    }

    @Test
    fun newSlotFinalizesAndReplacesTheOnlyOldSlot() = runBlocking {
        var finalizedPayload: UndoPayload? = null
        val coordinator = UndoCoordinator(
            provider,
            handlers = listOf(
                UndoActionHandler(
                    UndoActionType.TAG_CREATED,
                    undo = { _, _ -> },
                    finalize = { _, payload -> finalizedPayload = payload },
                ),
            ),
            now = { "fixed" },
        )
        val oldPayload = TagCreatedUndoPayload(1)
        assertSame(UndoCoordinatorResult.Success, coordinator.replacePending(oldPayload, "old"))

        assertSame(
            UndoCoordinatorResult.Success,
            coordinator.replacePending(GroupCreatedUndoPayload(2), "new"),
        )

        assertEquals(oldPayload, finalizedPayload)
        assertEquals(UndoActionType.GROUP_CREATED.storageValue, coordinator.current()?.actionType)
    }

    @Test
    fun invalidateClearsSlotButInternalUpdateDoesNot() = runBlocking {
        val coordinator = UndoCoordinator(provider)
        coordinator.replacePending(TagCreatedUndoPayload(1), "pending")

        database.clipDao().upsertSyncState(SyncStateEntity(id = 1, lastSyncAt = "internal"))
        assertEquals(UndoActionType.TAG_CREATED.storageValue, coordinator.current()?.actionType)

        assertSame(UndoCoordinatorResult.Success, coordinator.invalidateForUserEdit())
        assertNull(coordinator.current())
    }

    @Test
    fun failedNewEditRollsBackEditAndNewSlotWithoutRestoringOldSlot() = runBlocking {
        database.clipDao().insertClip(clip(summary = "before"))
        val coordinator = UndoCoordinator(provider)
        coordinator.replacePending(TagCreatedUndoPayload(1), "old")
        val failure = IllegalStateException("edit failed")

        val thrown = runCatching {
            coordinator.commitDatabaseEdit(SummaryEditedUndoPayload(CLIP_ID, "before"), "new") {
                it.clipDao().updateClip(onlyClip().copy(summary = "partial"))
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals("before", onlyClip().summary)
        assertNull(coordinator.current())
    }

    @Test
    fun malformedAndUnknownPayloadsAreNotExecutedOrDiscarded() = runBlocking {
        var undoCalls = 0
        val coordinator = UndoCoordinator(
            provider,
            listOf(UndoActionHandler(UndoActionType.TAG_CREATED, undo = { _, _ -> undoCalls++ })),
        )
        val malformed = UndoEntity(
            actionType = UndoActionType.TAG_CREATED.storageValue,
            payloadJson = "{broken",
            message = "bad",
            createdAt = "now",
        )
        database.undoDao().replaceSlot(malformed)

        assertTrue(coordinator.undo() is UndoCoordinatorResult.DecodeError)
        assertEquals(malformed, coordinator.current())

        val unknown = malformed.copy(payloadJson = """{"schemaVersion":1,"actionType":"future_action","payload":{}}""")
        database.undoDao().replaceSlot(unknown)
        assertTrue(coordinator.undo() is UndoCoordinatorResult.DecodeError)
        assertEquals(unknown, coordinator.current())
        assertEquals(0, undoCalls)
    }

    @Test
    fun undoFailureRollsBackInverseOperationAndKeepsSlot() = runBlocking {
        database.clipDao().insertClip(clip(summary = "after"))
        val failure = IllegalStateException("undo failed")
        val coordinator = UndoCoordinator(
            provider,
            listOf(
                UndoActionHandler(UndoActionType.SUMMARY_EDITED, undo = { db, _ ->
                    db.clipDao().updateClip(onlyClip().copy(summary = "partial inverse"))
                    throw failure
                }),
            ),
        )
        coordinator.replacePending(SummaryEditedUndoPayload(CLIP_ID, "before"), "pending")

        val result = coordinator.undo()

        assertTrue(result is UndoCoordinatorResult.Failure)
        assertSame(failure, (result as UndoCoordinatorResult.Failure).cause)
        assertEquals("after", onlyClip().summary)
        assertTrue(coordinator.current() != null)
    }

    @Test
    fun actionTypeColumnMismatchKeepsSlot() = runBlocking {
        val coordinator = UndoCoordinator(provider)
        val slot = UndoEntity(
            actionType = UndoActionType.GROUP_CREATED.storageValue,
            payloadJson = UndoPayloadCodec.encode(TagCreatedUndoPayload(1)),
            message = "mismatch",
            createdAt = "now",
        )
        database.undoDao().replaceSlot(slot)

        assertTrue(coordinator.finalizePending() is UndoCoordinatorResult.ActionTypeMismatch)
        assertEquals(slot, coordinator.current())
    }

    @Test
    fun staleNotificationCannotUndoOrFinalizeReplacementSlot() = runBlocking {
        var undoCalls = 0
        val coordinator = UndoCoordinator(
            provider,
            listOf(UndoActionHandler(UndoActionType.TAG_CREATED, undo = { _, _ -> undoCalls++ })),
        )
        coordinator.replacePending(TagCreatedUndoPayload(1), "old")
        val oldSlot = checkNotNull(coordinator.current())
        coordinator.replacePending(TagCreatedUndoPayload(2), "new")
        val newSlot = checkNotNull(coordinator.current())

        assertSame(UndoCoordinatorResult.SlotChanged, coordinator.finalizePending(oldSlot))
        assertSame(UndoCoordinatorResult.SlotChanged, coordinator.undo(oldSlot))
        assertEquals(0, undoCalls)
        assertEquals(newSlot, coordinator.current())
    }

    @Test
    fun identicalConsecutiveEditsStillReceiveDifferentSlotIdentities() = runBlocking {
        val coordinator = UndoCoordinator(provider, now = { "2026-08-12T00:00:00Z" })
        val payload = TagCreatedUndoPayload(1)
        coordinator.replacePending(payload, "same")
        val first = checkNotNull(coordinator.current())
        coordinator.replacePending(payload, "same")
        val second = checkNotNull(coordinator.current())

        assertTrue(first != second)
        assertSame(UndoCoordinatorResult.SlotChanged, coordinator.finalizePending(first))
        assertEquals(second, coordinator.current())
    }

    private fun coordinatorWithSummaryHandler() = UndoCoordinator(
        provider,
        handlers = listOf(
            UndoActionHandler(UndoActionType.SUMMARY_EDITED, undo = { db, payload ->
                payload as SummaryEditedUndoPayload
                val current = db.clipDao().getAllClips().single { it.id == payload.clipId }
                db.clipDao().updateClip(current.copy(summary = payload.previousSummary))
            }),
        ),
        now = { "fixed" },
    )

    private suspend fun onlyClip(): ClipEntity = database.clipDao().getAllClips().single()

    private fun clip(summary: String, likeCount: Long? = null) = ClipEntity(
        id = CLIP_ID,
        xPostId = "post",
        authorName = "author",
        authorUsername = "user",
        text = "text",
        postUrl = "https://example.invalid",
        xCreatedAt = "x-created",
        savedAt = "saved",
        syncedAt = "synced",
        summary = summary,
        likeCount = likeCount,
    )

    private class TestDatabaseProvider(database: LikeListDatabase) : UndoDatabaseProvider {
        override val database = MutableStateFlow<LikeListDatabase?>(database)

        override suspend fun <T> withDatabase(block: suspend (LikeListDatabase) -> T): T =
            block(checkNotNull(database.value))
    }

    companion object {
        const val CLIP_ID = 42L
    }
}
