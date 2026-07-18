package com.lyco256.llm.data

data class MediaGridViewportItem(
    val assetId: Long,
    val sourceIndex: Int,
    val centerDistance: Int,
)

data class MediaGridViewportSnapshot(
    val sourceRevision: Long,
    val columnCount: Int,
    val items: List<MediaGridViewportItem>,
) {
    /** Pixel offsets are intentionally excluded: only the visible structure is a new viewport. */
    fun sameStructureAs(other: MediaGridViewportSnapshot): Boolean {
        if (sourceRevision != other.sourceRevision || columnCount != other.columnCount || items.size != other.items.size) return false
        for (index in items.indices) {
            val current = items[index]
            val next = other.items[index]
            if (current.assetId != next.assetId || current.sourceIndex != next.sourceIndex) return false
        }
        return true
    }
}

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
        if (snapshot.sourceRevision != sourceRevision || lastApplied?.sameStructureAs(snapshot) == true) return false
        lastApplied = snapshot
        return true
    }
}
