package com.lyco256.llm.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class XApiClientMockWebServerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: XApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = XApiClient(server.url("/2").toString().trimEnd('/'), 1_000, 1_000)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesLikedPostsWithoutIncludesOrMedia() {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"id":"1","text":"hello","created_at":"2026-01-01T00:00:00Z"}],"meta":{}}""",
            ),
        )

        val result = client.fetchLikedPosts("token", "user")

        assertEquals(1, result.posts.size)
        assertEquals("", result.posts.single().authorName)
        assertTrue(result.posts.single().media.isEmpty())
        assertNull(result.nextToken)
        assertTrue(server.takeRequest().path!!.startsWith("/2/users/user/liked_tweets"))
    }

    @Test
    fun parsesUsersAndMixedPhotoVideoGifMediaWithUnknownFields() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": [{
                    "id": "mixed-1",
                    "text": "mixed",
                    "author_id": "user-1",
                    "created_at": "2026-01-01T00:00:00Z",
                    "attachments": {"media_keys": ["p1", "v1", "g1"]},
                    "public_metrics": {"like_count": 42},
                    "future_field": {"ignored": true}
                  }],
                  "includes": {
                    "users": [{"id": "user-1", "name": "Alice", "username": "alice"}],
                    "media": [
                      {"media_key": "p1", "type": "photo", "url": "https://example.test/photo.jpg", "width": 100, "height": 80},
                      {"media_key": "v1", "type": "video", "preview_image_url": "https://example.test/video.jpg"},
                      {"media_key": "g1", "type": "animated_gif", "preview_image_url": "https://example.test/gif.jpg"}
                    ]
                  },
                  "meta": {"next_token": "next-mixed"}
                }
                """.trimIndent(),
            ),
        )

        val result = client.fetchLikedPosts("token", "user")
        val post = result.posts.single()

        assertEquals("Alice", post.authorName)
        assertEquals("alice", post.authorUsername)
        assertEquals(42L, post.likeCount)
        assertEquals("next-mixed", result.nextToken)
        assertEquals(listOf("photo", "video", "animated_gif"), post.media.map { it.type })
        assertEquals("https://example.test/photo.jpg", post.media[0].url)
        assertEquals("https://example.test/video.jpg", post.media[1].previewImageUrl)
        assertEquals("https://example.test/gif.jpg", post.media[2].previewImageUrl)
    }

    @Test
    fun exposesRateLimitHeadersOn429() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("x-rate-limit-limit", "75")
                .setHeader("x-rate-limit-remaining", "0")
                .setHeader("x-rate-limit-reset", "9999")
                .setBody("limited"),
        )

        val error = runCatching { client.fetchLikedPosts("token", "user") }.exceptionOrNull() as XApiException

        assertEquals(429, error.statusCode)
        assertEquals(75, error.rateLimitLimit)
        assertEquals(0, error.rateLimitRemaining)
        assertEquals(9999L, error.rateLimitReset)
    }

    @Test
    fun malformedJsonFailsInsteadOfSilentlySavingPartialData() {
        server.enqueue(MockResponse().setBody("{not-json"))

        val error = runCatching { client.fetchLikedPosts("token", "user") }.exceptionOrNull()

        assertTrue(error is JSONException)
    }

    @Test
    fun preserves403And500StatusForRepositoryErrorMapping() {
        listOf(403, 500).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("error-$status"))
            val error = runCatching { client.fetchLikedPosts("token", "user") }.exceptionOrNull() as XApiException
            assertEquals(status, error.statusCode)
        }
    }

    @Test
    fun timeoutFailsDeterministically() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fastClient = XApiClient(server.url("/2").toString().trimEnd('/'), 100, 100)

        val error = runCatching { fastClient.fetchLikedPosts("token", "user") }.exceptionOrNull()

        assertTrue(error is SocketTimeoutException)
    }
}
