package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import kotlinx.coroutines.runBlocking
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
        repository = ClipRepository(context, storage, settings, oauth, api)
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
    fun paginationPersistsUsageAndDoesNotDuplicatePosts() = runBlocking {
        api.likedResponses += XApiResult(listOf(post("201")), "next-1", 75, 70, 1234)
        api.likedResponses += XApiResult(listOf(post("202"), post("201")), null, 75, 68, 1234)

        repository.syncNow()

        storage.withDatabase { database ->
            assertEquals(setOf("201", "202"), database.clipDao().getActiveClips().map { it.xPostId }.toSet())
            val state = database.clipDao().getSyncState()
            assertEquals(3, state?.monthlyFetchedCount)
            assertEquals(null, state?.likedPostsNextToken)
            assertEquals(2, api.likedCalls.size)
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
    fun scopeAndServerErrorsDoNotWritePartialPosts() = runBlocking {
        listOf(403, 500).forEach { status ->
            api.likedResponses += XApiException(status, "error-$status")
            val failure = runCatching { repository.syncNow() }.exceptionOrNull()
            assertNotNull(failure)
        }

        storage.withDatabase { database -> assertTrue(database.clipDao().getActiveClips().isEmpty()) }
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

    private fun pngBytes(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).run {
            eraseColor(color)
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
        output.toByteArray()
    }
}

private data class LikedCall(val accessToken: String, val paginationToken: String?)

private class RecordingXApiGateway : XApiGateway {
    val likedResponses = ArrayDeque<Any>()
    val likedCalls = mutableListOf<LikedCall>()

    override fun fetchLikedPosts(accessToken: String, xUserId: String, maxResults: Int, paginationToken: String?): XApiResult {
        likedCalls += LikedCall(accessToken, paginationToken)
        val response = likedResponses.removeFirstOrNull() ?: XApiResult(emptyList(), null, null, null, null)
        if (response is Throwable) throw response
        return response as XApiResult
    }

    override fun fetchPostMetrics(accessToken: String, postIds: List<String>) =
        XPostMetricsResult(emptyList(), emptyList(), null, null, null)

    override fun getMyUser(accessToken: String) = XUser("user-1", "Tester", "tester")
    override fun revokeToken(clientId: String, token: String) = Unit
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
