package com.lyco256.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipRepository
import com.lyco256.llm.data.ClipTagEntity
import com.lyco256.llm.data.DisabledOAuthGateway
import com.lyco256.llm.data.DisabledXApiGateway
import com.lyco256.llm.data.InMemorySettingsStore
import com.lyco256.llm.data.LikeListDatabase
import com.lyco256.llm.data.PostStorageConfig
import com.lyco256.llm.data.PostStorageManager
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchFilterDatabaseIntegrationTest {
    private lateinit var context: Context
    private lateinit var storage: PostStorageManager
    private lateinit var repository: ClipRepository
    private val storageConfig = PostStorageConfig(
        databaseName = "search_filter_database_integration_test.db",
        imagesDirectory = "search_filter_test_images",
        dataDirectory = "search_filter_test_data",
        preferencesName = "search_filter_test_preferences",
    )

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(storageConfig.databaseName)
        File(context.filesDir, storageConfig.imagesDirectory).deleteRecursively()
        context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        storage = PostStorageManager(context, storageConfig)
        repository = ClipRepository(
            context = context,
            postStorageManager = storage,
            apiSettingsStore = InMemorySettingsStore(),
            xOAuthManager = DisabledOAuthGateway(),
            xApiClient = DisabledXApiGateway(),
            includeSeedMedia = false,
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            storage.database.value?.close()
            context.deleteDatabase(storageConfig.databaseName)
            File(context.filesDir, storageConfig.imagesDirectory).deleteRecursively()
            context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun repositoryFlowsFeedCompositeSearchWithoutMutatingDatabase() = runBlocking {
        val fixture = storage.withDatabase { database ->
            insertSearchFixture(database)
        }
        val before = databaseFingerprint()

        val clips = repository.clipsWithDetails.first { it.size == 5 }
        val hierarchy = repository.tagHierarchy.first { it.tags.size == 2 && it.groups.size == 2 }
        val filters = TweetFilterState(
                startDate = java.time.LocalDate.of(2026, 6, 15),
                endDate = java.time.LocalDate.of(2026, 6, 15),
                selectedAuthors = setOf(TweetAuthorKey(authorId = "author-alpha", username = "alpha")),
                tagFilters = mapOf(
                    TagNodeRef(TagNodeType.GROUP, fixture.requiredGroupId) to TagFilterState.REQUIRED,
                    TagNodeRef(TagNodeType.TAG, fixture.excludedTagId) to TagFilterState.EXCLUDED,
                ),
                taggedOnly = true,
        )
        val filtered = filterClipsByConditions(
            clips = filterClipsByRegex(
                clips = clips,
                criteria = ClassifiedSearchCriteria(
                    query = "manual\\s+summary",
                    mode = SearchMode.Regex,
                    regexTargets = setOf(SearchTarget.Summary),
                ),
            ),
            hierarchy = hierarchy,
            filters = filters,
        )

        assertEquals(listOf("search-match"), filtered.map { it.clip.xPostId })
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun ocrTextSearchTargetMatchesTheStoredOcrTextField() = runBlocking {
        val clip = storage.withDatabase { database ->
            database.clipDao().insertClip(
                searchClip(
                    "ocr-search-match",
                    ocrText = "OCR Needle from local text",
                ),
            )
            database.clipDao().getAllClips().single { it.xPostId == "ocr-search-match" }
        }

        val clips = repository.clipsWithDetails.first { it.any { item -> item.clip.id == clip.id } }
        val hierarchy = repository.tagHierarchy.first()
        val filtered = filterClipsByRegex(
            clips = clips,
            criteria = ClassifiedSearchCriteria(
                query = "ocr needle",
                mode = SearchMode.Regex,
                regexTargets = setOf(SearchTarget.OcrText),
            ),
        )

        assertEquals(listOf("ocr-search-match"), filtered.map { it.clip.xPostId })
    }

    private suspend fun insertSearchFixture(database: LikeListDatabase): SearchFixture {
        val clipDao = database.clipDao()
        val tagDao = database.tagDao()
        val now = "2026-06-15T00:00:00Z"
        val rootGroup = tagDao.insertGroup(TagGroupEntity(name = "SearchRoot", createdAt = now, updatedAt = now))
        val requiredGroup = tagDao.insertGroup(TagGroupEntity(name = "SearchChild", parentGroupId = rootGroup, createdAt = now, updatedAt = now))
        val requiredTag = tagDao.insertTag(TagEntity(name = "RequiredTag", parentGroupId = requiredGroup, createdAt = now, updatedAt = now))
        val excludedTag = tagDao.insertTag(TagEntity(name = "ExcludedTag", createdAt = now, updatedAt = now))

        val match = clipDao.insertClip(searchClip("search-match"))
        val wrongDate = clipDao.insertClip(searchClip("search-wrong-date", xCreatedAt = "2026-05-01T12:00:00Z"))
        val wrongAuthor = clipDao.insertClip(
            searchClip(
                "search-wrong-author",
                authorId = "author-beta",
                authorName = "Beta",
                authorUsername = "beta",
            ),
        )
        val excluded = clipDao.insertClip(searchClip("search-excluded"))
        clipDao.insertClip(searchClip("search-untagged"))

        listOf(match, wrongDate, wrongAuthor, excluded).forEach { clipId ->
            clipDao.insertClipTag(ClipTagEntity(clipId, requiredTag, now))
        }
        clipDao.insertClipTag(ClipTagEntity(excluded, excludedTag, now))
        return SearchFixture(requiredGroupId = requiredGroup, excludedTagId = excludedTag)
    }

    private fun searchClip(
        id: String,
        xCreatedAt: String = "2026-06-15T12:00:00Z",
        authorId: String = "author-alpha",
        authorName: String = "Alpha",
        authorUsername: String = "alpha",
        ocrText: String = "",
    ) = ClipEntity(
        xPostId = id,
        authorId = authorId,
        authorName = authorName,
        authorUsername = authorUsername,
        text = "Body without the search phrase",
        postUrl = "https://x.com/$authorUsername/status/$id",
        xCreatedAt = xCreatedAt,
        savedAt = id,
        syncedAt = "2026-06-15T12:30:00Z",
        summary = "Manual Summary from local DB",
        ocrText = ocrText,
    )

    private suspend fun databaseFingerprint(): SearchDbFingerprint = storage.withDatabase { database ->
        SearchDbFingerprint(
            clips = database.clipDao().getAllClips().map { clip ->
                "${clip.id}:${clip.xPostId}:${clip.summary}"
            }.sorted(),
            clipTags = database.clipDao().clipTagsForClipIds(database.clipDao().getAllClips().map { it.id }).map {
                "${it.clipId}:${it.tagId}"
            }.sorted(),
            groups = database.tagDao().getGroups().map { "${it.id}:${it.name}:${it.parentGroupId}" }.sorted(),
            tags = database.tagDao().getTags().map { "${it.id}:${it.name}:${it.parentGroupId}" }.sorted(),
        )
    }

    private data class SearchFixture(
        val requiredGroupId: Long,
        val excludedTagId: Long,
    )

    private data class SearchDbFingerprint(
        val clips: List<String>,
        val clipTags: List<String>,
        val groups: List<String>,
        val tags: List<String>,
    )
}
