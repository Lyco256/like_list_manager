package com.lyco256.llm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ImageDuplicateSearchProgress(
    val processedAssetCount: Int,
    val totalAssetCount: Int,
)

data class ImageDuplicateSearchResult(
    val orderedAssetIds: List<Long>,
    val groupCount: Int,
    val sourceRevision: Long,
)

object ImageDuplicateSearchTuning {
    const val NEIGHBOR_COUNT = 32
    const val MIN_EDGE_SIMILARITY = 0.55f
    const val STRONG_EDGE_SIMILARITY = 0.72f
}

/** The duplicate search reads one immutable image-embedding snapshot and never mutates storage. */
interface ImageDuplicateSearchEngine {
    suspend fun search(onProgress: (ImageDuplicateSearchProgress) -> Unit = {}): ImageDuplicateSearchResult
}

internal fun interface ImageDuplicateSearchDataSource {
    suspend fun getImageSnapshot(): SearchDataSnapshot<ImageEmbeddingDocument>
}

class LocalImageDuplicateSearchEngine internal constructor(
    private val data: ImageDuplicateSearchDataSource,
    private val annFactory: SearchAnnFactory,
) : ImageDuplicateSearchEngine {
    constructor(storage: DerivedSearchStorage) : this(
        data = ImageDuplicateSearchDataSource {
            storage.getImageSnapshot()
                ?: error("Image embedding snapshot unexpectedly returned null")
        },
        annFactory = NativeSearchAnnFactory,
    )

    override suspend fun search(
        onProgress: (ImageDuplicateSearchProgress) -> Unit,
    ): ImageDuplicateSearchResult = withContext(Dispatchers.Default) {
        val snapshot = data.getImageSnapshot()
        coroutineContext.ensureActive()
        val documents = snapshot.documents.sortedBy(ImageEmbeddingDocument::assetId)
        val total = documents.size
        onProgress(ImageDuplicateSearchProgress(0, total))

        if (total <= 1) {
            onProgress(ImageDuplicateSearchProgress(total, total))
            return@withContext ImageDuplicateSearchResult(emptyList(), 0, snapshot.revision)
        }

        val assetIds = LongArray(total)
        val entries = ArrayList<LocalAnnEntry>(total)
        documents.forEachIndexed { index, document ->
            coroutineContext.ensureActive()
            check(document.assetId > 0L) { "Image embedding asset ID must be positive" }
            check(index == 0 || documents[index - 1].assetId != document.assetId) {
                "Duplicate image asset ID in snapshot: ${document.assetId}"
            }
            assetIds[index] = document.assetId
            entries += LocalAnnEntry(document.assetId, document.embedding)
        }

        var ann: SearchAnn? = null
        try {
            val currentAnn = annFactory.build(dimension = ImageEmbeddingBlobCodec.DIMENSION, entries = entries)
            ann = currentAnn
            val maxNeighbors = ImageDuplicateSearchTuning.NEIGHBOR_COUNT
            val neighborSlots = Math.multiplyExact(total, maxNeighbors)
            val neighborIndices = IntArray(neighborSlots)
            val neighborScores = FloatArray(neighborSlots)
            val neighborCounts = IntArray(total)
            val seenCandidateStamp = IntArray(total)
            val candidateCount = minOf(total, maxNeighbors + 1)

            for (sourceIndex in documents.indices) {
                coroutineContext.ensureActive()
                val hits = currentAnn.search(documents[sourceIndex].embedding, candidateCount)
                var count = 0
                val stamp = sourceIndex + 1
                for (hit in hits) {
                    coroutineContext.ensureActive()
                    check(hit.cosineSimilarity.isFinite()) {
                        "Image duplicate ANN score is not finite"
                    }
                    val candidateIndex = assetIds.binarySearch(hit.key)
                    check(candidateIndex >= 0) {
                        "Image duplicate ANN returned an unknown asset ID: ${hit.key}"
                    }
                    if (candidateIndex == sourceIndex || seenCandidateStamp[candidateIndex] == stamp) continue
                    seenCandidateStamp[candidateIndex] = stamp
                    if (count >= maxNeighbors) continue
                    val slot = sourceIndex * maxNeighbors + count
                    neighborIndices[slot] = candidateIndex
                    neighborScores[slot] = hit.cosineSimilarity
                    count++
                }
                neighborCounts[sourceIndex] = count
                onProgress(ImageDuplicateSearchProgress(sourceIndex + 1, total))
            }

            val edgeKeys = LongArray(neighborSlots)
            val edgeScores = FloatArray(neighborSlots)
            var edgeCount = 0
            for (sourceIndex in documents.indices) {
                coroutineContext.ensureActive()
                val sourceOffset = sourceIndex * maxNeighbors
                for (slotIndex in 0 until neighborCounts[sourceIndex]) {
                    val slot = sourceOffset + slotIndex
                    val candidateIndex = neighborIndices[slot]
                    val score = neighborScores[slot]
                    val mutual = containsNeighbor(
                        sourceIndex = candidateIndex,
                        targetIndex = sourceIndex,
                        neighborIndices = neighborIndices,
                        neighborCounts = neighborCounts,
                        maxNeighbors = maxNeighbors,
                    )
                    if (score < ImageDuplicateSearchTuning.STRONG_EDGE_SIMILARITY &&
                        (score < ImageDuplicateSearchTuning.MIN_EDGE_SIMILARITY || !mutual)
                    ) continue
                    val left = minOf(sourceIndex, candidateIndex)
                    val right = maxOf(sourceIndex, candidateIndex)
                    edgeKeys[edgeCount] = pairKey(left, right)
                    edgeScores[edgeCount] = score
                    edgeCount++
                }
            }
            sortEdges(edgeKeys, edgeScores, edgeCount)
            val uniqueEdgeCount = deduplicateEdges(edgeKeys, edgeScores, edgeCount)
            if (uniqueEdgeCount == 0) {
                return@withContext ImageDuplicateSearchResult(emptyList(), 0, snapshot.revision)
            }

            val unionFind = UnionFind(total)
            for (edgeIndex in 0 until uniqueEdgeCount) {
                coroutineContext.ensureActive()
                val key = edgeKeys[edgeIndex]
                unionFind.union(pairLeft(key), pairRight(key))
            }

            val rootByIndex = IntArray(total)
            val componentSizes = IntArray(total)
            for (index in 0 until total) {
                coroutineContext.ensureActive()
                val root = unionFind.find(index)
                rootByIndex[index] = root
                componentSizes[root]++
            }

            val groupRoots = ArrayList<Int>()
            val maxEdgeScoreByRoot = FloatArray(total) { Float.NEGATIVE_INFINITY }
            val bestStartEdgeByRoot = IntArray(total) { -1 }
            for (edgeIndex in 0 until uniqueEdgeCount) {
                coroutineContext.ensureActive()
                val key = edgeKeys[edgeIndex]
                val root = rootByIndex[pairLeft(key)]
                maxEdgeScoreByRoot[root] = maxOf(maxEdgeScoreByRoot[root], edgeScores[edgeIndex])
                val currentStartEdge = bestStartEdgeByRoot[root]
                if (currentStartEdge < 0 ||
                    isBetterStartEdge(edgeIndex, currentStartEdge, edgeKeys, edgeScores, assetIds)
                ) {
                    bestStartEdgeByRoot[root] = edgeIndex
                }
            }
            for (root in componentSizes.indices) {
                if (componentSizes[root] >= 2) groupRoots += root
            }

            val adjacencyDegree = IntArray(total)
            for (edgeIndex in 0 until uniqueEdgeCount) {
                coroutineContext.ensureActive()
                val key = edgeKeys[edgeIndex]
                adjacencyDegree[pairLeft(key)]++
                adjacencyDegree[pairRight(key)]++
            }
            val adjacencyOffsets = IntArray(total + 1)
            for (index in adjacencyDegree.indices) {
                coroutineContext.ensureActive()
                adjacencyOffsets[index + 1] = adjacencyOffsets[index] + adjacencyDegree[index]
            }
            val adjacencyEdgeIndices = IntArray(uniqueEdgeCount * 2)
            val adjacencyCursor = adjacencyOffsets.copyOf()
            for (edgeIndex in 0 until uniqueEdgeCount) {
                coroutineContext.ensureActive()
                val key = edgeKeys[edgeIndex]
                val left = pairLeft(key)
                val right = pairRight(key)
                adjacencyEdgeIndices[adjacencyCursor[left]++] = edgeIndex
                adjacencyEdgeIndices[adjacencyCursor[right]++] = edgeIndex
            }

            val groupStart = IntArray(total) { -1 }
            var groupedAssetCount = 0
            groupRoots.forEach { root ->
                groupStart[root] = groupedAssetCount
                groupedAssetCount += componentSizes[root]
            }
            val members = IntArray(groupedAssetCount)
            val nextMember = groupStart.copyOf()
            for (index in 0 until total) {
                coroutineContext.ensureActive()
                val root = rootByIndex[index]
                if (groupStart[root] >= 0) members[nextMember[root]++] = index
            }

            groupRoots.sortWith(
                compareByDescending<Int> { maxEdgeScoreByRoot[it] }
                    .thenBy { assetIds[members[groupStart[it]]] },
            )
            val orderedAssetIds = ArrayList<Long>(groupedAssetCount)
            val emitted = BooleanArray(total)
            groupRoots.forEach { root ->
                coroutineContext.ensureActive()
                appendOrderedGroup(
                    root = root,
                    groupStart = groupStart,
                    componentSizes = componentSizes,
                    members = members,
                    assetIds = assetIds,
                    edgeKeys = edgeKeys,
                    edgeScores = edgeScores,
                    bestStartEdge = bestStartEdgeByRoot[root],
                    adjacencyOffsets = adjacencyOffsets,
                    adjacencyEdgeIndices = adjacencyEdgeIndices,
                    rootByIndex = rootByIndex,
                    emitted = emitted,
                    output = orderedAssetIds,
                )
            }
            check(emitted.count { it } == orderedAssetIds.size) {
                "Image duplicate result contains an unexpected asset count"
            }
            check(orderedAssetIds.size == orderedAssetIds.distinct().size) {
                "Image duplicate result contains duplicate asset IDs"
            }
            ImageDuplicateSearchResult(orderedAssetIds, groupRoots.size, snapshot.revision)
        } finally {
            ann?.close()
        }
    }

    private suspend fun appendOrderedGroup(
        root: Int,
        groupStart: IntArray,
        componentSizes: IntArray,
        members: IntArray,
        assetIds: LongArray,
        edgeKeys: LongArray,
        edgeScores: FloatArray,
        bestStartEdge: Int,
        adjacencyOffsets: IntArray,
        adjacencyEdgeIndices: IntArray,
        rootByIndex: IntArray,
        emitted: BooleanArray,
        output: MutableList<Long>,
    ) {
        val start = groupStart[root]
        val end = start + componentSizes[root]
        check(bestStartEdge >= 0) { "Image duplicate component has no starting edge" }

        val startKey = edgeKeys[bestStartEdge]
        val first = pairLeft(startKey)
        val second = pairRight(startKey)
        check(rootByIndex[first] == root && rootByIndex[second] == root) {
            "Image duplicate starting edge is outside its component"
        }
        emitted[first] = true
        emitted[second] = true
        output += assetIds[first]
        output += assetIds[second]
        var placedCount = 2
        val frontierCandidateByEdge = IntArray(adjacencyEdgeIndices.size / 2) { -1 }
        val frontier = EdgeFrontierHeap(edgeKeys, edgeScores, assetIds, frontierCandidateByEdge)

        suspend fun addAdjacentEdges(placed: Int) {
            for (offset in adjacencyOffsets[placed] until adjacencyOffsets[placed + 1]) {
                coroutineContext.ensureActive()
                val edgeIndex = adjacencyEdgeIndices[offset]
                val key = edgeKeys[edgeIndex]
                val other = if (pairLeft(key) == placed) pairRight(key) else pairLeft(key)
                if (rootByIndex[other] != root || emitted[other] || frontierCandidateByEdge[edgeIndex] >= 0) {
                    continue
                }
                frontierCandidateByEdge[edgeIndex] = other
                frontier.add(edgeIndex)
            }
        }

        addAdjacentEdges(first)
        addAdjacentEdges(second)

        while (placedCount < componentSizes[root]) {
            coroutineContext.ensureActive()
            var bestCandidate = -1
            while (bestCandidate < 0) {
                val edgeIndex = frontier.poll() ?: break
                val candidate = frontierCandidateByEdge[edgeIndex]
                if (candidate >= 0 && !emitted[candidate] && rootByIndex[candidate] == root) {
                    bestCandidate = candidate
                }
            }
            if (bestCandidate < 0) {
                for (memberOffset in start until end) {
                    coroutineContext.ensureActive()
                    val member = members[memberOffset]
                    if (!emitted[member]) {
                        emitted[member] = true
                        output += assetIds[member]
                        placedCount++
                    }
                }
                break
            }
            emitted[bestCandidate] = true
            output += assetIds[bestCandidate]
            placedCount++
            addAdjacentEdges(bestCandidate)
        }
    }

    private class EdgeFrontierHeap(
        private val edgeKeys: LongArray,
        private val edgeScores: FloatArray,
        private val assetIds: LongArray,
        private val candidateByEdge: IntArray,
    ) {
        private var values = IntArray(16)
        private var size = 0

        suspend fun add(edgeIndex: Int) {
            coroutineContext.ensureActive()
            if (size == values.size) values = values.copyOf(values.size * 2)
            var index = size
            values[size++] = edgeIndex
            while (index > 0) {
                coroutineContext.ensureActive()
                val parent = (index - 1) ushr 1
                if (!isHigherPriority(values[index], values[parent])) break
                val swap = values[index]
                values[index] = values[parent]
                values[parent] = swap
                index = parent
            }
        }

        suspend fun poll(): Int? {
            if (size == 0) return null
            coroutineContext.ensureActive()
            val result = values[0]
            values[0] = values[--size]
            var index = 0
            while (true) {
                coroutineContext.ensureActive()
                val left = index * 2 + 1
                if (left >= size) break
                val right = left + 1
                var best = left
                if (right < size && isHigherPriority(values[right], values[left])) best = right
                if (!isHigherPriority(values[best], values[index])) break
                val swap = values[index]
                values[index] = values[best]
                values[best] = swap
                index = best
            }
            return result
        }

        private fun isHigherPriority(left: Int, right: Int): Boolean {
            val scoreOrder = edgeScores[left].compareTo(edgeScores[right])
            if (scoreOrder != 0) return scoreOrder > 0
            val leftCandidate = candidateByEdge[left]
            val rightCandidate = candidateByEdge[right]
            val assetOrder = assetIds[leftCandidate].compareTo(assetIds[rightCandidate])
            if (assetOrder != 0) return assetOrder < 0
            return edgeKeys[left] < edgeKeys[right]
        }
    }

    private fun isBetterStartEdge(
        candidateIndex: Int,
        currentIndex: Int,
        edgeKeys: LongArray,
        edgeScores: FloatArray,
        assetIds: LongArray,
    ): Boolean {
        val scoreDelta = edgeScores[candidateIndex].compareTo(edgeScores[currentIndex])
        if (scoreDelta != 0) return scoreDelta > 0
        val candidateKey = edgeKeys[candidateIndex]
        val currentKey = edgeKeys[currentIndex]
        val leftDelta = assetIds[pairLeft(candidateKey)].compareTo(assetIds[pairLeft(currentKey)])
        if (leftDelta != 0) return leftDelta < 0
        return assetIds[pairRight(candidateKey)] < assetIds[pairRight(currentKey)]
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun find(value: Int): Int {
            var current = value
            while (parent[current] != current) current = parent[current]
            var compressed = value
            while (parent[compressed] != compressed) {
                val next = parent[compressed]
                parent[compressed] = current
                compressed = next
            }
            return current
        }

        fun union(left: Int, right: Int) {
            var leftRoot = find(left)
            var rightRoot = find(right)
            if (leftRoot == rightRoot) return
            if (rank[leftRoot] < rank[rightRoot]) {
                val swap = leftRoot
                leftRoot = rightRoot
                rightRoot = swap
            }
            parent[rightRoot] = leftRoot
            if (rank[leftRoot] == rank[rightRoot]) rank[leftRoot]++
        }
    }

    private fun containsNeighbor(
        sourceIndex: Int,
        targetIndex: Int,
        neighborIndices: IntArray,
        neighborCounts: IntArray,
        maxNeighbors: Int,
    ): Boolean {
        val offset = sourceIndex * maxNeighbors
        for (index in 0 until neighborCounts[sourceIndex]) {
            if (neighborIndices[offset + index] == targetIndex) return true
        }
        return false
    }

    private fun pairKey(left: Int, right: Int): Long =
        (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)

    private fun pairLeft(key: Long): Int = (key ushr 32).toInt()

    private fun pairRight(key: Long): Int = key.toInt()

    private suspend fun deduplicateEdges(keys: LongArray, scores: FloatArray, count: Int): Int {
        var uniqueCount = 0
        for (index in 0 until count) {
            coroutineContext.ensureActive()
            if (uniqueCount == 0 || keys[index] != keys[uniqueCount - 1]) {
                keys[uniqueCount] = keys[index]
                scores[uniqueCount] = scores[index]
                uniqueCount++
            } else {
                scores[uniqueCount - 1] = maxOf(scores[uniqueCount - 1], scores[index])
            }
        }
        return uniqueCount
    }

    private suspend fun sortEdges(keys: LongArray, scores: FloatArray, count: Int) {
        if (count < 2) return
        fun swap(left: Int, right: Int) {
            if (left == right) return
            val key = keys[left]
            keys[left] = keys[right]
            keys[right] = key
            val score = scores[left]
            scores[left] = scores[right]
            scores[right] = score
        }
        suspend fun quickSort(lowStart: Int, highStart: Int) {
            var low = lowStart
            var high = highStart
            while (low < high) {
                coroutineContext.ensureActive()
                val pivot = keys[(low + high) ushr 1]
                var left = low
                var right = high
                while (left <= right) {
                    coroutineContext.ensureActive()
                    while (keys[left] < pivot) left++
                    while (keys[right] > pivot) right--
                    if (left <= right) {
                        swap(left, right)
                        left++
                        right--
                    }
                }
                if (right - low < high - left) {
                    if (low < right) quickSort(low, right)
                    low = left
                } else {
                    if (left < high) quickSort(left, high)
                    high = right
                }
            }
        }
        quickSort(0, count - 1)
    }
}
