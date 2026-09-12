package com.lyco256.llm

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
    val sourceRevision: Long,
    val hierarchyRevision: Long,
    val filter: TweetFilterState,
    val sort: ClassifiedSortState,
    val searchIdentity: String = "inactive",
    val searchGeneration: Long = 0L,
)

internal fun effectiveMediaGridSort(sort: ClassifiedSortState): ClassifiedSortState = sort.copy(
    tagDescending = sort.tagDescending.takeIf { sort.tagEnabled } ?: false,
    userDescending = sort.userDescending.takeIf { sort.userEnabled } ?: true,
    priority = if (sort.tagEnabled && sort.userEnabled) sort.priority else ClassifiedSortPriority.TagFirst,
    likeCountDescending = sort.likeCountDescending.takeIf { sort.baseOrder == ClassifiedSortBase.LikeCount } ?: true,
    postTimeDescending = sort.postTimeDescending.takeIf { sort.baseOrder == ClassifiedSortBase.PostTime } ?: true,
)

internal fun effectiveMediaGridFilter(filters: TweetFilterState): TweetFilterState = filters

internal fun mediaGridSortIsEffective(sort: ClassifiedSortState): Boolean =
    sort.baseOrder != ClassifiedSortBase.Default || sort.tagEnabled || sort.userEnabled

internal suspend fun prepareMediaGridMetadata(
    snapshot: com.lyco256.llm.data.MediaGridSourceSnapshot,
    hierarchy: com.lyco256.llm.data.TagHierarchy,
    filters: TweetFilterState,
    sort: ClassifiedSortState,
    searchState: ClassifiedSearchState,
    cache: MediaGridMetadataCache,
    key: MediaGridCacheKey,
): ClassifiedMediaGridState = withContext(Dispatchers.Default) {
    cache.get(key)?.let { return@withContext it }
    val source = snapshot.clips
    val searchOrdered = when {
        !searchState.isActive -> source
        searchState.isLoading || searchState.isFailed -> emptyList()
        searchState.criteria.mode == SearchMode.Smart -> {
            val sourceById = source.associateBy { it.clip.id }
            searchState.rankedClipIds.asSequence().distinct().mapNotNull(sourceById::get).toList()
        }
        else -> source.filter { matchesMediaGridRegex(it, searchState.criteria) }
    }
    val filtered = ArrayList<MediaGridClipSource>(source.size)
    val preparedFilter = prepareMediaGridFilter(hierarchy, filters)
    searchOrdered.forEach { clip -> if (matchesMediaGridFilter(clip, preparedFilter)) filtered += clip }
    val ordered = if (searchState.isActive || !mediaGridSortIsEffective(sort)) filtered else sortMediaGridClips(filtered, hierarchy, filters, sort)
    val built = buildMediaGridResult(ordered)
    val result = ClassifiedMediaGridState(
        dataKey = MediaGridDataKey(
            key.sourceRevision,
            key.hierarchyRevision,
            key.filter,
            key.sort,
            key.searchIdentity,
            key.searchGeneration,
        ),
        sourceRevision = key.sourceRevision,
        sourceClipCount = source.size,
        sourceMediaAssetCount = source.sumOf { it.mediaAssetCount },
        sourceTaggedClipCount = source.count { it.tagIds.isNotEmpty() },
        entries = built.entries,
        tagIdsByClip = filtered.associate { it.clip.id to it.tagIds },
        matchingClipCount = filtered.size,
        matchingMediaCount = built.matchingMediaCount,
        isEmptyByFilter = filtered.isEmpty(),
        hasMatchingClipButNoMedia = filtered.isNotEmpty() && !built.hasMedia,
    )
    cache.put(key, result)
    result
}

private data class PreparedMediaGridFilter(
    val filters: TweetFilterState,
    val included: List<Set<Long>>,
    val required: List<Set<Long>>,
    val excluded: List<Set<Long>>,
    val selectedAuthors: Set<TweetAuthorKey>,
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
        included = targetSets.filterKeys { filters.tagFilters[it] == TagFilterState.INCLUDED }.values.toList(),
        required = targetSets.filterKeys { filters.tagFilters[it] == TagFilterState.REQUIRED }.values.toList(),
        excluded = targetSets.filterKeys { filters.tagFilters[it] == TagFilterState.EXCLUDED }.values.toList(),
        selectedAuthors = filters.selectedAuthors,
    )
}

private fun matchesMediaGridFilter(clip: MediaGridClipSource, prepared: PreparedMediaGridFilter): Boolean {
    val filters = prepared
    val options = filters.filters
    if (options.taggedOnly && clip.tagIds.isEmpty()) return false
    if (filters.selectedAuthors.isNotEmpty() && clip.authorKey !in filters.selectedAuthors) return false
    if (options.startDate != null || options.endDate != null) {
        val day = clip.postLocalEpochDay ?: return false
        if (options.startDate != null && day < options.startDate.toEpochDay()) return false
        if (options.endDate != null && day > options.endDate.toEpochDay()) return false
    }
    if (clip.tagIds.isEmpty() && filters.included.isNotEmpty()) return false
    fun matches(target: Set<Long>): Boolean {
        clip.tagIds.forEach { if (it in target) return true }
        return false
    }
    if (filters.excluded.any(::matches) || filters.required.any { !matches(it) }) return false
    if (filters.included.isNotEmpty() && filters.included.none(::matches)) return false
    return true
}

private fun matchesMediaGridRegex(
    clip: MediaGridClipSource,
    criteria: ClassifiedSearchCriteria,
): Boolean {
    if (criteria.mode != SearchMode.Regex || criteria.normalizedQuery.isBlank()) return false
    val regex = runCatching { Regex(criteria.normalizedQuery, RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
    val values = buildList {
        if (SearchTarget.Text in criteria.regexTargets) add(clip.clip.text)
        if (SearchTarget.Summary in criteria.regexTargets) add(clip.clip.summary)
        if (SearchTarget.OcrText in criteria.regexTargets) add(clip.clip.ocrText)
        if (SearchTarget.AuthorName in criteria.regexTargets) add(clip.clip.authorName)
        if (SearchTarget.Username in criteria.regexTargets) add(clip.clip.authorUsername)
    }
    return values.any(regex::containsMatchIn)
}

private data class MediaGridSortKey(
    val sourceIndex: Int,
    val clipId: Long,
    val postTime: Long,
    val likeCount: Long,
    val authorKey: TweetAuthorKey,
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
    val counts = if (sort.userEnabled) HashMap<TweetAuthorKey, Int>().also { map -> clips.forEach { clip -> map[clip.authorKey] = (map[clip.authorKey] ?: 0) + 1 } } else emptyMap()
    val tagOrder = if (sort.tagEnabled) mediaGridTagDisplayOrderIndex(hierarchy) else emptyMap()
    val candidates = if (sort.tagEnabled) mediaGridTagCandidateIds(filters, hierarchy) else emptySet()
    val prepared = ArrayList<PreparedMediaGridClip>(clips.size)
    clips.forEachIndexed { index, clip ->
        var representative = Int.MAX_VALUE
        clip.tagIds.forEach { id -> if (candidates.isEmpty() || id in candidates) representative = minOf(representative, tagOrder[id] ?: Int.MAX_VALUE) }
        val authorName = clip.authorNameKey
        val authorKey = clip.authorKey
        prepared += PreparedMediaGridClip(
            clip,
            MediaGridSortKey(clip.sourceIndex, clip.clip.id, clip.postTimeMillis ?: Long.MIN_VALUE, clip.clip.likeCount ?: Long.MIN_VALUE, authorKey, authorName, counts[authorKey] ?: 0, representative),
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
