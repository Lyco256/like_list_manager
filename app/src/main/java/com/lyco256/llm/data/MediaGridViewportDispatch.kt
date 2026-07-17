package com.lyco256.llm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class MediaGridViewportItem(
    val assetId: Long,
    val sourceIndex: Int,
    val centerDistance: Int,
)

data class MediaGridViewportSnapshot(
    val sourceRevision: Long,
    val columnCount: Int,
    val items: List<MediaGridViewportItem>,
)

internal fun mediaGridViewportUiIndices(first: Int, last: Int, columns: Int, sourceSize: Int): Set<Int> {
    if (sourceSize <= 0 || last < first) return emptySet()
    val margin = columns.coerceAtLeast(1)
    return (first - margin..last + margin).filterTo(LinkedHashSet()) { it in 0 until sourceSize }
}

internal class MediaGridViewportRevisionGate {
    private var sourceRevision: Long? = null
    private var lastApplied: MediaGridViewportSnapshot? = null

    fun setSourceRevision(revision: Long) {
        if (sourceRevision != revision) {
            sourceRevision = revision
            lastApplied = null
        }
    }

    fun shouldApply(snapshot: MediaGridViewportSnapshot): Boolean {
        if (snapshot.sourceRevision != sourceRevision || snapshot == lastApplied) return false
        lastApplied = snapshot
        return true
    }
}

/** Delivers only the newest unprocessed value through one serial worker. */
internal class LatestValueDispatcher<T>(
    scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val process: suspend (T) -> Unit,
) {
    private val gate = Any()
    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<T?>(null)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val worker: Job = scope.launch(workerDispatcher) {
        for (ignored in signal) {
            val value = latest.getAndSet(null) ?: continue
            try {
                process(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    fun dispatch(value: T): Boolean = synchronized(gate) {
        if (closed.get()) return false
        latest.set(value)
        signal.trySend(Unit).isSuccess
    }

    fun close() {
        synchronized(gate) {
            if (!closed.compareAndSet(false, true)) return
            latest.set(null)
            signal.close()
            worker.cancel()
        }
    }
}
