package com.lyco256.llm.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Access to the currently selected durable post database. */
interface UndoDatabaseProvider {
    val database: Flow<LikeListDatabase?>

    suspend fun <T> withDatabase(block: suspend (LikeListDatabase) -> T): T
}

/**
 * Implements one undo action. Implementations must only reverse fields or relations represented by [payload].
 * [finalize] may release action-owned resources and must be idempotent and limited to resources in [payload].
 */
class UndoActionHandler(
    val actionType: UndoActionType,
    val undo: suspend (LikeListDatabase, UndoPayload) -> Unit,
    val finalize: suspend (LikeListDatabase, UndoPayload) -> Unit = { _, _ -> },
    val afterUndo: suspend (UndoPayload) -> Unit = {},
    val afterFinalize: suspend (UndoPayload) -> Unit = {},
)

sealed interface UndoCoordinatorResult {
    data object Success : UndoCoordinatorResult
    data object NoPendingUndo : UndoCoordinatorResult
    /** The UI action referred to a notification that has already been replaced or invalidated. */
    data object SlotChanged : UndoCoordinatorResult
    data class DecodeError(val reason: UndoPayloadDecodeResult.Error.Reason) : UndoCoordinatorResult
    data class ActionTypeMismatch(val stored: String, val decoded: UndoActionType) : UndoCoordinatorResult
    data class UnsupportedAction(val actionType: UndoActionType) : UndoCoordinatorResult
    data class Failure(val cause: Throwable) : UndoCoordinatorResult
}

class UndoCoordinatorException(val result: UndoCoordinatorResult) : IllegalStateException(
    "Undo slot could not be finalized: $result",
)

data class UndoDatabaseEdit<T>(
    val result: T,
    val payload: UndoPayload?,
)

/** Coordinates the single durable undo slot. Internal/background updates do not call this class. */
class UndoCoordinator(
    private val databaseProvider: UndoDatabaseProvider,
    handlers: List<UndoActionHandler> = emptyList(),
    private val now: () -> String = { Instant.now().toString() },
) {
    private val mutex = Mutex()
    private var identityClockBase: String? = null
    private var identityClockSequence: Long = 0L
    private var mostRecentSlotCreatedAt: String? = null
    private val handlersByType = handlers.associateBy(UndoActionHandler::actionType).also {
        require(it.size == handlers.size) { "Only one UndoActionHandler may be registered for each action type" }
    }

    val pendingUndo: Flow<UndoEntity?> = databaseProvider.database.flatMapLatest { database ->
        database?.undoDao()?.observeSlot() ?: flowOf(null)
    }

    suspend fun current(): UndoEntity? = databaseProvider.withDatabase { it.undoDao().getSlot() }

    /** Invalidates the previous user edit. Call this when an explicit user edit starts. */
    suspend fun invalidateForUserEdit(): UndoCoordinatorResult = mutex.withLock { finalizeLocked() }

    /** Dismisses the current undo and runs its optional resource cleanup. */
    suspend fun finalizePending(): UndoCoordinatorResult = mutex.withLock { finalizeLocked() }

    /** Finalizes [expectedSlot] only if it is still the current durable slot. */
    suspend fun finalizePending(expectedSlot: UndoEntity): UndoCoordinatorResult = mutex.withLock {
        finalizeLocked(expectedSlot)
    }

    /**
     * Replaces the previous slot after finalizing it. A failed replacement never restores the old slot.
     * DB-only edits should normally use [commitDatabaseEdit] instead.
     */
    suspend fun replacePending(payload: UndoPayload, message: String): UndoCoordinatorResult = mutex.withLock {
        val finalized = finalizeLocked()
        if (finalized != UndoCoordinatorResult.Success && finalized != UndoCoordinatorResult.NoPendingUndo) {
            return@withLock finalized
        }
        try {
            databaseProvider.withDatabase { database ->
                database.undoDao().replaceSlot(payload.toEntity(message))
            }
            UndoCoordinatorResult.Success
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            UndoCoordinatorResult.Failure(error)
        }
    }

    /**
     * Invalidates the old slot first, then commits the edit and its new slot in one Room transaction.
     * If [edit] fails, neither the edit nor the new slot is committed and the old slot stays invalidated.
     */
    suspend fun <T> commitDatabaseEdit(
        payload: UndoPayload,
        message: String,
        edit: suspend (LikeListDatabase) -> T,
    ): T = mutex.withLock {
        val finalized = finalizeLocked()
        if (finalized != UndoCoordinatorResult.Success && finalized != UndoCoordinatorResult.NoPendingUndo) {
            throw UndoCoordinatorException(finalized)
        }
        databaseProvider.withDatabase { database ->
            database.withTransaction {
                val result = edit(database)
                database.undoDao().replaceSlot(payload.toEntity(message))
                result
            }
        }
    }

    /**
     * Variant for edits whose minimal undo payload must be derived from rows read in the edit transaction.
     */
    suspend fun <T> commitComputedDatabaseEdit(
        message: String,
        edit: suspend (LikeListDatabase) -> UndoDatabaseEdit<T>,
    ): T = mutex.withLock {
        val finalized = finalizeLocked()
        if (finalized != UndoCoordinatorResult.Success && finalized != UndoCoordinatorResult.NoPendingUndo) {
            throw UndoCoordinatorException(finalized)
        }
        databaseProvider.withDatabase { database ->
            database.withTransaction {
                val committed = edit(database)
                committed.payload?.let { database.undoDao().replaceSlot(it.toEntity(message)) }
                committed.result
            }
        }
    }

    /** Executes the decoded inverse operation and clears its slot atomically for DB-only handlers. */
    suspend fun undo(): UndoCoordinatorResult = undoMatching(expectedSlot = null)

    /** Undoes [expectedSlot] only if it is still the current durable slot. */
    suspend fun undo(expectedSlot: UndoEntity): UndoCoordinatorResult = undoMatching(expectedSlot)

    private suspend fun undoMatching(expectedSlot: UndoEntity?): UndoCoordinatorResult = mutex.withLock {
        try {
            databaseProvider.withDatabase { database ->
                val slot = database.undoDao().getSlot() ?: return@withDatabase UndoCoordinatorResult.NoPendingUndo
                mostRecentSlotCreatedAt = slot.createdAt
                if (expectedSlot != null && slot != expectedSlot) {
                    return@withDatabase UndoCoordinatorResult.SlotChanged
                }
                val payload = when (val decoded = decode(slot)) {
                    is DecodedSlot.Error -> return@withDatabase decoded.result
                    is DecodedSlot.Success -> decoded.payload
                }
                val handler = handlersByType[payload.actionType]
                    ?: return@withDatabase UndoCoordinatorResult.UnsupportedAction(payload.actionType)
                try {
                    database.withTransaction {
                        handler.undo(database, payload)
                        database.undoDao().deleteSlot()
                    }
                    handler.afterUndo(payload)
                    UndoCoordinatorResult.Success
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    UndoCoordinatorResult.Failure(error)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            UndoCoordinatorResult.Failure(error)
        }
    }

    private suspend fun finalizeLocked(expectedSlot: UndoEntity? = null): UndoCoordinatorResult = try {
        databaseProvider.withDatabase { database ->
            val slot = database.undoDao().getSlot() ?: return@withDatabase UndoCoordinatorResult.NoPendingUndo
            mostRecentSlotCreatedAt = slot.createdAt
            if (expectedSlot != null && slot != expectedSlot) {
                return@withDatabase UndoCoordinatorResult.SlotChanged
            }
            val payload = when (val decoded = decode(slot)) {
                is DecodedSlot.Error -> return@withDatabase decoded.result
                is DecodedSlot.Success -> decoded.payload
            }
            try {
                database.withTransaction {
                    handlersByType[payload.actionType]?.finalize?.invoke(database, payload)
                    database.undoDao().deleteSlot()
                }
                handlersByType[payload.actionType]?.afterFinalize?.invoke(payload)
                UndoCoordinatorResult.Success
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                UndoCoordinatorResult.Failure(error)
            }
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        UndoCoordinatorResult.Failure(error)
    }

    private fun UndoPayload.toEntity(message: String) = UndoEntity(
        actionType = actionType.storageValue,
        payloadJson = UndoPayloadCodec.encode(this),
        message = message,
        createdAt = nextSlotCreatedAt(),
    )

    /** Keeps the persisted timestamp ISO-shaped while making equal clock readings distinct slot identities. */
    private fun nextSlotCreatedAt(): String {
        val base = now()
        if (base == identityClockBase) {
            identityClockSequence++
        } else {
            identityClockBase = base
            identityClockSequence = 0L
        }
        var candidate = sequencedTimestamp(base, identityClockSequence)
        while (candidate == mostRecentSlotCreatedAt) {
            identityClockSequence++
            candidate = sequencedTimestamp(base, identityClockSequence)
        }
        mostRecentSlotCreatedAt = candidate
        return candidate
    }

    private fun sequencedTimestamp(base: String, sequence: Long): String {
        if (sequence == 0L) return base
        return runCatching { Instant.parse(base).plusNanos(sequence).toString() }
            .getOrElse { "$base#$sequence" }
    }

    private fun decode(slot: UndoEntity): DecodedSlot = when (val decoded = UndoPayloadCodec.decode(slot.payloadJson)) {
        is UndoPayloadDecodeResult.Error -> DecodedSlot.Error(UndoCoordinatorResult.DecodeError(decoded.reason))
        is UndoPayloadDecodeResult.Success -> {
            if (slot.actionType != decoded.payload.actionType.storageValue) {
                DecodedSlot.Error(
                    UndoCoordinatorResult.ActionTypeMismatch(slot.actionType, decoded.payload.actionType),
                )
            } else {
                DecodedSlot.Success(decoded.payload)
            }
        }
    }

    private sealed interface DecodedSlot {
        data class Success(val payload: UndoPayload) : DecodedSlot
        data class Error(val result: UndoCoordinatorResult) : DecodedSlot
    }
}
