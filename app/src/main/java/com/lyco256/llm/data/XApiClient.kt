package com.lyco256.llm.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class XPost(
    val id: String,
    val text: String,
    val createdAt: String,
    val authorId: String?,
    val authorName: String,
    val authorUsername: String,
    val media: List<XMedia>,
)

data class XMedia(
    val mediaKey: String,
    val type: String,
    val url: String?,
    val previewImageUrl: String?,
    val width: Int?,
    val height: Int?,
)

data class XApiResult(
    val posts: List<XPost>,
    val nextToken: String?,
    val rateLimitLimit: Int?,
    val rateLimitRemaining: Int?,
    val rateLimitReset: Long?,
)

class XApiClient {
    fun fetchLikedPosts(
        settings: ApiSettings,
        maxResults: Int = 50,
        paginationToken: String? = null,
    ): XApiResult {
        require(settings.xUserId.isNotBlank()) { "X User ID is required." }
        require(settings.apiKey.isNotBlank()) { "OAuth 1.0a API Key is required." }
        require(settings.apiKeySecret.isNotBlank()) { "OAuth 1.0a API Key Secret is required." }
        require(settings.accessToken.isNotBlank()) { "OAuth 1.0a Access Token is required." }
        require(settings.accessTokenSecret.isNotBlank()) { "OAuth 1.0a Access Token Secret is required." }

        val baseUrl = "https://api.x.com/2/users/${settings.xUserId}/liked_tweets"
        val query = linkedMapOf(
            "max_results" to maxResults.coerceIn(5, 100).toString(),
            "tweet.fields" to "id,text,created_at,author_id,attachments",
            "expansions" to "attachments.media_keys,author_id",
            "media.fields" to "media_key,type,url,preview_image_url,width,height",
            "user.fields" to "id,name,username",
        )
        paginationToken?.let { query["pagination_token"] = it }
        val url = URL("$baseUrl?${query.toQueryString()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", oauth1Header("GET", baseUrl, query, settings))
        }

        val body = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("X API error ${connection.responseCode}: $error")
        }

        return XApiResult(
            posts = parseLikedPosts(body),
            nextToken = JSONObject(body).optJSONObject("meta")?.optString("next_token")?.ifBlank { null },
            rateLimitLimit = connection.getHeaderField("x-rate-limit-limit")?.toIntOrNull(),
            rateLimitRemaining = connection.getHeaderField("x-rate-limit-remaining")?.toIntOrNull(),
            rateLimitReset = connection.getHeaderField("x-rate-limit-reset")?.toLongOrNull(),
        )
    }

    private fun oauth1Header(
        method: String,
        baseUrl: String,
        query: Map<String, String>,
        settings: ApiSettings,
    ): String {
        val oauth = linkedMapOf(
            "oauth_consumer_key" to settings.apiKey,
            "oauth_nonce" to UUID.randomUUID().toString().replace("-", ""),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_token" to settings.accessToken,
            "oauth_version" to "1.0",
        )
        val signatureParams = (query + oauth).toSortedMap()
        val base = listOf(
            method.uppercase(),
            baseUrl.percentEncode(),
            signatureParams.toQueryString().percentEncode(),
        ).joinToString("&")
        val signingKey = "${settings.apiKeySecret.percentEncode()}&${settings.accessTokenSecret.percentEncode()}"
        val signature = hmacSha1(base, signingKey)
        return (oauth + ("oauth_signature" to signature))
            .map { (key, value) -> "${key.percentEncode()}=\"${value.percentEncode()}\"" }
            .joinToString(prefix = "OAuth ", separator = ", ")
    }

    private fun parseLikedPosts(body: String): List<XPost> {
        val root = JSONObject(body)
        val includes = root.optJSONObject("includes")
        val users = includes?.optJSONArray("users").toMapById("id")
        val media = includes?.optJSONArray("media").toMapById("media_key")
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { index ->
            val tweet = data.getJSONObject(index)
            val authorId = tweet.optString("author_id").ifBlank { null }
            val user = users[authorId]
            val keys = tweet.optJSONObject("attachments")?.optJSONArray("media_keys")
            val tweetMedia = (0 until (keys?.length() ?: 0)).mapNotNull { keyIndex ->
                val mediaItem = media[keys?.getString(keyIndex)]
                mediaItem?.let {
                    XMedia(
                        mediaKey = it.optString("media_key"),
                        type = it.optString("type"),
                        url = it.optString("url").ifBlank { null },
                        previewImageUrl = it.optString("preview_image_url").ifBlank { null },
                        width = if (it.has("width")) it.optInt("width") else null,
                        height = if (it.has("height")) it.optInt("height") else null,
                    )
                }
            }
            XPost(
                id = tweet.getString("id"),
                text = tweet.optString("text"),
                createdAt = tweet.optString("created_at"),
                authorId = authorId,
                authorName = user?.optString("name").orEmpty(),
                authorUsername = user?.optString("username").orEmpty(),
                media = tweetMedia,
            )
        }
    }
}

private fun org.json.JSONArray?.toMapById(key: String): Map<String, JSONObject> {
    if (this == null) return emptyMap()
    return (0 until length())
        .map { getJSONObject(it) }
        .associateBy { it.optString(key) }
}

private fun Map<String, String>.toQueryString(): String =
    entries.joinToString("&") { (key, value) -> "${key.percentEncode()}=${value.percentEncode()}" }

private fun String.percentEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")

private fun hmacSha1(value: String, key: String): String {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
    return android.util.Base64.encodeToString(
        mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
        android.util.Base64.NO_WRAP,
    )
}
