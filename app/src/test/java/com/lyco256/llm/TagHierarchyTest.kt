package com.lyco256.llm

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipTagEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.MediaGridAssetRow
import com.lyco256.llm.data.MediaGridClipSource
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagGroupNode
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagLeafNode
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagWithCount
import com.lyco256.llm.data.TagColorId
import com.lyco256.llm.data.orderNodesAfterMove
import com.lyco256.llm.data.orderNodesAfterMoveAtSlot
import com.lyco256.llm.data.parentGroupIdForMove
import com.lyco256.llm.data.postsBeforeFirstExisting
import com.lyco256.llm.data.normalizedTagColorId
import com.lyco256.llm.data.tagColorSpec
import com.lyco256.llm.data.requireSiblingNameAvailable
import com.lyco256.llm.data.requireValidGroupDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class TagHierarchyTest {
    private val now = "2026-06-15T00:00:00Z"
    private val parent = TagGroupEntity(1, "開発", null, 0, now, now)
    private val child = TagGroupEntity(2, "Android", 1, 0, now, now)
    private val kotlin = TagEntity(10, "Kotlin", parentGroupId = 2, createdAt = now, updatedAt = now)
    private val compose = TagEntity(11, "Compose", parentGroupId = 2, createdAt = now, updatedAt = now)
    private val design = TagEntity(12, "Design", createdAt = now, updatedAt = now)

    @Test
    fun descendantTagsRemainRecursiveAndGroupCountsOnlyDirectChildren() {
        val hierarchy = hierarchy(
            clipTags = listOf(
                ClipTagEntity(100, kotlin.id, now),
                ClipTagEntity(100, compose.id, now),
                ClipTagEntity(101, compose.id, now),
            ),
        )

        assertEquals(setOf(kotlin.id, compose.id), hierarchy.descendantTagIdsByGroup[parent.id])
        assertEquals(1, hierarchy.groupCounts[parent.id])
        assertEquals(2, hierarchy.groupCounts[child.id])
    }

    @Test
    fun likeCountFormattingUsesGroupingAndTruncatedJapaneseTenThousands() {
        assertEquals("852", formatLikeCount(852))
        assertEquals("3,643", formatLikeCount(3_643))
        assertEquals("1.9万", formatLikeCount(19_999))
        assertEquals("10万", formatLikeCount(100_000))
    }

    @Test
    fun tagColorPaletteFallsBackToStandardForUnknownIds() {
        assertEquals(TagColorId.STANDARD.id, normalizedTagColorId(null))
        assertEquals(TagColorId.STANDARD.id, normalizedTagColorId("unknown"))
        assertEquals(TagColorId.STANDARD.id, tagColorSpec("unknown").id)
    }

    @Test
    fun incrementalSyncStopsAtFirstExistingPost() {
        val returnedIds = listOf("new-3", "new-2", "known", "older-known", "oldest")

        assertEquals(
            listOf("new-3", "new-2"),
            postsBeforeFirstExisting(returnedIds, setOf("known", "older-known")) { it },
        )
        assertEquals(returnedIds, postsBeforeFirstExisting(returnedIds, emptySet()) { it })
    }

    @Test
    fun filtersApplyRequiredAndTogetherAndIncludedAsOr() {
        val hierarchy = hierarchy()
        val clip = clip(kotlin, design)
        val requiredKotlin = TagNodeRef(TagNodeType.TAG, kotlin.id)
        val requiredDesign = TagNodeRef(TagNodeType.TAG, design.id)
        val includedCompose = TagNodeRef(TagNodeType.TAG, compose.id)
        val includedKotlin = TagNodeRef(TagNodeType.TAG, kotlin.id)

        assertFalse(
            matchesTagFilters(
                clip,
                hierarchy,
                mapOf(
                    requiredKotlin to TagFilterState.REQUIRED,
                    requiredDesign to TagFilterState.REQUIRED,
                    includedCompose to TagFilterState.INCLUDED,
                ),
            ),
        )
        assertTrue(
            matchesTagFilters(
                clip,
                hierarchy,
                mapOf(
                    requiredKotlin to TagFilterState.REQUIRED,
                    requiredDesign to TagFilterState.REQUIRED,
                    includedCompose to TagFilterState.INCLUDED,
                    includedKotlin to TagFilterState.INCLUDED,
                ),
            ),
        )
    }

    @Test
    fun excludedTagsRemoveClipBeforeRequiredAndIncluded() {
        val hierarchy = hierarchy()
        val clip = clip(kotlin, design)

        assertFalse(
            matchesTagFilters(
                clip,
                hierarchy,
                mapOf(
                    TagNodeRef(TagNodeType.TAG, kotlin.id) to TagFilterState.REQUIRED,
                    TagNodeRef(TagNodeType.TAG, design.id) to TagFilterState.EXCLUDED,
                ),
            ),
        )
    }

    @Test
    fun groupExcludeRemovesClipsWithDescendantTags() {
        val hierarchy = hierarchy()
        val clip = clip(kotlin)

        assertFalse(
            matchesTagFilters(
                clip,
                hierarchy,
                mapOf(TagNodeRef(TagNodeType.GROUP, parent.id) to TagFilterState.EXCLUDED),
            ),
        )
    }

    @Test
    fun taggedOnlyControlsWhetherUntaggedClipsAreSearched() {
        val hierarchy = hierarchy()
        val untagged = clip(id = 101, text = "needle")
        val tagged = clip(kotlin, id = 102, text = "needle")
        val filters = TweetFilterState(query = "needle")

        assertEquals(listOf(tagged), filterClipsForSearch(listOf(untagged, tagged), hierarchy, filters))
        assertEquals(
            listOf(untagged, tagged),
            filterClipsForSearch(listOf(untagged, tagged), hierarchy, filters.copy(taggedOnly = false)),
        )
    }

    @Test
    fun selectedAuthorsMatchAsOr() {
        val hierarchy = hierarchy()
        val first = clip(kotlin, id = 101, authorId = "a1", username = "first")
        val second = clip(kotlin, id = 102, authorId = "a2", username = "second")
        val third = clip(kotlin, id = 103, authorId = "a3", username = "third")
        val filters = TweetFilterState(
            selectedAuthors = setOf(
                TweetAuthorKey("a1", "first"),
                TweetAuthorKey("a3", "third"),
            ),
        )

        assertEquals(listOf(first, third), filterClipsForSearch(listOf(first, second, third), hierarchy, filters))
    }

    @Test
    fun dateRangeIncludesStartAndEndDate() {
        val hierarchy = hierarchy()
        val early = clip(kotlin, id = 101, createdAt = "2026-06-10T00:00:00Z")
        val start = clip(kotlin, id = 102, createdAt = "2026-06-11T00:00:00Z")
        val end = clip(kotlin, id = 103, createdAt = "2026-06-12T12:00:00Z")
        val late = clip(kotlin, id = 104, createdAt = "2026-06-13T00:00:00Z")
        val filters = TweetFilterState(
            startDate = java.time.LocalDate.of(2026, 6, 11),
            endDate = java.time.LocalDate.of(2026, 6, 12),
        )

        assertEquals(listOf(start, end), filterClipsForSearch(listOf(early, start, end, late), hierarchy, filters))
    }

    @Test
    fun invalidRegexSearchReturnsEmptyList() {
        val hierarchy = hierarchy()
        val clip = clip(kotlin)
        val filters = TweetFilterState(query = "[", searchMode = SearchMode.Regex)

        assertEquals(emptyList<ClipWithDetails>(), filterClipsForSearch(listOf(clip), hierarchy, filters))
    }

    @Test
    fun eachTextSearchTargetIsAppliedIndependentlyAndCaseInsensitively() {
        val candidate = clip(
            kotlin,
            text = "Body Needle",
            summary = "Summary Marker",
            authorName = "Display Person",
            username = "Account_Name",
        )
        val cases = listOf(
            SearchTarget.Text to "body needle",
            SearchTarget.Summary to "summary marker",
            SearchTarget.AuthorName to "display person",
            SearchTarget.Username to "account_name",
        )

        cases.forEach { (target, query) ->
            val filters = TweetFilterState(query = query, searchTargets = setOf(target))
            assertEquals(listOf(candidate), filterClipsForSearch(listOf(candidate), hierarchy(), filters))
            assertEquals(
                emptyList<ClipWithDetails>(),
                filterClipsForSearch(listOf(candidate), hierarchy(), filters.copy(searchTargets = SearchTarget.entries.toSet() - target)),
            )
        }
    }

    @Test
    fun regexAndCombinedFiltersRequireEveryFilterDimension() {
        val matching = clip(
            kotlin,
            id = 201,
            text = "Kotlin 2.0 migration",
            authorId = "author-1",
            username = "alice",
            createdAt = "2026-06-12T12:00:00Z",
        )
        val wrongAuthor = clip(
            kotlin,
            id = 202,
            text = "Kotlin 2.0 migration",
            authorId = "author-2",
            username = "bob",
            createdAt = "2026-06-12T12:00:00Z",
        )
        val wrongTag = clip(
            design,
            id = 203,
            text = "Kotlin 2.0 migration",
            authorId = "author-1",
            username = "alice",
            createdAt = "2026-06-12T12:00:00Z",
        )
        val filters = TweetFilterState(
            query = "kotlin\\s+2\\.0",
            searchMode = SearchMode.Regex,
            searchTargets = setOf(SearchTarget.Text),
            startDate = java.time.LocalDate.of(2026, 6, 12),
            endDate = java.time.LocalDate.of(2026, 6, 12),
            selectedAuthors = setOf(TweetAuthorKey("author-1", "alice")),
            tagFilters = mapOf(TagNodeRef(TagNodeType.TAG, kotlin.id) to TagFilterState.REQUIRED),
        )

        assertEquals(
            listOf(matching),
            filterClipsForSearch(listOf(matching, wrongAuthor, wrongTag), hierarchy(), filters),
        )
    }

    @Test
    fun filteringNeverMutatesClipsTagsOrSourceOrder() {
        val first = clip(kotlin, id = 301, text = "needle")
        val second = clip(design, id = 302, text = "other")
        val source = mutableListOf(first, second)
        val before = source.map { it.copy(clip = it.clip.copy(), tags = it.tags.toList(), assets = it.assets.toList()) }

        filterClipsForSearch(source, hierarchy(), TweetFilterState(query = "needle"))

        assertEquals(before, source)
        assertEquals(listOf(301L, 302L), source.map { it.clip.id })
    }

    @Test
    fun filterSummaryShowsDefaultScopeAndNoAdditionalConditions() {
        assertEquals(
            "対象:タグ付きのみ、条件なし",
            filterConditionSummary(TweetFilterState(), hierarchy(), emptyList()),
        )
        assertEquals(
            "対象:全ツイート",
            filterConditionSummary(TweetFilterState(taggedOnly = false), hierarchy(), emptyList()),
        )
    }

    @Test
    fun filterSummaryFormatsCombinedConditionsAsSentence() {
        val author = TweetAuthorOption(TweetAuthorKey("a1", "alice"), "Alice", "alice", 12)
        val filters = TweetFilterState(
            query = "猫",
            searchTargets = setOf(SearchTarget.Text, SearchTarget.Summary),
            startDate = java.time.LocalDate.of(2026, 6, 1),
            endDate = java.time.LocalDate.of(2026, 6, 20),
            selectedAuthors = setOf(author.key),
            tagFilters = mapOf(
                TagNodeRef(TagNodeType.TAG, kotlin.id) to TagFilterState.INCLUDED,
                TagNodeRef(TagNodeType.TAG, compose.id) to TagFilterState.REQUIRED,
                TagNodeRef(TagNodeType.TAG, design.id) to TagFilterState.EXCLUDED,
            ),
        )

        assertEquals(
            "対象:タグ付きのみ、文字列:\"猫\"(対象:本文・概要)、期間:2026/6/1~2026/6/20、ユーザー:@alice、タグ:含む[Kotlin],必須[Compose],排除[Design]",
            filterConditionSummary(filters, hierarchy(), listOf(author)),
        )
    }

    @Test
    fun sortSummaryShowsDefaultAndCombinedOrderingLabels() {
        assertEquals("並び:保存順", sortConditionSummary(ClassifiedSortState()))
        assertEquals(
            "並び:タグ上から → ユーザー件数多い順 → いいね多い順",
            sortConditionSummary(
                ClassifiedSortState(
                    tagEnabled = true,
                    userEnabled = true,
                    priority = ClassifiedSortPriority.TagFirst,
                    baseOrder = ClassifiedSortBase.LikeCount,
                ),
            ),
        )
        assertEquals(
            "並び:ユーザー件数少ない順 → 投稿時間古い順",
            sortConditionSummary(
                ClassifiedSortState(
                    userEnabled = true,
                    userDescending = false,
                    baseOrder = ClassifiedSortBase.PostTime,
                    postTimeDescending = false,
                ),
            ),
        )
    }

    @Test
    fun defaultSortUsesSaveOrderAndIdTiebreaker() {
        val alpha = TagEntity(101, "Alpha", sortOrder = 0, createdAt = now, updatedAt = now)
        val beta = TagEntity(102, "Beta", sortOrder = 1, createdAt = now, updatedAt = now)
        val gamma = TagEntity(103, "Gamma", sortOrder = 2, createdAt = now, updatedAt = now)
        val hierarchy = TagHierarchy(tags = listOf(alpha, beta, gamma).map { TagWithCount(it, 0) })
        val first = clip(alpha, id = 2, savedAt = "2026-06-02T00:00:00Z")
        val second = clip(beta, id = 1, savedAt = "2026-06-02T00:00:00Z")
        val third = clip(gamma, id = 3, savedAt = "2026-06-03T00:00:00Z")

        assertEquals(
            listOf(third, second, first),
            sortClipsForDisplay(
                listOf(first, second, third),
                hierarchy,
                TweetFilterState(),
                ClassifiedSortState(),
            ),
        )
        assertEquals(listOf(second, first), sortClipsForDisplay(listOf(first, second), hierarchy, TweetFilterState(), ClassifiedSortState()))
    }

    @Test
    fun likeAndPostTimeSortTreatMissingValuesAsLowest() {
        val goodLike = clip(kotlin, id = 201, likeCount = 12, savedAt = "2026-06-02T00:00:00Z")
        val missingLike = clip(compose, id = 202, savedAt = "2026-06-03T00:00:00Z")
        val goodDate = clip(kotlin, id = 203, createdAt = "2026-06-02T00:00:00Z")
        val missingDate = clip(compose, id = 204, createdAt = "not-a-date")

        assertEquals(
            listOf(goodLike, missingLike),
            sortClipsForDisplay(
                listOf(missingLike, goodLike),
                hierarchy(),
                TweetFilterState(),
                ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount),
            ),
        )
        assertEquals(
            listOf(missingLike, goodLike),
            sortClipsForDisplay(
                listOf(missingLike, goodLike),
                hierarchy(),
                TweetFilterState(),
                ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount, likeCountDescending = false),
            ),
        )
        assertEquals(
            listOf(goodDate, missingDate),
            sortClipsForDisplay(
                listOf(missingDate, goodDate),
                hierarchy(),
                TweetFilterState(),
                ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime),
            ),
        )
        assertEquals(
            listOf(missingDate, goodDate),
            sortClipsForDisplay(
                listOf(missingDate, goodDate),
                hierarchy(),
                TweetFilterState(),
                ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime, postTimeDescending = false),
            ),
        )
    }

    @Test
    fun tagSortUsesIncludedCandidatesAndIgnoresExcludedTags() {
        val first = TagEntity(301, "First", sortOrder = 0, createdAt = now, updatedAt = now)
        val second = TagEntity(302, "Second", sortOrder = 1, createdAt = now, updatedAt = now)
        val third = TagEntity(303, "Third", sortOrder = 2, createdAt = now, updatedAt = now)
        val hierarchy = TagHierarchy(tags = listOf(first, second, third).map { TagWithCount(it, 0) })
        val filters = TweetFilterState(
            tagFilters = mapOf(
                TagNodeRef(TagNodeType.TAG, second.id) to TagFilterState.INCLUDED,
                TagNodeRef(TagNodeType.TAG, first.id) to TagFilterState.EXCLUDED,
            ),
        )
        val excludedHeavy = clip(first, second, id = 401, savedAt = "2026-06-01T00:00:00Z")
        val includedOnly = clip(second, third, id = 402, savedAt = "2026-06-02T00:00:00Z")

        assertEquals(
            listOf(includedOnly, excludedHeavy),
            sortClipsForDisplay(
                listOf(excludedHeavy, includedOnly),
                hierarchy,
                filters,
                ClassifiedSortState(tagEnabled = true),
            ),
        )
    }

    @Test
    fun tagAndUserPriorityChangesTheWinningCriterion() {
        val first = TagEntity(401, "First", sortOrder = 0, createdAt = now, updatedAt = now)
        val second = TagEntity(402, "Second", sortOrder = 1, createdAt = now, updatedAt = now)
        val hierarchy = TagHierarchy(tags = listOf(first, second).map { TagWithCount(it, 0) })
        val alphaNew = clip(second, id = 501, authorName = "Alpha", username = "alpha", savedAt = "2026-06-03T00:00:00Z")
        val alphaOld = clip(second, id = 502, authorName = "Alpha", username = "alpha", savedAt = "2026-06-01T00:00:00Z")
        val beta = clip(first, id = 503, authorName = "Beta", username = "beta", savedAt = "2026-06-02T00:00:00Z")
        val source = listOf(alphaNew, alphaOld, beta)

        assertEquals(
            listOf(beta, alphaNew, alphaOld),
            sortClipsForDisplay(
                source,
                hierarchy,
                TweetFilterState(),
                ClassifiedSortState(tagEnabled = true, userEnabled = true, priority = ClassifiedSortPriority.TagFirst),
            ),
        )
        assertEquals(
            listOf(alphaNew, alphaOld, beta),
            sortClipsForDisplay(
                source,
                hierarchy,
                TweetFilterState(),
                ClassifiedSortState(tagEnabled = true, userEnabled = true, priority = ClassifiedSortPriority.UserFirst),
            ),
        )
    }

    @Test
    fun taglessPostsSortToTailOrHeadDependingOnDirection() {
        val first = TagEntity(601, "First", sortOrder = 0, createdAt = now, updatedAt = now)
        val second = TagEntity(602, "Second", sortOrder = 1, createdAt = now, updatedAt = now)
        val hierarchy = TagHierarchy(tags = listOf(first, second).map { TagWithCount(it, 0) })
        val tagged = clip(first, id = 701, savedAt = "2026-06-01T00:00:00Z")
        val untagged = clip(id = 702, savedAt = "2026-06-02T00:00:00Z")

        assertEquals(
            listOf(tagged, untagged),
            sortClipsForDisplay(
                listOf(untagged, tagged),
                hierarchy,
                TweetFilterState(),
                ClassifiedSortState(tagEnabled = true),
            ),
        )
        assertEquals(
            listOf(untagged, tagged),
            sortClipsForDisplay(
                listOf(untagged, tagged),
                hierarchy,
                TweetFilterState(),
                ClassifiedSortState(tagEnabled = true, tagDescending = true),
            ),
        )
    }

    @Test
    fun userSortUsesFilteredCountsRatherThanHiddenClips() {
        val alphaVisible = clip(id = 501, text = "needle", authorName = "Alpha", username = "zeta")
        val alphaHidden = clip(id = 502, text = "hidden", authorName = "Alpha", username = "zeta")
        val betaVisible = clip(id = 503, text = "needle", authorName = "Beta", username = "alpha")
        val source = listOf(alphaVisible, alphaHidden, betaVisible)
        val filters = TweetFilterState(query = "needle", taggedOnly = false)
        val filtered = filterClipsForSearch(source, hierarchy(), filters)

        assertEquals(
            listOf(betaVisible, alphaVisible),
            sortClipsForDisplay(
                filtered,
                hierarchy(),
                filters,
                ClassifiedSortState(userEnabled = true),
            ),
        )
    }

    @Test
    fun reversedDateRangeReturnsEmptyList() {
        val clip = clip(kotlin, createdAt = "2026-06-15T00:00:00Z")
        val filters = TweetFilterState(
            startDate = java.time.LocalDate.of(2026, 6, 20),
            endDate = java.time.LocalDate.of(2026, 6, 1),
        )

        assertEquals(emptyList<ClipWithDetails>(), filterClipsForSearch(listOf(clip), hierarchy(), filters))
    }

    @Test(expected = IllegalArgumentException::class)
    fun siblingNameCannotDuplicateAcrossGroupAndTag() {
        requireSiblingNameAvailable(
            groups = listOf(parent),
            tags = emptyList(),
            parentGroupId = null,
            name = parent.name,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun groupCannotMoveIntoDescendant() {
        requireValidGroupDestination(listOf(parent, child), parent.id, child.id)
    }

    @Test
    fun rootNodeParentIsResolvedWithoutTreatingNullAsMissing() {
        assertEquals(null, parentGroupIdForMove(TagNodeRef(TagNodeType.GROUP, parent.id), listOf(parent), emptyList()))
        assertEquals(null, parentGroupIdForMove(TagNodeRef(TagNodeType.TAG, design.id), emptyList(), listOf(design)))
    }

    @Test
    fun movingWithinSameParentAdjustsDownwardIndexAfterRemovingSource() {
        val a = TagNodeRef(TagNodeType.TAG, 1)
        val b = TagNodeRef(TagNodeType.TAG, 2)
        val c = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(b, a, c), orderNodesAfterMove(listOf(a, b, c), a, 2))
        assertEquals(listOf(b, c, a), orderNodesAfterMove(listOf(a, b, c), a, 3))
        assertEquals(listOf(c, a, b), orderNodesAfterMove(listOf(a, b, c), c, 0))
    }

    @Test
    fun movingAcrossParentsInsertsAtRequestedIndex() {
        val moved = TagNodeRef(TagNodeType.TAG, 1)
        val a = TagNodeRef(TagNodeType.TAG, 2)
        val b = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(a, moved, b), orderNodesAfterMove(listOf(a, b), moved, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeMoveIndexFailsInsteadOfMovingToHead() {
        val moved = TagNodeRef(TagNodeType.TAG, 1)
        val a = TagNodeRef(TagNodeType.TAG, 2)

        orderNodesAfterMove(listOf(a), moved, -1)
    }

    @Test
    fun maxMoveIndexAppendsToTail() {
        val moved = TagNodeRef(TagNodeType.TAG, 1)
        val a = TagNodeRef(TagNodeType.TAG, 2)
        val b = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(a, b, moved), orderNodesAfterMove(listOf(a, b), moved, Int.MAX_VALUE))
    }

    @Test
    fun slotMoveUsesIndexAfterRemovingDraggedNode() {
        val a = TagNodeRef(TagNodeType.TAG, 1)
        val b = TagNodeRef(TagNodeType.TAG, 2)
        val c = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(b, a, c), orderNodesAfterMoveAtSlot(listOf(a, b, c), a, 1))
        assertEquals(listOf(b, c, a), orderNodesAfterMoveAtSlot(listOf(a, b, c), a, 2))
        assertEquals(listOf(c, a, b), orderNodesAfterMoveAtSlot(listOf(a, b, c), c, 0))
    }

    @Test
    fun slotMoveAcrossParentsInsertsAtRequestedSlot() {
        val moved = TagNodeRef(TagNodeType.GROUP, 1)
        val a = TagNodeRef(TagNodeType.TAG, 2)
        val b = TagNodeRef(TagNodeType.TAG, 3)

        assertEquals(listOf(a, moved, b), orderNodesAfterMoveAtSlot(listOf(a, b), moved, 1))
        assertEquals(listOf(a, b, moved), orderNodesAfterMoveAtSlot(listOf(a, b), moved, Int.MAX_VALUE))
    }

    @Test
    fun randomizedSlotMovesAlwaysPreserveEveryNodeExactlyOnce() {
        val random = Random(0x4C4C4D)
        repeat(250) {
            val size = random.nextInt(1, 80)
            val nodes = (0 until size).map { id -> TagNodeRef(TagNodeType.TAG, id.toLong()) }
            val moved = nodes[random.nextInt(nodes.size)]
            val requestedSlot = random.nextInt(0, size + 20)

            val result = orderNodesAfterMoveAtSlot(nodes, moved, requestedSlot)

            assertEquals(nodes.size, result.size)
            assertEquals(nodes.toSet(), result.toSet())
            assertEquals(1, result.count { it == moved })
            assertEquals(requestedSlot.coerceAtMost(size - 1), result.indexOf(moved))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeSlotIndexFails() {
        val moved = TagNodeRef(TagNodeType.TAG, 1)

        orderNodesAfterMoveAtSlot(emptyList(), moved, -1)
    }

    @Test
    fun groupDropKeepsPreviousVisualPlaceholder() {
        val tag = TagLeafNode(design, 0)
        val group = TagGroupNode(parent, 0)
        val rows = listOf(
            VisibleTagRow(tag, depth = 0, parentGroupId = null, indexInParent = 0),
            VisibleTagRow(group, depth = 0, parentGroupId = null, indexInParent = 1),
        )
        val initial = dragState(
            node = tag.ref(),
            targetParentId = null,
            placeholderIndex = 1,
            visualParentId = null,
            visualPlaceholderIndex = 1,
            pointerYInRoot = 122f,
            grabOffsetY = 20f,
            itemHeight = 40f,
            lastDragCenterY = 122f,
        )
        val updated = updateDragStateAfterMove(
            state = initial,
            visibleRows = rows,
            displayItems = buildTagListItems(rows, initial),
            rowBounds = mapOf(group.ref() to Rect(0f, 100f, 300f, 160f)),
            hierarchy = TagHierarchy(groups = listOf(parent), tags = listOf(TagWithCount(design, 0))),
            listBounds = Rect(0f, 0f, 300f, 500f),
            allowStaticCrossing = false,
        )
        val items = buildTagListItems(rows, updated)
        val placeholder = items.filterIsInstance<TagListItem.Placeholder>().single()

        assertTrue(updated.targetIsGroupDrop)
        assertEquals(parent.id, updated.targetParentId)
        assertEquals(null, placeholder.parentGroupId)
        assertEquals(1, placeholder.index)
    }

    @Test
    fun draggedPlaceholderUsesSeparateKeyFromDraggedRow() {
        val tag = TagLeafNode(design, 0)
        val rows = listOf(VisibleTagRow(tag, depth = 0, parentGroupId = null, indexInParent = 0))
        val state = dragState(node = tag.ref(), placeholderIndex = 0)
        val items = buildTagListItems(rows, state)

        assertEquals(1, items.size)
        assertTrue(items.single() is TagListItem.Placeholder)
        assertFalse(items.single().key == tag.ref().saveableKeyForTest())
    }

    @Test
    fun topItemMovesDownOnlyOneSlotPerCrossing() {
        val a = TagLeafNode(design.copy(id = 20, name = "A", sortOrder = 0), 0)
        val b = TagLeafNode(design.copy(id = 21, name = "B", sortOrder = 1), 0)
        val c = TagLeafNode(design.copy(id = 22, name = "C", sortOrder = 2), 0)
        val rows = listOf(
            VisibleTagRow(a, depth = 0, parentGroupId = null, indexInParent = 0),
            VisibleTagRow(b, depth = 0, parentGroupId = null, indexInParent = 1),
            VisibleTagRow(c, depth = 0, parentGroupId = null, indexInParent = 2),
        )
        val beforeCross = dragState(
            node = a.ref(),
            placeholderIndex = 0,
            visualPlaceholderIndex = 0,
            pointerYInRoot = 80f,
            grabOffsetY = 20f,
            itemHeight = 40f,
            lastDragCenterY = 100f,
        )
        val crossed = beforeCross.copy(pointerYInRoot = 135f)
        val movedOnce = updateDragStateAfterMove(
            state = crossed,
            visibleRows = rows,
            displayItems = buildTagListItems(rows, crossed),
            rowBounds = mapOf(
                b.ref() to Rect(0f, 100f, 300f, 160f),
                c.ref() to Rect(0f, 180f, 300f, 240f),
            ),
            hierarchy = TagHierarchy(groups = emptyList(), tags = listOf(TagWithCount(a.tag, 0), TagWithCount(b.tag, 0), TagWithCount(c.tag, 0))),
            listBounds = Rect(0f, 0f, 300f, 500f),
            allowStaticCrossing = false,
        )
        val repeated = updateDragStateAfterMove(
            state = movedOnce.copy(reorderLocked = false),
            visibleRows = rows,
            displayItems = buildTagListItems(rows, movedOnce),
            rowBounds = mapOf(
                b.ref() to Rect(0f, 100f, 300f, 160f),
                c.ref() to Rect(0f, 180f, 300f, 240f),
            ),
            hierarchy = TagHierarchy(groups = emptyList(), tags = listOf(TagWithCount(a.tag, 0), TagWithCount(b.tag, 0), TagWithCount(c.tag, 0))),
            listBounds = Rect(0f, 0f, 300f, 500f),
            allowStaticCrossing = false,
        )

        assertEquals(1, movedOnce.placeholderIndex)
        assertEquals(1, movedOnce.visualPlaceholderIndex)
        assertEquals(1, repeated.placeholderIndex)
    }

    @Test
    fun autoScrollDoesNotRequestUnavailableDirection() {
        val bounds = Rect(0f, 100f, 300f, 500f)

        assertEquals(0f, calculateAutoScrollDelta(110f, bounds, canScrollBackward = false, canScrollForward = true), 0.001f)
        assertTrue(calculateAutoScrollDelta(110f, bounds, canScrollBackward = true, canScrollForward = true) < 0f)
        assertEquals(0f, calculateAutoScrollDelta(490f, bounds, canScrollBackward = true, canScrollForward = false), 0.001f)
        assertTrue(calculateAutoScrollDelta(490f, bounds, canScrollBackward = true, canScrollForward = true) > 0f)
    }

    @Test
    fun buildMediaGridEntriesKeepsClipOrderFiltersUnsupportedAssetsAndUsesFallbackUrls() {
        val existingPath = File.createTempFile("media-grid", ".webp").apply {
            writeBytes(byteArrayOf(1))
            deleteOnExit()
        }.absolutePath
        val missingPath = File(existingPath).parentFile!!.resolve("missing-media-grid.webp").absolutePath
        val firstClip = clip(
            id = 1,
            text = "first needle clip",
            authorName = "author1",
            username = "author1",
        )
        val secondClip = clip(
            id = 2,
            text = "second needle clip",
            authorName = "author2",
            username = "author2",
        )
        val firstSource = mediaClip(
            clip = firstClip,
            assets = listOf(
                mediaGridAsset(
                    clipId = 1,
                    assetId = 10,
                    mediaKey = "video-preview",
                    assetType = "video_thumbnail",
                    remoteUrl = null,
                    previewUrl = "https://example.test/video-preview.jpg",
                    localPath = null,
                    downloadState = "downloaded",
                ),
                mediaGridAsset(
                    clipId = 1,
                    assetId = 20,
                    mediaKey = "photo-remote",
                    assetType = "photo",
                    remoteUrl = "https://example.test/photo-remote.jpg",
                    previewUrl = null,
                    localPath = null,
                    downloadState = "downloaded",
                ),
                mediaGridAsset(
                    clipId = 1,
                    assetId = 30,
                    mediaKey = "photo-existing",
                    assetType = "photo",
                    remoteUrl = "https://example.test/photo-existing.jpg",
                    previewUrl = null,
                    localPath = existingPath,
                    downloadState = "downloaded",
                ),
                mediaGridAsset(
                    clipId = 1,
                    assetId = 99,
                    mediaKey = "animated",
                    assetType = "animated_gif",
                    remoteUrl = "https://example.test/animated.gif",
                    previewUrl = "https://example.test/animated.jpg",
                    localPath = null,
                    downloadState = "downloaded",
                ),
            ),
        )
        val secondSource = mediaClip(
            clip = secondClip,
            assets = listOf(
                mediaGridAsset(
                    clipId = 2,
                    assetId = 40,
                    mediaKey = "photo-missing",
                    assetType = "photo",
                    remoteUrl = "https://example.test/photo-missing.jpg",
                    previewUrl = null,
                    localPath = missingPath,
                    downloadState = "downloaded",
                ),
                mediaGridAsset(
                    clipId = 2,
                    assetId = 41,
                    mediaKey = "photo-failed",
                    assetType = "photo",
                    remoteUrl = "https://example.test/photo-failed.jpg",
                    previewUrl = null,
                    localPath = null,
                    downloadState = "failed",
                ),
            ),
        )

        val entries = buildMediaGridEntries(listOf(firstSource, secondSource))

        assertEquals(listOf(10L, 20L, 30L, 40L, 41L), entries.map { it.assetId })
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L), entries.map { it.clipId })
        assertEquals(listOf(0, 1, 2, 0, 1), entries.map { it.mediaIndex })
        assertEquals("https://example.test/video-preview.jpg", entries[0].displayUrl)
        assertEquals("https://example.test/photo-remote.jpg", entries[1].displayUrl)
        assertEquals(existingPath, entries[2].displayUrl)
        assertTrue(entries[2].hasLocalFile)
        assertFalse(entries[0].hasLocalFile)
        assertEquals(missingPath, entries[3].localPath)
        assertFalse(entries[3].hasLocalFile)
        assertEquals("failed", entries[4].downloadState)
    }

    @Test
    fun buildClassifiedMediaGridItemsAddsHeadersForDateAndLikeBuckets() {
        val clips = listOf(
            mediaClip(
                clip = clip(
                    id = 1,
                    createdAt = "2026-06-15T10:00:00Z",
                    likeCount = 1_234,
                    authorName = "first",
                    username = "first",
                ),
                assets = listOf(
                    mediaGridAsset(clipId = 1, assetId = 11, mediaKey = "first-1", assetType = "photo", remoteUrl = "https://example.test/first-1.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                    mediaGridAsset(clipId = 1, assetId = 12, mediaKey = "first-2", assetType = "video_thumbnail", remoteUrl = null, previewUrl = "https://example.test/first-2.jpg", localPath = null, downloadState = "downloaded"),
                ),
            ),
            mediaClip(
                clip = clip(
                    id = 2,
                    createdAt = "2026-06-15T12:00:00Z",
                    likeCount = 1_999,
                    authorName = "second",
                    username = "second",
                ),
                assets = listOf(
                    mediaGridAsset(clipId = 2, assetId = 21, mediaKey = "second-1", assetType = "photo", remoteUrl = "https://example.test/second-1.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                ),
            ),
            mediaClip(
                clip = clip(
                    id = 3,
                    createdAt = "not-an-instant",
                    likeCount = null,
                    authorName = "third",
                    username = "third",
                ),
                assets = listOf(
                    mediaGridAsset(clipId = 3, assetId = 31, mediaKey = "third-1", assetType = "photo", remoteUrl = "https://example.test/third-1.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                ),
            ),
        )
        val entries = buildMediaGridEntries(clips)

        val postItems = buildClassifiedMediaGridItems(entries, ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), 4)
        assertEquals(
            listOf(
                "media_grid_header_post_time_day_2026-06-15",
                "media_grid_item_11",
                "media_grid_item_12",
                "media_grid_item_21",
                "media_grid_header_post_time_unknown",
                "media_grid_item_31",
            ),
            postItems.map { it.key },
        )
        assertEquals(
            listOf("2026/6/15", "日付不明"),
            postItems.filterIsInstance<MediaGridHeaderItem>().map { it.label },
        )

        val likeItems = buildClassifiedMediaGridItems(entries, ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount), 4)
        assertEquals(
            listOf(
                "media_grid_header_like_count_1000_1000",
                "media_grid_item_11",
                "media_grid_item_12",
                "media_grid_item_21",
                "media_grid_header_like_count_unknown",
                "media_grid_item_31",
            ),
            likeItems.map { it.key },
        )
        assertEquals(
            listOf("1,000〜1,999", "いいね数不明"),
            likeItems.filterIsInstance<MediaGridHeaderItem>().map { it.label },
        )
    }

    @Test
    fun buildClassifiedMediaGridItemsUsesWeekAndMonthBucketsForWiderGrids() {
        val clips = listOf(
            mediaClip(
                clip = clip(
                    id = 1,
                    createdAt = "2026-06-18T10:00:00Z",
                    likeCount = 15_000,
                    authorName = "wide",
                    username = "wide",
                ),
                assets = listOf(
                    mediaGridAsset(clipId = 1, assetId = 11, mediaKey = "wide-1", assetType = "photo", remoteUrl = "https://example.test/wide-1.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                ),
            ),
            mediaClip(
                clip = clip(
                    id = 2,
                    createdAt = "2026-06-19T10:00:00Z",
                    likeCount = 4_999,
                    authorName = "wide2",
                    username = "wide2",
                ),
                assets = listOf(
                    mediaGridAsset(clipId = 2, assetId = 21, mediaKey = "wide-2", assetType = "photo", remoteUrl = "https://example.test/wide-2.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                ),
            ),
        )
        val entries = buildMediaGridEntries(clips)

        val weekItems = buildClassifiedMediaGridItems(entries, ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), 5)
        assertEquals(listOf("media_grid_header_post_time_week_2026-06-15", "media_grid_item_11", "media_grid_item_21"), weekItems.map { it.key })
        assertEquals(listOf("2026/6/15週"), weekItems.filterIsInstance<MediaGridHeaderItem>().map { it.label })

        val monthItems = buildClassifiedMediaGridItems(entries, ClassifiedSortState(baseOrder = ClassifiedSortBase.PostTime), 9)
        assertEquals(listOf("media_grid_header_post_time_month_2026-06", "media_grid_item_11", "media_grid_item_21"), monthItems.map { it.key })
        assertEquals(listOf("2026/6"), monthItems.filterIsInstance<MediaGridHeaderItem>().map { it.label })

        val likeItems = buildClassifiedMediaGridItems(entries, ClassifiedSortState(baseOrder = ClassifiedSortBase.LikeCount), 9)
        assertEquals(listOf("media_grid_header_like_count_10000_10000", "media_grid_item_11", "media_grid_header_like_count_10000_0", "media_grid_item_21"), likeItems.map { it.key })
        assertEquals(listOf("10,000〜19,999", "0〜9,999"), likeItems.filterIsInstance<MediaGridHeaderItem>().map { it.label })
    }

    @Test
    fun classifiedMediaGridColumnCountFollowsPinchScaleAndClampsBounds() {
        assertEquals(ClassifiedMediaGridDefaultColumnCount, classifiedMediaGridColumnCountForScale(4, 1f))
        assertEquals(5, classifiedMediaGridColumnCountForScale(4, 1.13f))
        assertEquals(3, classifiedMediaGridColumnCountForScale(4, 0.88f))
        assertEquals(ClassifiedMediaGridMaxColumnCount, classifiedMediaGridColumnCountForScale(12, 2f))
        assertEquals(ClassifiedMediaGridMinColumnCount, classifiedMediaGridColumnCountForScale(2, 0.2f))
    }

    @Test
    fun mediaGridSelectionBackgroundUsesLightBlueForSingleAndBlueForMultiAsset() {
        val primary = Color(0.4f, 0.7f, 1f, 1f)

        val singleBackground = mediaGridSelectionBackground(primary, multiAsset = false)
        val multiBackground = mediaGridSelectionBackground(primary, multiAsset = true)
        assertTrue(singleBackground.blue >= singleBackground.red)
        assertTrue(singleBackground.blue >= singleBackground.green)
        assertTrue(multiBackground.blue > multiBackground.red)
        assertTrue(multiBackground.blue > multiBackground.green)
        assertTrue(multiBackground.red < singleBackground.red)
    }

    @Test
    fun mediaGridHapticOnlyOccursWhenLongPressStartsSelectionMode() {
        var hapticCalls = 0
        var toggleCalls = 0
        handleMediaGridLongPress(
            selectionMode = false,
            haptic = { hapticCalls++ },
            toggleSelection = { toggleCalls++ },
        )
        handleMediaGridLongPress(
            selectionMode = true,
            haptic = { hapticCalls++ },
            toggleSelection = { toggleCalls++ },
        )
        assertEquals(1, hapticCalls)
        assertEquals(2, toggleCalls)
    }

    @Test
    fun buildClassifiedMediaGridItemsLeavesDefaultSortWithoutHeaders() {
        val clips = listOf(
            mediaClip(
                clip = clip(id = 1, createdAt = "2026-06-15T10:00:00Z", likeCount = 12, authorName = "default", username = "default"),
                assets = listOf(
                    mediaGridAsset(clipId = 1, assetId = 11, mediaKey = "default-1", assetType = "photo", remoteUrl = "https://example.test/default-1.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                ),
            ),
            mediaClip(
                clip = clip(id = 2, createdAt = "not-an-instant", likeCount = null, authorName = "default2", username = "default2"),
                assets = listOf(
                    mediaGridAsset(clipId = 2, assetId = 21, mediaKey = "default-2", assetType = "photo", remoteUrl = "https://example.test/default-2.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                ),
            ),
        )
        val entries = buildMediaGridEntries(clips)

        val items = buildClassifiedMediaGridItems(entries, ClassifiedSortState(), 4)
        assertEquals(listOf("media_grid_item_11", "media_grid_item_21"), items.map { it.key })
        assertTrue(items.none { it is MediaGridHeaderItem })
    }

    @Test
    fun lightweightMediaFilteringAndSortingMatchesClassifiedCards() {
        val tag = TagEntity(id = 1, name = "NeedleTag", createdAt = now, updatedAt = now)
        val alpha = ClipWithDetails(
            clip = ClipEntity(
                id = 1,
                xPostId = "x-1",
                authorName = "Alpha User",
                authorUsername = "alpha_user",
                text = "needle alpha",
                postUrl = "https://x.com/alpha_user/status/x-1",
                xCreatedAt = "2026-06-15T10:00:00Z",
                savedAt = "2026-06-15T10:00:00Z",
                syncedAt = now,
                likeCount = 3,
            ),
            assets = emptyList(),
            tags = listOf(tag),
        )
        val beta = ClipWithDetails(
            clip = ClipEntity(
                id = 2,
                xPostId = "x-2",
                authorName = "Beta User",
                authorUsername = "beta_user",
                text = "needle beta",
                postUrl = "https://x.com/beta_user/status/x-2",
                xCreatedAt = "2026-06-15T09:00:00Z",
                savedAt = "2026-06-15T09:00:00Z",
                syncedAt = now,
                likeCount = 9,
            ),
            assets = emptyList(),
            tags = listOf(tag),
        )
        val gamma = ClipWithDetails(
            clip = ClipEntity(
                id = 3,
                xPostId = "x-3",
                authorName = "Gamma User",
                authorUsername = "gamma_user",
                text = "needle gamma",
                postUrl = "https://x.com/gamma_user/status/x-3",
                xCreatedAt = "2026-06-15T08:00:00Z",
                savedAt = "2026-06-15T08:00:00Z",
                syncedAt = now,
                likeCount = 6,
            ),
            assets = emptyList(),
            tags = listOf(tag),
        )
        val cardClips = listOf(alpha, beta, gamma)
        val mediaClips = listOf(
            mediaClip(
                clip = alpha,
                assets = listOf(
                    mediaGridAsset(clipId = 1, assetId = 10, mediaKey = "alpha-photo-1", assetType = "photo", remoteUrl = "https://example.test/alpha-1.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                    mediaGridAsset(clipId = 1, assetId = 11, mediaKey = "alpha-photo-2", assetType = "photo", remoteUrl = "https://example.test/alpha-2.jpg", previewUrl = null, localPath = null, downloadState = "downloaded"),
                ),
            ),
            mediaClip(
                clip = beta,
                assets = listOf(
                    mediaGridAsset(clipId = 2, assetId = 20, mediaKey = "beta-thumb", assetType = "video_thumbnail", remoteUrl = null, previewUrl = "https://example.test/beta-thumb.jpg", localPath = null, downloadState = "downloaded"),
                ),
            ),
            mediaClip(
                clip = gamma,
                assets = emptyList(),
            ),
        )
        val hierarchy = TagHierarchy(
            groups = emptyList(),
            tags = listOf(TagWithCount(tag, 0)),
            clipTags = listOf(
                ClipTagEntity(1, tag.id, now),
                ClipTagEntity(2, tag.id, now),
                ClipTagEntity(3, tag.id, now),
            ),
        )
        val filters = TweetFilterState(
            query = "needle",
            searchTargets = setOf(SearchTarget.Text),
            tagFilters = mapOf(TagNodeRef(TagNodeType.TAG, tag.id) to TagFilterState.REQUIRED),
        )
        val sort = ClassifiedSortState(
            baseOrder = ClassifiedSortBase.PostTime,
            postTimeDescending = true,
        )

        val cardResult = sortClipsForDisplay(filterClipsForSearch(cardClips, hierarchy, filters), hierarchy, filters, sort)
        val mediaResult = sortClipsForDisplay(filterClipsForSearch(mediaClips, hierarchy, filters), hierarchy, filters, sort)
        val entries = buildMediaGridEntries(mediaResult)

        assertEquals(cardResult.map { it.clip.id }, mediaResult.map { it.clip.id })
        assertEquals(listOf(1L, 1L, 2L), entries.map { it.clipId })
        assertEquals(listOf(10L, 11L, 20L), entries.map { it.assetId })
    }

    private fun hierarchy(clipTags: List<ClipTagEntity> = emptyList()) = TagHierarchy(
        groups = listOf(parent, child),
        tags = listOf(kotlin, compose, design).map { TagWithCount(it, 0) },
        clipTags = clipTags,
    )

    private fun dragState(
        node: TagNodeRef,
        targetParentId: Long? = null,
        placeholderIndex: Int = 0,
        visualParentId: Long? = targetParentId,
        visualPlaceholderIndex: Int = placeholderIndex,
        pointerYInRoot: Float = 20f,
        grabOffsetY: Float = 20f,
        itemHeight: Float = 40f,
        lastDragCenterY: Float = pointerYInRoot - grabOffsetY + itemHeight / 2,
    ) = DragState(
        node = node,
        label = "drag",
        isGroup = node.type == TagNodeType.GROUP,
        sourceParentId = null,
        sourceIndexInParent = 0,
        targetParentId = targetParentId,
        placeholderIndex = placeholderIndex,
        visualParentId = visualParentId,
        visualPlaceholderIndex = visualPlaceholderIndex,
        pointerYInRoot = pointerYInRoot,
        grabOffsetY = grabOffsetY,
        itemLeftX = 0f,
        itemWidth = 300f,
        itemHeight = itemHeight,
        lastDragCenterY = lastDragCenterY,
    )

    private fun TagNodeRef.saveableKeyForTest(): String = "${type.name}:$id"

    private fun clip(
        vararg tags: TagEntity,
        id: Long = 100,
        text: String = "text",
        summary: String = "",
        authorId: String? = null,
        authorName: String = "author",
        username: String = "author",
        createdAt: String = now,
        savedAt: String = now,
        likeCount: Long? = null,
    ) = ClipWithDetails(
        clip = ClipEntity(
            id = id,
            xPostId = "x-$id",
            authorId = authorId,
            authorName = authorName,
            authorUsername = username,
            text = text,
            postUrl = "https://x.com/$username/status/x-$id",
            xCreatedAt = createdAt,
            savedAt = savedAt,
            syncedAt = now,
            summary = summary,
            likeCount = likeCount,
        ),
        assets = emptyList(),
        tags = tags.toList(),
    )

    private fun mediaClip(
        clip: ClipWithDetails,
        assets: List<MediaGridAssetRow>,
    ): MediaGridClipSource = MediaGridClipSource(
        clip = clip.clip,
        tags = clip.tags,
        assets = assets,
    )

    private fun mediaGridAsset(
        clipId: Long,
        assetId: Long,
        mediaKey: String,
        assetType: String,
        remoteUrl: String?,
        previewUrl: String?,
        localPath: String?,
        downloadState: String,
    ) = MediaGridAssetRow(
        clipId = clipId,
        xPostId = "x-$clipId",
        authorId = null,
        authorName = "author-$clipId",
        authorUsername = "author_$clipId",
        text = "text-$clipId",
        summary = "",
        ocrText = "",
        postUrl = "https://x.com/author_$clipId/status/x-$clipId",
        xCreatedAt = now,
        savedAt = now,
        syncedAt = now,
        likeCount = null,
        assetId = assetId,
        mediaKey = mediaKey,
        assetType = assetType,
        remoteUrl = remoteUrl,
        previewUrl = previewUrl,
        localPath = localPath,
        width = null,
        height = null,
        downloadState = downloadState,
    )
}
