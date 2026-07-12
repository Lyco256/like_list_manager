package com.lyco256.llm

import android.os.Trace
import com.lyco256.llm.data.MediaGridClipSource
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagNodeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.time.Instant
import java.time.ZoneId

/** Pure, file-free metadata preparation for the classified media grid. */
internal class MediaGridMetadataCache(private val maxEntries: Int = 3) {
    private val entries = LinkedHashMap<MediaGridCacheKey, ClassifiedMediaGridState>(maxEntries, .75f, true)
    @Synchronized fun get(key: MediaGridCacheKey) = entries[key]
    @Synchronized fun put(key: MediaGridCacheKey, value: ClassifiedMediaGridState) {
        entries[key] = value
        while (entries.size > maxEntries) entries.remove(entries.entries.first().key)
    }
}

internal data class MediaGridCacheKey(
    val sourceRevision: Int,
    val hierarchyRevision: Int,
    val filter: TweetFilterState,
    val sort: ClassifiedSortState,
)

internal suspend fun prepareMediaGridMetadata(
    source: List<MediaGridClipSource>,
    hierarchy: com.lyco256.llm.data.TagHierarchy,
    filters: TweetFilterState,
    sort: ClassifiedSortState,
    cache: MediaGridMetadataCache,
    key: MediaGridCacheKey,
): ClassifiedMediaGridState = withContext(Dispatchers.Default) {
    cache.get(key)?.let { return@withContext it }
    Trace.beginSection("MediaGridMetadataPrepare")
    try {
        val filtered = ArrayList<MediaGridClipSource>(source.size)
        Trace.beginSection("MediaGridFilter")
        val preparedFilter = prepareMediaGridFilter(hierarchy, filters)
        source.forEach { clip -> if (matchesMediaGridFilter(clip, preparedFilter)) filtered += clip }
        Trace.endSection()
        val ordered = if (sort == ClassifiedSortState()) filtered else {
            Trace.beginSection("MediaGridSort")
            sortMediaGridClips(filtered, hierarchy, filters, sort)
                .also { Trace.endSection() }
        }
        Trace.beginSection("MediaGridEntryBuild")
        val result = ClassifiedMediaGridState(
            entries = buildMediaGridEntries(ordered),
            tagIdsByClip = filtered.associate { it.clip.id to it.tagIds.toSet() },
            matchingClipCount = filtered.size,
            matchingMediaCount = ordered.sumOf { it.assets.count { a -> a.assetType == "photo" || a.assetType == "video_thumbnail" } },
            isEmptyByFilter = filtered.isEmpty(),
            hasMatchingClipButNoMedia = filtered.isNotEmpty() && ordered.none { it.assets.any { a -> a.assetType == "photo" || a.assetType == "video_thumbnail" } },
        )
        Trace.endSection()
        cache.put(key, result)
        result
    } finally { Trace.endSection() }
}

private data class PreparedMediaGridFilter(
    val filters: TweetFilterState,
    val query: String,
    val regex: Regex?,
    val included: List<Set<Long>>,
    val required: List<Set<Long>>,
    val excluded: List<Set<Long>>,
)

private fun prepareMediaGridFilter(
    hierarchy: com.lyco256.llm.data.TagHierarchy,
    filters: TweetFilterState,
): PreparedMediaGridFilter {
    val targetSets = filters.tagFilters.mapValues { (ref, _) ->
        if (ref.type == TagNodeType.TAG) setOf(ref.id) else hierarchy.descendantTagIdsByGroup[ref.id].orEmpty()
    }
    return PreparedMediaGridFilter(
        filters = filters,
        query = filters.query.trim(),
        regex = if (filters.searchMode == SearchMode.Regex && filters.query.isNotBlank()) runCatching { Regex(filters.query, RegexOption.IGNORE_CASE) }.getOrNull() else null,
        included = targetSets.filterKeys { filters.tagFilters[it] == TagFilterState.INCLUDED }.values.toList(),
        required = targetSets.filterKeys { filters.tagFilters[it] == TagFilterState.REQUIRED }.values.toList(),
        excluded = targetSets.filterKeys { filters.tagFilters[it] == TagFilterState.EXCLUDED }.values.toList(),
    )
}

private fun matchesMediaGridFilter(clip: MediaGridClipSource, prepared: PreparedMediaGridFilter): Boolean {
    val filters = prepared
    val options = filters.filters
    if (options.taggedOnly && clip.tagIds.isEmpty()) return false
    val authorKey = TweetAuthorKey(clip.clip.authorId?.takeIf { it.isNotBlank() }, clip.clip.authorUsername.lowercase())
    if (options.selectedAuthors.isNotEmpty() && authorKey !in options.selectedAuthors) return false
    if (options.startDate != null || options.endDate != null) {
        val date = runCatching { Instant.parse(clip.clip.xCreatedAt).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: return false
        if (options.startDate != null && date.isBefore(options.startDate)) return false
        if (options.endDate != null && date.isAfter(options.endDate)) return false
    }
    if (clip.tagIds.isEmpty() && filters.included.isNotEmpty()) return false
    fun matches(target: Set<Long>): Boolean {
        clip.tagIds.forEach { if (it in target) return true }
        return false
    }
    if (filters.excluded.any(::matches) || filters.required.any { !matches(it) }) return false
    if (filters.included.isNotEmpty() && filters.included.none(::matches)) return false
    if (filters.query.isNotBlank()) {
        fun textMatch(value: String): Boolean = when (options.searchMode) {
            SearchMode.Literal -> value.contains(filters.query, ignoreCase = true)
            SearchMode.Regex -> filters.regex?.containsMatchIn(value) == true
        }
        var textMatches = false
        if (SearchTarget.Text in options.searchTargets) textMatches = textMatches || textMatch(clip.clip.text)
        if (SearchTarget.Summary in options.searchTargets) textMatches = textMatches || textMatch(clip.clip.summary)
        if (SearchTarget.OcrText in options.searchTargets) textMatches = textMatches || textMatch(clip.clip.ocrText)
        if (SearchTarget.AuthorName in options.searchTargets) textMatches = textMatches || textMatch(clip.clip.authorName)
        if (SearchTarget.Username in options.searchTargets) textMatches = textMatches || textMatch(clip.clip.authorUsername)
        if (!textMatches) return false
    }
    return true
}

private data class MediaGridSortKey(
    val sourceIndex: Int,
    val clipId: Long,
    val postTime: Long,
    val likeCount: Long,
    val authorKey: String,
    val authorNameKey: String,
    val authorCount: Int,
    val tagOrder: Int,
)

private data class PreparedMediaGridClip(val source: MediaGridClipSource, val key: MediaGridSortKey)

private fun sortMediaGridClips(
    clips: List<MediaGridClipSource>,
    hierarchy: com.lyco256.llm.data.TagHierarchy,
    filters: TweetFilterState,
    sort: ClassifiedSortState,
): List<MediaGridClipSource> {
    val counts = HashMap<String, Int>()
    clips.forEach { clip -> counts[clip.clip.authorId.orEmpty() + "\u0000" + clip.clip.authorUsername.lowercase()] = (counts[clip.clip.authorId.orEmpty() + "\u0000" + clip.clip.authorUsername.lowercase()] ?: 0) + 1 }
    val tagOrder = mediaGridTagDisplayOrderIndex(hierarchy)
    val candidates = mediaGridTagCandidateIds(filters, hierarchy)
    val prepared = ArrayList<PreparedMediaGridClip>(clips.size)
    clips.forEachIndexed { index, clip ->
        var representative = Int.MAX_VALUE
        clip.tagIds.forEach { id -> if (candidates.isEmpty() || id in candidates) representative = minOf(representative, tagOrder[id] ?: Int.MAX_VALUE) }
        val authorName = clip.clip.authorUsername.lowercase()
        val authorKey = clip.clip.authorId.orEmpty() + "\u0000" + authorName
        prepared += PreparedMediaGridClip(
            clip,
            MediaGridSortKey(index, clip.clip.id, runCatching { Instant.parse(clip.clip.xCreatedAt).toEpochMilli() }.getOrDefault(Long.MIN_VALUE), clip.clip.likeCount ?: Long.MIN_VALUE, authorKey, authorName, counts[authorKey] ?: 0, representative),
        )
    }
    prepared.sortWith(Comparator { left, right ->
        fun direction(value: Int, descending: Boolean) = if (descending) -value else value
        val tag = if (sort.tagEnabled) direction(left.key.tagOrder.compareTo(right.key.tagOrder), sort.tagDescending) else 0
        val user = if (sort.userEnabled) direction(left.key.authorCount.compareTo(right.key.authorCount), sort.userDescending).takeIf { it != 0 }
            ?: left.key.authorNameKey.compareTo(right.key.authorNameKey) else 0
        val primary = when {
            sort.tagEnabled && sort.userEnabled && sort.priority == ClassifiedSortPriority.TagFirst -> tag.takeIf { it != 0 } ?: user
            sort.tagEnabled && sort.userEnabled -> user.takeIf { it != 0 } ?: tag
            sort.tagEnabled -> tag
            sort.userEnabled -> user
            else -> 0
        }
        if (primary != 0) return@Comparator primary
        val base = when (sort.baseOrder) {
            ClassifiedSortBase.Default -> 0
            ClassifiedSortBase.LikeCount -> direction(left.key.likeCount.compareTo(right.key.likeCount), sort.likeCountDescending)
            ClassifiedSortBase.PostTime -> direction(left.key.postTime.compareTo(right.key.postTime), sort.postTimeDescending)
        }
        if (base != 0) base else left.key.sourceIndex.compareTo(right.key.sourceIndex).takeIf { it != 0 } ?: left.key.clipId.compareTo(right.key.clipId)
    })
    return prepared.mapTo(ArrayList(prepared.size)) { it.source }
}

private fun mediaGridTagDisplayOrderIndex(hierarchy: com.lyco256.llm.data.TagHierarchy): Map<Long, Int> {
    val result = HashMap<Long, Int>()
    var index = 0
    fun visit(parent: Long?) {
        hierarchy.children(parent).forEach { node ->
            if (node is com.lyco256.llm.data.TagGroupNode) visit(node.id)
            else result[node.id] = index++
        }
    }
    visit(null)
    return result
}

private fun mediaGridTagCandidateIds(
    filters: TweetFilterState,
    hierarchy: com.lyco256.llm.data.TagHierarchy,
): Set<Long> = buildSet {
    filters.tagFilters.forEach { (ref, state) ->
        if (state != TagFilterState.INCLUDED && state != TagFilterState.REQUIRED) return@forEach
        when (ref.type) {
            TagNodeType.TAG -> add(ref.id)
            TagNodeType.GROUP -> addAll(hierarchy.descendantTagIdsByGroup[ref.id].orEmpty())
        }
    }
}
