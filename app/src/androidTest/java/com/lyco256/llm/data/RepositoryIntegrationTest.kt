package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import com.lyco256.llm.ClassifiedSortBase
import com.lyco256.llm.ClassifiedSortState
import com.lyco256.llm.SearchMode
import com.lyco256.llm.SearchTarget
import com.lyco256.llm.buildMediaGridEntries
import com.lyco256.llm.filterClipsForSearch
import com.lyco256.llm.sortClipsForDisplay
import com.lyco256.llm.TweetFilterState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class RepositoryIntegrationTest {
    private lateinit var context: Context
    private lateinit var storage: PostStorageManager
    private lateinit var settings: InMemorySettingsStore
    private lateinit var api: RecordingXApiGateway
    private lateinit var oauth: RecordingOAuthGateway
    private lateinit var repository: ClipRepository
    private val storageConfig = PostStorageConfig(
        databaseName = "repository_integration_test.db",
        imagesDirectory = "repository_test_images",
        dataDirectory = "repository_test_data",
        preferencesName = "repository_test_preferences",
    )

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(storageConfig.databaseName)
        File(context.filesDir, storageConfig.imagesDirectory).deleteRecursively()
        context.getSharedPreferences(storageConfig.preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        storage = PostStorageManager(context, storageConfig)
        settings = InMemorySettingsStore(
            ApiSettings("test-client"),
            OAuthSession(
                accessToken = "test-access",
                refreshToken = "test-refresh",
                expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000,
                scopes = XOAuthManager.SCOPES,
                xUserId = "user-1",
                username = "tester",
                displayName = "Tester",
            ),
        )
        api = RecordingXApiGateway()
        oauth = RecordingOAuthGateway()
        repository = newRepository()
    }

    private fun newRepository() = ClipRepository(
        context = context,
        postStorageManager = storage,
        apiSettingsStore = settings,
        xOAuthManager = oauth,
        xApiClient = api,
        ocrTextGateway = FakeOcrTextGateway { bitmap ->
            when {
                bitmap.width == 1 && bitmap.height == 1 -> ""
                bitmap.width == 2 && bitmap.height == 2 -> throw IllegalStateException("Fake OCR failure")
                bitmap.width > bitmap.height -> "Landscape OCR\nSecond line"
                else -> "Portrait OCR"
            }
        },
    )

    @Test
    fun observeClipWithDetailsTracksOnlyTheSelectedClipAndUpdates() = runBlocking {
        val now = Instant.now().toString()
        val selectedId = storage.withDatabase { database ->
            val selectedId = database.clipDao().insertClip(clip("selected"))
            val otherId = database.clipDao().insertClip(clip("other"))
            val tagId = database.tagDao().insertTag(TagEntity(name = "Selected", createdAt = now, updatedAt = now))
            database.clipDao().insertAssets(
                listOf(
                    AssetEntity(clipId = selectedId, mediaKey = "selected-2", type = "photo", remoteUrl = "https://example.test/2.jpg", previewUrl = null, createdAt = now),
                    AssetEntity(clipId = selectedId, mediaKey = "selected-1", type = "photo", remoteUrl = "https://example.test/1.jpg", previewUrl = null, createdAt = now),
                    AssetEntity(clipId = otherId, mediaKey = "other-1", type = "photo", remoteUrl = "https://example.test/other.jpg", previewUrl = null, createdAt = now),
                ),
            )
            database.clipDao().insertClipTag(ClipTagEntity(selectedId, tagId, now))
            selectedId
        }

        val initial = requireNotNull(repository.observeClipWithDetails(selectedId).first())
        assertEquals(selectedId, initial.clip.id)
        assertTrue(initial.assets.zipWithNext().all { (first, second) -> first.id < second.id })
        assertEquals(listOf("Selected"), initial.tags.map { it.name })

        storage.withDatabase { database ->
            database.clipDao().updateClip(initial.clip.copy(summary = "updated summary"))
        }
        val updated = requireNotNull(repository.observeClipWithDetails(selectedId).first { it?.clip?.summary == "updated summary" })
        assertEquals("updated summary", updated.clip.summary)
        assertEquals(null, repository.observeClipWithDetails(Long.MAX_VALUE).first())
    }

    @Test
    fun bulkTagReplacementUpdatesEverySelectedClipTogether() = runBlocking {
        val now = Instant.now().toString()
        val fixture = storage.withDatabase { database ->
            val firstClipId = database.clipDao().insertClip(clip("bulk-first"))
            val secondClipId = database.clipDao().insertClip(clip("bulk-second"))
            val untouchedClipId = database.clipDao().insertClip(clip("bulk-untouched"))
            val oldTagId = database.tagDao().insertTag(TagEntity(name = "Old", createdAt = now, updatedAt = now))
            val newTagId = database.tagDao().insertTag(TagEntity(name = "New", createdAt = now, updatedAt = now))
            listOf(firstClipId, secondClipId, untouchedClipId).forEach { clipId ->
                database.clipDao().insertClipTag(ClipTagEntity(clipId, oldTagId, now))
            }
            listOf(firstClipId, secondClipId, untouchedClipId, oldTagId, newTagId)
        }
        val (firstClipId, secondClipId, untouchedClipId, oldTagId, newTagId) = fixture

        repository.applyClipTagChanges(
            clipIds = setOf(firstClipId, secondClipId),
            pendingAddTagIds = setOf(newTagId),
            pendingRemoveTagIds = setOf(oldTagId),
        )

        storage.withDatabase { database ->
            val rows = database.clipDao().clipTagsForClipIds(listOf(firstClipId, secondClipId, untouchedClipId))
                .groupBy { it.clipId }
                .mapValues { (_, values) -> values.map { it.tagId }.toSet() }
            assertEquals(setOf(newTagId), rows[firstClipId])
            assertEquals(setOf(newTagId), rows[secondClipId])
            assertEquals(setOf(oldTagId), rows[untouchedClipId])
        }
    }

    @Test
    fun bulkTagChangesRejectOverlappingPendingSetsWithoutChangingDatabase() = runBlocking {
        val now = Instant.now().toString()
        val clipId = storage.withDatabase { database ->
            val id = database.clipDao().insertClip(clip("bulk-overlap"))
            val tagId = database.tagDao().insertTag(TagEntity(name = "Overlap", createdAt = now, updatedAt = now))
            database.clipDao().insertClipTag(ClipTagEntity(id, tagId, now))
            id to tagId
        }

        val failure = runCatching {
            repository.applyClipTagChanges(setOf(clipId.first), setOf(clipId.second), setOf(clipId.second))
        }.exceptionOrNull()

        assertNotNull(failure)
        storage.withDatabase { database ->
            assertEquals(setOf(clipId.second), database.clipDao().clipTagsForClipIds(listOf(clipId.first)).map { it.tagId }.toSet())
        }
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
    fun resyncPreservesSummaryTagsAndClassificationWhileAddingOnlyNewPosts() = runBlocking {
        val now = Instant.now().toString()
        val existingId = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("100", summary = "手動概要"))
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "保護タグ", createdAt = now, updatedAt = now),
            )
            database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
            database.clipDao().upsertSyncState(SyncStateEntity(usageMonth = java.time.YearMonth.now().toString()))
            clipId
        }
        api.likedResponses += XApiResult(
            posts = listOf(post("101"), post("100", text = "API側の変更")),
            nextToken = null,
            rateLimitLimit = 75,
            rateLimitRemaining = 74,
            rateLimitReset = 1234,
        )

        repository.syncNow()

        storage.withDatabase { database ->
            val clips = database.clipDao().getActiveClips()
            assertEquals(2, clips.size)
            val existing = clips.single { it.id == existingId }
            assertEquals("手動概要", existing.summary)
            assertEquals("original", existing.text)
            assertEquals(1, database.clipDao().clipTagsForClipIds(listOf(existingId)).size)
        }
    }

    @Test
    fun resyncDoesNotReviveLocallyDeletedPosts() = runBlocking {
        val deletedId = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("150", summary = "削除前メモ"))
            val deleted = database.clipDao().getActiveClips().single { it.id == clipId }.copy(isDeleted = true)
            database.clipDao().updateClip(deleted)
            clipId
        }
        api.likedResponses += XApiResult(
            posts = listOf(post("151"), post("150", text = "APIから再登場")),
            nextToken = null,
            rateLimitLimit = 75,
            rateLimitRemaining = 73,
            rateLimitReset = 1234,
        )

        repository.syncNow()

        storage.withDatabase { database ->
            val activeIds = database.clipDao().getActiveClips().map { it.xPostId }.toSet()
            assertEquals(setOf("151"), activeIds)
            assertFalse(database.clipDao().getActiveClips().any { it.id == deletedId })
            assertEquals(2, database.clipDao().countClips())
        }
    }

    @Test
    fun paginationPersistsUsageAndDoesNotDuplicatePosts() = runBlocking {
        api.likedResponses += XApiResult(listOf(post("201")), "next-1", 75, 70, 1234)
        api.likedResponses += XApiResult(listOf(post("202"), post("201")), null, 75, 68, 1234)

        repository.syncNow()

        storage.withDatabase { database ->
            assertEquals(setOf("201", "202"), database.clipDao().getActiveClips().map { it.xPostId }.toSet())
            val state = database.clipDao().getSyncState()
            assertEquals(3, state?.monthlyFetchedCount)
            assertEquals(3L, database.clipDao().getApiUsageMonth(requireNotNull(state?.usageMonth))?.billableReadCount)
            assertEquals(3L, database.clipDao().getTotalBillableReadCount())
            assertEquals(null, state?.likedPostsNextToken)
            assertEquals(2, api.likedCalls.size)
        }
    }

    @Test
    fun randomizedPagedSyncPreservesUniquePostSetAndUsageAccounting() = runBlocking {
        val random = Random(1729)
        val expectedIds = linkedSetOf<String>()
        var expectedFetched = 0
        repeat(12) { page ->
            val uniqueIds = (0 until 8).map { index -> "property-${page.toString().padStart(2, '0')}-$index" }
            val duplicateIds = List(random.nextInt(0, 4)) { uniqueIds[random.nextInt(uniqueIds.size)] }
            val responseIds = (uniqueIds + duplicateIds).shuffled(random)
            expectedIds += responseIds
            expectedFetched += responseIds.size
            api.likedResponses += XApiResult(
                posts = responseIds.map { id -> post(id) },
                nextToken = if (page == 11) null else "property-next-$page",
                rateLimitLimit = 75,
                rateLimitRemaining = 75 - page,
                rateLimitReset = 1234L + page,
            )
        }

        repository.syncNow()

        storage.withDatabase { database ->
            val activeIds = database.clipDao().getActiveClips().map { it.xPostId }
            assertEquals(expectedIds, activeIds.toSet())
            assertEquals(expectedIds.size, activeIds.size)
            val state = database.clipDao().getSyncState()
            assertEquals(expectedFetched, state?.monthlyFetchedCount)
            assertEquals(null, state?.likedPostsNextToken)
            assertEquals(12, api.likedCalls.size)
        }
    }

    @Test
    fun unauthorizedRefreshesOnceAndRetriesWithNewToken() = runBlocking {
        api.likedResponses += XApiException(401, "expired")
        api.likedResponses += XApiResult(emptyList(), null, null, null, null)

        repository.syncNow()

        assertEquals(1, oauth.refreshCount)
        assertEquals(listOf("test-access", "refreshed-access"), api.likedCalls.map { it.accessToken })
        assertEquals("refreshed-access", settings.loadSession()?.accessToken)
    }

    @Test
    fun refreshFailureStopsAfterOneAttemptAndKeepsThePreviousSession() = runBlocking {
        api.likedResponses += XApiException(401, "expired")
        oauth.refreshFailure = IllegalStateException("refresh failed")

        val failure = runCatching { repository.syncNow() }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, oauth.refreshCount)
        assertEquals(1, api.likedCalls.size)
        assertEquals("test-access", settings.loadSession()?.accessToken)
        storage.withDatabase { database -> assertTrue(database.clipDao().getActiveClips().isEmpty()) }
    }

    @Test
    fun logoutKeepsClientIdAndClearsSessionEvenWhenRevokeFails() = runBlocking {
        api.revokeFailure = IllegalStateException("revoke failed")

        val revokeFailed = repository.logout()

        assertTrue(revokeFailed)
        assertEquals(ApiSettings("test-client"), settings.load())
        assertEquals(null, settings.loadSession())
        assertEquals(listOf("test-client:test-refresh"), api.revokeCalls)
    }

    @Test
    fun clearApiSettingsRemovesClientIdAndSessionTogether() = runBlocking {
        repository.clearApiSettings()

        assertEquals(ApiSettings(), settings.load())
        assertEquals(null, settings.loadSession())
    }

    @Test
    fun mediaGridSourceMatchesCardFilteringAndSortingForTheSameClipSet() = runBlocking {
        data class GridFixture(
            val tagId: Long,
            val lowClipId: Long,
            val highClipId: Long,
            val noMediaClipId: Long,
            val lowAssetId: Long,
            val highAssetId: Long,
        )
        val now = "2026-06-15T12:00:00Z"
        val fixture = storage.withDatabase { database ->
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "GridMatch", createdAt = now, updatedAt = now),
            )
            val lowClipId = database.clipDao().insertClip(
                ClipEntity(
                    xPostId = "grid-low",
                    authorName = "Low",
                    authorUsername = "low",
                    text = "Needle alpha",
                    postUrl = "https://x.com/low/status/grid-low",
                    xCreatedAt = now,
                    savedAt = "2026-06-15T09:00:00Z",
                    syncedAt = now,
                    likeCount = 5,
                ),
            )
            val highClipId = database.clipDao().insertClip(
                ClipEntity(
                    xPostId = "grid-high",
                    authorName = "High",
                    authorUsername = "high",
                    text = "Needle beta",
                    postUrl = "https://x.com/high/status/grid-high",
                    xCreatedAt = now,
                    savedAt = "2026-06-15T10:00:00Z",
                    syncedAt = now,
                    likeCount = 10,
                ),
            )
            val noMediaClipId = database.clipDao().insertClip(
                ClipEntity(
                    xPostId = "grid-no-media",
                    authorName = "Plain",
                    authorUsername = "plain",
                    text = "Needle gamma",
                    postUrl = "https://x.com/plain/status/grid-no-media",
                    xCreatedAt = now,
                    savedAt = "2026-06-15T11:00:00Z",
                    syncedAt = now,
                    likeCount = 1,
                ),
            )
            listOf(lowClipId, highClipId, noMediaClipId).forEach { clipId ->
                database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
            }
            database.clipDao().insertAssets(
                listOf(
                    AssetEntity(
                        clipId = lowClipId,
                        mediaKey = "grid-low-photo",
                        type = "photo",
                        remoteUrl = "https://example.test/grid-low-photo.jpg",
                        previewUrl = null,
                        localPath = null,
                        createdAt = now,
                    ),
                    AssetEntity(
                        clipId = highClipId,
                        mediaKey = "grid-high-photo",
                        type = "photo",
                        remoteUrl = "https://example.test/grid-high-photo.jpg",
                        previewUrl = null,
                        localPath = null,
                        createdAt = now,
                    ),
                ),
            )
            val lowAssetId = database.clipDao().assetsForClipIds(listOf(lowClipId)).single().id
            val highAssetId = database.clipDao().assetsForClipIds(listOf(highClipId)).single().id
            GridFixture(tagId, lowClipId, highClipId, noMediaClipId, lowAssetId, highAssetId)
        }

        val hierarchy = repository.tagHierarchy.first { it.tags.any { tag -> tag.tag.id == fixture.tagId } }
        val filters = TweetFilterState(
            query = "Needle",
            searchMode = SearchMode.Literal,
            searchTargets = setOf(SearchTarget.Text),
            taggedOnly = true,
            tagFilters = mapOf(TagNodeRef(TagNodeType.TAG, fixture.tagId) to TagFilterState.REQUIRED),
        )
        val sort = ClassifiedSortState(
            baseOrder = ClassifiedSortBase.LikeCount,
            likeCountDescending = true,
        )
        val cardClips = repository.clipsWithDetails.first { it.size == 3 }
        val mediaClips = repository.mediaGridSource.first { it.size == 3 }

        val cardResult = sortClipsForDisplay(filterClipsForSearch(cardClips, hierarchy, filters), hierarchy, filters, sort)
        val mediaResult = sortClipsForDisplay(filterClipsForSearch(mediaClips, hierarchy, filters), hierarchy, filters, sort)

        assertEquals(cardResult.map { it.clip.id }, mediaResult.map { it.clip.id })
        assertEquals(listOf(fixture.highClipId, fixture.lowClipId, fixture.noMediaClipId), mediaResult.map { it.clip.id })
        assertEquals(listOf(fixture.highAssetId, fixture.lowAssetId), buildMediaGridEntries(mediaResult).map { it.assetId })
    }

    @Test
    fun failedSecondPagePersistsContinuationAndNextRunResumesWithoutDuplicating() = runBlocking {
        api.likedResponses += XApiResult(listOf(post("251")), "next-251", 75, 74, 1234)
        api.likedResponses += XApiException(500, "second page failed")

        assertNotNull(runCatching { repository.syncNow() }.exceptionOrNull())
        storage.withDatabase { database ->
            assertEquals(listOf("251"), database.clipDao().getActiveClips().map { it.xPostId })
            assertEquals("next-251", database.clipDao().getSyncState()?.likedPostsNextToken)
        }

        api.likedResponses += XApiResult(listOf(post("252"), post("251")), null, 75, 72, 1234)
        val resumeCallIndex = api.likedCalls.size
        repository.syncNow()

        assertEquals("next-251", api.likedCalls[resumeCallIndex].paginationToken)
        assertEquals(null, api.likedCalls.last().paginationToken)
        storage.withDatabase { database ->
            assertEquals(setOf("251", "252"), database.clipDao().getActiveClips().map { it.xPostId }.toSet())
            assertEquals(null, database.clipDao().getSyncState()?.likedPostsNextToken)
        }
    }

    @Test
    fun rateLimitFailureIsPersistedAndDoesNotRetryForever() = runBlocking {
        api.likedResponses += XApiException(429, "limited", 75, 0, 9999)

        val failure = runCatching { repository.syncNow() }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, api.likedCalls.size)
        storage.withDatabase { database ->
            val state = database.clipDao().getSyncState()
            assertEquals(0, state?.rateLimitRemaining)
            assertEquals(9999L, state?.rateLimitResetEpochSeconds)
        }
    }

    @Test
    fun likedPostSyncFailuresDoNotIncrementApiUsage() = runBlocking {
        val failures = listOf(
            XApiException(401, "expired"),
            XApiException(403, "forbidden"),
            XApiException(429, "limited", 75, 0, 9999),
            XApiException(500, "server"),
            IllegalStateException("parse failed"),
        )

        failures.forEach { failure ->
            storage.withDatabase { database -> database.clearAllTables() }
            settings.saveSession(testSession())
            oauth.refreshFailure = if (failure is XApiException && failure.statusCode == 401) {
                IllegalStateException("refresh failed")
            } else {
                null
            }
            api.likedResponses.clear()
            api.likedResponses += failure

            assertNotNull(runCatching { repository.syncNow() }.exceptionOrNull())

            storage.withDatabase { database ->
                assertTrue(database.clipDao().getActiveClips().isEmpty())
                assertEquals(0L, database.clipDao().getTotalBillableReadCount())
                assertEquals(0, database.clipDao().getSyncState()?.monthlyFetchedCount ?: 0)
            }
        }
        oauth.refreshFailure = null
    }

    @Test
    fun monthlyStopLinePreventsAnyApiCall() = runBlocking {
        storage.withDatabase { database ->
            database.clipDao().upsertSyncState(
                SyncStateEntity(
                    monthlyFetchedCount = 10,
                    monthlyBudgetLimit = 10,
                    monthlyStopLimit = 10,
                    usageMonth = java.time.YearMonth.now().toString(),
                ),
            )
        }

        assertTrue(repository.syncNow().contains("同期しませんでした"))
        assertTrue(api.likedCalls.isEmpty())
    }

    @Test
    fun emptyPageWithUnexpectedNextTokenStopsWithoutLooping() = runBlocking {
        api.likedResponses += XApiResult(emptyList(), "unexpected-next", 75, 74, 1234)

        repository.syncNow()

        assertEquals(1, api.likedCalls.size)
        storage.withDatabase { database ->
            assertEquals(null, database.clipDao().getSyncState()?.likedPostsNextToken)
            assertTrue(database.clipDao().getActiveClips().isEmpty())
        }
    }

    @Test
    fun likeCountRefreshAddsApiUsageWithoutIncreasingSavedCount() = runBlocking {
        val firstId = storage.withDatabase { database -> database.clipDao().insertClip(clip("601")) }
        val secondId = storage.withDatabase { database -> database.clipDao().insertClip(clip("602")) }
        api.metricResponses += XPostMetricsResult(
            posts = listOf(XPostMetric("601", 11), XPostMetric("602", 22)),
            errors = emptyList(),
            rateLimitLimit = 75,
            rateLimitRemaining = 73,
            rateLimitReset = 1234,
        )

        val message = repository.refreshLikeCounts()

        assertTrue(message.contains("取得成功: 2件"))
        assertEquals(setOf("601", "602"), api.metricCalls.single().postIds.toSet())
        storage.withDatabase { database ->
            assertEquals(2, database.clipDao().countActiveClips())
            val clips = database.clipDao().getActiveClips().associateBy { it.id }
            assertEquals(11L, clips.getValue(firstId).likeCount)
            assertEquals(22L, clips.getValue(secondId).likeCount)
            val state = database.clipDao().getSyncState()
            assertEquals(2, state?.monthlyFetchedCount)
            assertEquals(2L, database.clipDao().getApiUsageMonth(requireNotNull(state?.usageMonth))?.billableReadCount)
            assertEquals(2L, database.clipDao().getTotalBillableReadCount())
        }
    }

    @Test
    fun likeCountRefreshFailuresDoNotIncrementApiUsage() = runBlocking {
        val failures = listOf(
            XApiException(401, "expired"),
            XApiException(403, "forbidden"),
            XApiException(429, "limited"),
            XApiException(500, "server"),
            IllegalStateException("parse failed"),
        )

        failures.forEachIndexed { index, failure ->
            storage.withDatabase { database ->
                database.clearAllTables()
                database.clipDao().insertClip(clip("${700 + index}"))
            }
            settings.saveSession(testSession())
            api.metricResponses.clear()
            api.metricResponses += failure

            repository.refreshLikeCounts()

            storage.withDatabase { database ->
                assertEquals(0L, database.clipDao().getTotalBillableReadCount())
                assertEquals(0, database.clipDao().getSyncState()?.monthlyFetchedCount ?: 0)
            }
        }
    }

    @Test
    fun settingsSnapshotUsesApiUsageMonthHistoryForCumulativeUsage() = runBlocking {
        val now = Instant.now().toString()
        storage.withDatabase { database ->
            database.clipDao().incrementApiUsageMonth("2026-01", 4, now)
            database.clipDao().incrementApiUsageMonth("2026-02", 5, now)
        }

        val snapshot = repository.loadSettingsSnapshot()

        assertEquals(9L, snapshot.cumulativeApiUsage)
    }

    @Test
    fun settingsSnapshotCountsOnlyActiveClipsAndExistingManagedImages() = runBlocking {
        val now = Instant.now().toString()
        val image = File(storage.imageDirectory(), "snapshot-counted.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        storage.withDatabase { database ->
            val activeId = database.clipDao().insertClip(clip("801"))
            val deletedId = database.clipDao().insertClip(clip("802"))
            val deleted = database.clipDao().getActiveClips().single { it.id == deletedId }.copy(isDeleted = true)
            database.clipDao().updateClip(deleted)
            database.clipDao().insertAssets(
                listOf(
                    AssetEntity(
                        clipId = activeId,
                        mediaKey = "counted",
                        type = "photo",
                        remoteUrl = null,
                        previewUrl = null,
                        localPath = image.absolutePath,
                        sizeBytes = image.length(),
                        downloadState = "downloaded",
                        createdAt = now,
                    ),
                    AssetEntity(
                        clipId = activeId,
                        mediaKey = "failed",
                        type = "photo",
                        remoteUrl = null,
                        previewUrl = null,
                        localPath = null,
                        downloadState = "failed",
                        createdAt = now,
                    ),
                    AssetEntity(
                        clipId = activeId,
                        mediaKey = "missing",
                        type = "photo",
                        remoteUrl = null,
                        previewUrl = null,
                        localPath = File(storage.imageDirectory(), "missing.webp").absolutePath,
                        sizeBytes = 99,
                        downloadState = "downloaded",
                        createdAt = now,
                    ),
                ),
            )
        }

        val snapshot = repository.loadSettingsSnapshot()

        assertEquals(1, snapshot.saveCount)
        assertEquals(1, snapshot.imageCount)
    }

    @Test
    fun scopeAndServerErrorsDoNotWritePartialPosts() = runBlocking {
        listOf(403, 500).forEach { status ->
            api.likedResponses += XApiException(status, "error-$status")
            val failure = runCatching { repository.syncNow() }.exceptionOrNull()
            assertNotNull(failure)
        }

        storage.withDatabase { database -> assertTrue(database.clipDao().getActiveClips().isEmpty()) }
        storage.withDatabase { database -> assertEquals(0L, database.clipDao().getTotalBillableReadCount()) }
        assertEquals(2, api.likedCalls.size)
    }

    @Test
    fun photoIsStoredAsWebpAndExistingPostIsNotDownloadedTwice() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val png = pngBytes(android.graphics.Color.RED)
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(png)))
            val media = XMedia("media-1", "photo", server.url("/photo.png").toString(), null, 2, 2)
            api.likedResponses += XApiResult(listOf(post("301", media = listOf(media))), null, null, null, null)
            api.likedResponses += XApiResult(listOf(post("301", media = listOf(media))), null, null, null, null)

            repository.syncNow()
            repository.syncNow()

            storage.withDatabase { database ->
                val assets = database.clipDao().getAllAssets()
                assertEquals(1, assets.size)
                val local = File(requireNotNull(assets.single().localPath))
                assertTrue(local.exists())
                assertTrue(local.extension.equals("webp", ignoreCase = true))
                assertNotNull(BitmapFactory.decodeFile(local.absolutePath))
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun videoAndAnimatedGifThumbnailsAreStoredAsWebpFromPreviewImagesOnly() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pngBytes(android.graphics.Color.MAGENTA))))
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pngBytes(android.graphics.Color.CYAN))))
            val video = XMedia(
                mediaKey = "video-media",
                type = "video",
                url = server.url("/video-body.mp4").toString(),
                previewImageUrl = server.url("/video-thumb.png").toString(),
                width = 1920,
                height = 1080,
            )
            val gif = XMedia(
                mediaKey = "gif-media",
                type = "animated_gif",
                url = server.url("/gif-body.mp4").toString(),
                previewImageUrl = server.url("/gif-thumb.png").toString(),
                width = 640,
                height = 480,
            )
            api.likedResponses += XApiResult(listOf(post("304", media = listOf(video, gif))), null, null, null, null)

            repository.syncNow()

            storage.withDatabase { database ->
                val assets = database.clipDao().getAllAssets().sortedBy { it.mediaKey }
                assertEquals(2, assets.size)
                assets.forEach { asset ->
                    assertEquals("video_thumbnail", asset.type)
                    assertEquals("downloaded", asset.downloadState)
                    val local = File(requireNotNull(asset.localPath))
                    assertTrue(local.exists())
                    assertTrue(local.extension.equals("webp", ignoreCase = true))
                    assertNotNull(BitmapFactory.decodeFile(local.absolutePath))
                    assertTrue(requireNotNull(asset.sizeBytes) > 0)
                }
            }
            assertEquals(2, server.requestCount)
            assertEquals(
                setOf("/gif-thumb.png", "/video-thumb.png"),
                setOf(requireNotNull(server.takeRequest().path), requireNotNull(server.takeRequest().path)),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun videoThumbnailIsStoredAsWebpOnNonWifi() = runBlocking {
        val repo = newRepository()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pngBytes(android.graphics.Color.YELLOW))))
            val media = XMedia(
                mediaKey = "nonwifi-video",
                type = "video",
                url = server.url("/video-body.mp4").toString(),
                previewImageUrl = server.url("/video-thumb.png").toString(),
                width = 1920,
                height = 1080,
            )
            api.likedResponses += XApiResult(listOf(post("305", media = listOf(media))), null, null, null, null)

            repo.syncNow()

            storage.withDatabase { database ->
                val asset = database.clipDao().getAllAssets().single()
                assertEquals("video_thumbnail", asset.type)
                val local = File(requireNotNull(asset.localPath))
                assertTrue(local.exists())
                assertTrue(local.extension.equals("webp", ignoreCase = true))
                assertTrue(requireNotNull(asset.sizeBytes) > 0)
                assertEquals("downloaded", asset.downloadState)
            }
            assertEquals(1, server.requestCount)
            assertEquals("/video-thumb.png", requireNotNull(server.takeRequest().path))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun animatedGifThumbnailIsStoredAsWebpOnNonWifi() = runBlocking {
        val repo = newRepository()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pngBytes(android.graphics.Color.CYAN))))
            val media = XMedia(
                mediaKey = "nonwifi-gif",
                type = "animated_gif",
                url = server.url("/gif-body.mp4").toString(),
                previewImageUrl = server.url("/gif-thumb.png").toString(),
                width = 480,
                height = 270,
            )
            api.likedResponses += XApiResult(listOf(post("305-gif", media = listOf(media))), null, null, null, null)

            repo.syncNow()

            storage.withDatabase { database ->
                val asset = database.clipDao().getAllAssets().single()
                assertEquals("video_thumbnail", asset.type)
                val local = File(requireNotNull(asset.localPath))
                assertTrue(local.exists())
                assertTrue(local.extension.equals("webp", ignoreCase = true))
                assertTrue(requireNotNull(asset.sizeBytes) > 0)
                assertEquals("downloaded", asset.downloadState)
            }
            assertEquals(1, server.requestCount)
            assertEquals("/gif-thumb.png", requireNotNull(server.takeRequest().path))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun previewImageFailureDoesNotFailThePostAndMarksTheVideoThumbnailAsFailed() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not an image"))
            val media = XMedia(
                mediaKey = "broken-video-thumb",
                type = "video",
                url = server.url("/video-body.mp4").toString(),
                previewImageUrl = server.url("/broken-thumb.png").toString(),
                width = 1920,
                height = 1080,
            )
            api.likedResponses += XApiResult(listOf(post("306", media = listOf(media))), null, null, null, null)

            repository.syncNow()

            storage.withDatabase { database ->
                assertEquals(1, database.clipDao().getActiveClips().count { it.xPostId == "306" })
                val asset = database.clipDao().getAllAssets().single()
                assertEquals("video_thumbnail", asset.type)
                assertEquals("failed", asset.downloadState)
                assertEquals(null, asset.localPath)
            }
            assertEquals(1, server.requestCount)
            assertEquals("/broken-thumb.png", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun multiplePhotosStoreSeparateWebpFilesWhileBrokenPhotoIsRecordedAsFailed() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pngBytes(android.graphics.Color.BLUE))))
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pngBytes(android.graphics.Color.GREEN))))
            server.enqueue(MockResponse().setResponseCode(200).setBody("not an image"))
            val media = listOf(
                XMedia("multi-1", "photo", server.url("/one.png").toString(), null, 2, 2),
                XMedia("multi-2", "photo", server.url("/two.png").toString(), null, 2, 2),
                XMedia("multi-broken", "photo", server.url("/broken.png").toString(), null, 2, 2),
            )
            api.likedResponses += XApiResult(listOf(post("303", media = media)), null, null, null, null)

            repository.syncNow()

            storage.withDatabase { database ->
                val assets = database.clipDao().getAllAssets().sortedBy { it.mediaKey }
                assertEquals(3, assets.size)
                val stored = assets.filter { it.downloadState == "downloaded" }
                val failed = assets.single { it.mediaKey == "multi-broken" }
                assertEquals(setOf("multi-1", "multi-2"), stored.map { it.mediaKey }.toSet())
                assertEquals(2, stored.mapNotNull { it.localPath }.toSet().size)
                stored.forEach { asset ->
                    val local = File(requireNotNull(asset.localPath))
                    assertTrue(local.exists())
                    assertTrue(local.extension.equals("webp", ignoreCase = true))
                    assertTrue(requireNotNull(asset.sizeBytes) > 0)
                    assertNotNull(BitmapFactory.decodeFile(local.absolutePath))
                }
                assertEquals("failed", failed.downloadState)
                assertEquals(null, failed.localPath)
            }
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun imageDownloadFailureKeepsThePostAndRecordsFailedAsset() = runBlocking {
        val media = XMedia("broken-media", "photo", "http://127.0.0.1:1/unavailable.png", null, 2, 2)
        api.likedResponses += XApiResult(listOf(post("302", media = listOf(media))), null, null, null, null)

        repository.syncNow()

        storage.withDatabase { database ->
            assertEquals(1, database.clipDao().getActiveClips().count { it.xPostId == "302" })
            val asset = database.clipDao().getAllAssets().single()
            assertEquals("failed", asset.downloadState)
            assertEquals(null, asset.localPath)
        }
    }

    @Test
    fun invalidStorageMoveKeepsCurrentDatabaseAndImages() = runBlocking {
        val clipId = storage.withDatabase { it.clipDao().insertClip(clip("350")) }
        val image = File(storage.imageDirectory(), "protected.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val locationBefore = storage.state.value.currentLocationId

        val result = repository.movePostStorage("missing-target")

        assertTrue(result.isFailure)
        assertEquals(locationBefore, storage.state.value.currentLocationId)
        assertTrue(image.exists())
        storage.withDatabase { database -> assertTrue(database.clipDao().getActiveClips().any { it.id == clipId }) }
    }

    @Test
    fun tagAndGroupMoveOperationsPersistParentsAndSiblingOrder() = runBlocking {
        val now = Instant.now().toString()
        val fixture = storage.withDatabase { database ->
            val alphaGroup = database.tagDao().insertGroup(TagGroupEntity(name = "Alpha", sortOrder = 0, createdAt = now, updatedAt = now))
            val betaGroup = database.tagDao().insertGroup(TagGroupEntity(name = "Beta", sortOrder = 1, createdAt = now, updatedAt = now))
            val gammaGroup = database.tagDao().insertGroup(TagGroupEntity(name = "Gamma", parentGroupId = alphaGroup, sortOrder = 0, createdAt = now, updatedAt = now))
            val alphaTag = database.tagDao().insertTag(TagEntity(name = "AlphaTag", parentGroupId = alphaGroup, sortOrder = 1, createdAt = now, updatedAt = now))
            val betaTag = database.tagDao().insertTag(TagEntity(name = "BetaTag", parentGroupId = betaGroup, sortOrder = 0, createdAt = now, updatedAt = now))
            val rootTag = database.tagDao().insertTag(TagEntity(name = "RootTag", sortOrder = 2, createdAt = now, updatedAt = now))
            MoveFixture(alphaGroup, betaGroup, gammaGroup, alphaTag, betaTag, rootTag)
        }

        repository.moveNode(TagNodeRef(TagNodeType.TAG, fixture.alphaTag), fixture.betaGroup)
        assertEquals(
            listOf("tag:${fixture.betaTag}:0", "tag:${fixture.alphaTag}:1"),
            siblingOrder(fixture.betaGroup),
        )

        repository.moveNodeToParentAtSlot(TagNodeRef(TagNodeType.TAG, fixture.alphaTag), fixture.betaGroup, 0)
        assertEquals(
            listOf("tag:${fixture.alphaTag}:0", "tag:${fixture.betaTag}:1"),
            siblingOrder(fixture.betaGroup),
        )

        repository.moveNodeToParentAt(TagNodeRef(TagNodeType.TAG, fixture.alphaTag), null, 1)
        assertEquals(null, tagParentGroupId(fixture.alphaTag))
        assertEquals(
            listOf("group:${fixture.alphaGroup}:0", "tag:${fixture.alphaTag}:1", "group:${fixture.betaGroup}:2", "tag:${fixture.rootTag}:3"),
            siblingOrder(null),
        )

        repository.moveNode(TagNodeRef(TagNodeType.GROUP, fixture.gammaGroup), fixture.betaGroup)
        assertEquals(
            listOf("tag:${fixture.betaTag}:0", "group:${fixture.gammaGroup}:1"),
            siblingOrder(fixture.betaGroup),
        )

        repository.moveNodeToParentAtSlot(TagNodeRef(TagNodeType.GROUP, fixture.gammaGroup), null, 0)
        assertEquals(null, groupParentGroupId(fixture.gammaGroup))
        assertEquals(
            listOf("group:${fixture.gammaGroup}:0", "group:${fixture.alphaGroup}:1", "tag:${fixture.alphaTag}:2", "group:${fixture.betaGroup}:3", "tag:${fixture.rootTag}:4"),
            siblingOrder(null),
        )

        repository.reorderSiblings(
            null,
            listOf(
                TagNodeRef(TagNodeType.GROUP, fixture.betaGroup),
                TagNodeRef(TagNodeType.GROUP, fixture.gammaGroup),
                TagNodeRef(TagNodeType.TAG, fixture.alphaTag),
                TagNodeRef(TagNodeType.GROUP, fixture.alphaGroup),
                TagNodeRef(TagNodeType.TAG, fixture.rootTag),
            ),
        )

        assertEquals(
            listOf("group:${fixture.betaGroup}:0", "group:${fixture.gammaGroup}:1", "tag:${fixture.alphaTag}:2", "group:${fixture.alphaGroup}:3", "tag:${fixture.rootTag}:4"),
            siblingOrder(null),
        )
        assertEquals(fixture.betaGroup, tagParentGroupId(fixture.betaTag))
        assertEquals(emptyList<String>(), siblingOrder(fixture.alphaGroup))
    }

    @Test
    fun detectOcrTextRecognizesEligibleAssetsAndSavingItPersistsTimestamp() = runBlocking {
        val now = Instant.now().toString()
        val clipId = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("900", summary = "ocr"))
            val imageDir = storage.imageDirectory()
            imageDir.mkdirs()
            val photoFile = File(imageDir, "ocr-photo.jpg").apply { writeBytes(bitmapBytes(2, 1, android.graphics.Color.RED)) }
            val thumbFile = File(imageDir, "ocr-thumb.png").apply { writeBytes(bitmapBytes(1, 2, android.graphics.Color.BLUE)) }
            database.clipDao().insertAssets(
                listOf(
                    AssetEntity(
                        clipId = clipId,
                        mediaKey = "photo",
                        type = "photo",
                        remoteUrl = "https://example.test/photo",
                        previewUrl = null,
                        localPath = photoFile.absolutePath,
                        width = 2,
                        height = 1,
                        sizeBytes = photoFile.length(),
                        downloadState = "downloaded",
                        createdAt = now,
                    ),
                    AssetEntity(
                        clipId = clipId,
                        mediaKey = "thumb",
                        type = "video_thumbnail",
                        remoteUrl = "https://example.test/thumb",
                        previewUrl = null,
                        localPath = thumbFile.absolutePath,
                        width = 1,
                        height = 2,
                        sizeBytes = thumbFile.length(),
                        downloadState = "downloaded",
                        createdAt = now,
                    ),
                ),
            )
            clipId
        }

        val clipWithDetails = storage.withDatabase { database ->
            val clip = database.clipDao().getActiveClips().single { it.id == clipId }
            val assets = database.clipDao().assetsForClipIds(listOf(clipId))
            ClipWithDetails(clip = clip, assets = assets, tags = emptyList())
        }

        val recognized = repository.detectOcrText(clipWithDetails)

        assertEquals("Landscape OCR\nSecond line\n\nPortrait OCR", recognized)

        val clip = clipWithDetails.clip
        repository.updateOcrText(clip, recognized)

        storage.withDatabase { database ->
            val updated = database.clipDao().getActiveClips().single { it.id == clipId }
            assertEquals(recognized, updated.ocrText)
            assertTrue(requireNotNull(updated.ocrUpdatedAt).isNotBlank())
        }
    }

    @Test
    fun moveClipToTrashDeletesRowsAndLocalImageFiles() = runBlocking {
        val now = Instant.now().toString()
        val imageFile = storage.imageDirectory().resolve("trash-me.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(bitmapBytes(2, 1, android.graphics.Color.GREEN))
        }
        val clipId = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("901"))
            val tagId = database.tagDao().insertTag(TagEntity(name = "tag", createdAt = now, updatedAt = now))
            database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
            database.clipDao().insertAssets(
                listOf(
                    AssetEntity(
                        clipId = clipId,
                        mediaKey = "trash-photo",
                        type = "photo",
                        remoteUrl = "https://example.test/trash",
                        previewUrl = null,
                        localPath = imageFile.absolutePath,
                        width = 2,
                        height = 1,
                        sizeBytes = imageFile.length(),
                        downloadState = "downloaded",
                        createdAt = now,
                    ),
                ),
            )
            clipId
        }
        val clip = storage.withDatabase { database -> database.clipDao().getActiveClips().single { it.id == clipId } }
        val beforeCount = storage.withDatabase { database -> database.clipDao().countClips() }

        repository.moveClipToTrash(clip)

        storage.withDatabase { database ->
            assertEquals(beforeCount - 1, database.clipDao().countClips())
            assertTrue(database.clipDao().getActiveClips().none { it.id == clipId })
            assertTrue(database.clipDao().assetsForClipIds(listOf(clipId)).isEmpty())
            assertTrue(database.clipDao().clipTagsForClipIds(listOf(clipId)).isEmpty())
        }
        assertFalse(imageFile.exists())
    }

    @Test
    fun deletingTagOrEmptyGroupNeverDeletesTheClip() = runBlocking {
        val now = Instant.now().toString()
        val (clipId, tagId, groupId) = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("401"))
            val tagId = database.tagDao().insertTag(TagEntity(name = "tag", createdAt = now, updatedAt = now))
            val groupId = database.tagDao().insertGroup(TagGroupEntity(name = "group", createdAt = now, updatedAt = now))
            database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, now))
            Triple(clipId, tagId, groupId)
        }

        repository.deleteTag(tagId)
        repository.deleteGroup(groupId)

        storage.withDatabase { database ->
            assertTrue(database.clipDao().getActiveClips().any { it.id == clipId })
            assertFalse(database.clipDao().clipTagsForClipIds(listOf(clipId)).isNotEmpty())
        }
    }

    private fun clip(id: String, summary: String = "") = ClipEntity(
        xPostId = id,
        authorName = "Author",
        authorUsername = "author",
        text = "original",
        postUrl = "https://x.com/author/status/$id",
        xCreatedAt = Instant.now().toString(),
        savedAt = Instant.now().toString(),
        syncedAt = Instant.now().toString(),
        summary = summary,
    )

    private fun post(id: String, text: String = "post-$id", media: List<XMedia> = emptyList()) = XPost(
        id = id,
        text = text,
        createdAt = Instant.now().toString(),
        authorId = "author-1",
        authorName = "Author",
        authorUsername = "author",
        media = media,
        likeCount = 1,
    )

    private fun testSession() = OAuthSession(
        accessToken = "test-access",
        refreshToken = "test-refresh",
        expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000,
        scopes = XOAuthManager.SCOPES,
        xUserId = "user-1",
        username = "tester",
        displayName = "Tester",
    )

    private fun pngBytes(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).run {
            eraseColor(color)
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
        output.toByteArray()
    }

    private fun bitmapBytes(width: Int, height: Int, color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).run {
            eraseColor(color)
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
        output.toByteArray()
    }

    private suspend fun siblingOrder(parentGroupId: Long?): List<String> = storage.withDatabase { database ->
        val groups = database.tagDao().getGroups()
            .filter { it.parentGroupId == parentGroupId }
            .map { "group:${it.id}:${it.sortOrder}" }
        val tags = database.tagDao().getTags()
            .filter { it.parentGroupId == parentGroupId }
            .map { "tag:${it.id}:${it.sortOrder}" }
        (groups + tags).sortedBy { it.substringAfterLast(':').toInt() }
    }

    private suspend fun tagParentGroupId(id: Long): Long? = storage.withDatabase { database ->
        database.tagDao().getTags().single { it.id == id }.parentGroupId
    }

    private suspend fun groupParentGroupId(id: Long): Long? = storage.withDatabase { database ->
        database.tagDao().getGroups().single { it.id == id }.parentGroupId
    }

    private data class MoveFixture(
        val alphaGroup: Long,
        val betaGroup: Long,
        val gammaGroup: Long,
        val alphaTag: Long,
        val betaTag: Long,
        val rootTag: Long,
    )
}

private data class LikedCall(val accessToken: String, val paginationToken: String?)
private data class MetricCall(val accessToken: String, val postIds: List<String>)

private class RecordingXApiGateway : XApiGateway {
    val likedResponses = ArrayDeque<Any>()
    val metricResponses = ArrayDeque<Any>()
    val likedCalls = mutableListOf<LikedCall>()
    val metricCalls = mutableListOf<MetricCall>()
    val revokeCalls = mutableListOf<String>()
    var revokeFailure: Throwable? = null

    override fun fetchLikedPosts(accessToken: String, xUserId: String, maxResults: Int, paginationToken: String?): XApiResult {
        likedCalls += LikedCall(accessToken, paginationToken)
        val response = likedResponses.removeFirstOrNull() ?: XApiResult(emptyList(), null, null, null, null)
        if (response is Throwable) throw response
        return response as XApiResult
    }

    override fun fetchPostMetrics(accessToken: String, postIds: List<String>): XPostMetricsResult {
        metricCalls += MetricCall(accessToken, postIds)
        val response = metricResponses.removeFirstOrNull() ?: XPostMetricsResult(emptyList(), emptyList(), null, null, null)
        if (response is Throwable) throw response
        return response as XPostMetricsResult
    }

    override fun getMyUser(accessToken: String) = XUser("user-1", "Tester", "tester")
    override fun revokeToken(clientId: String, token: String) {
        revokeCalls += "$clientId:$token"
        revokeFailure?.let { throw it }
    }
}

private class RecordingOAuthGateway : OAuthGateway {
    var refreshCount = 0
    var refreshFailure: Throwable? = null

    override fun createAuthorizationIntent(clientId: String) = android.content.Intent()
    override suspend fun exchangeAuthorizationResult(intent: android.content.Intent) =
        OAuthTokens("test-access", "test-refresh", System.currentTimeMillis() + 3_600_000, XOAuthManager.SCOPES)

    override suspend fun refresh(clientId: String, refreshToken: String): OAuthTokens {
        refreshCount += 1
        refreshFailure?.let { throw it }
        return OAuthTokens("refreshed-access", "refreshed-refresh", System.currentTimeMillis() + 3_600_000, XOAuthManager.SCOPES)
    }
}
