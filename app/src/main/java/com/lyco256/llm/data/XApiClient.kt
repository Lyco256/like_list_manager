package com.lyco256.llm.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class XUser(
    val id: String,
    val name: String,
    val username: String,
)

class XApiException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("X API error $statusCode: $responseBody")

data class XPost(
    val id: String,
    val text: String,
    val createdAt: String,
    val authorId: String?,
    val authorName: String,
    val authorUsername: String,
    val media: List<XMedia>,
    val likeCount: Long?,
)

data class XPostMetric(val id: String, val likeCount: Long)

data class XPostMetricError(val postId: String, val title: String, val detail: String)

data class XPostMetricsResult(
    val posts: List<XPostMetric>,
    val errors: List<XPostMetricError>,
    val rateLimitLimit: Int?,
    val rateLimitRemaining: Int?,
    val rateLimitReset: Long?,
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
        accessToken: String,
        xUserId: String,
        maxResults: Int = 50,
        paginationToken: String? = null,
    ): XApiResult {
        require(accessToken.isNotBlank()) { "Access Token is required." }
        require(xUserId.isNotBlank()) { "X User ID is required." }

        val baseUrl = "https://api.x.com/2/users/$xUserId/liked_tweets"
        val query = linkedMapOf(
            "max_results" to maxResults.coerceIn(5, 100).toString(),
            "tweet.fields" to "id,text,created_at,author_id,attachments,public_metrics",
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
            setRequestProperty("Authorization", "Bearer $accessToken")
        }

        val body = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw XApiException(connection.responseCode, error)
        }

        return XApiResult(
            posts = parseLikedPosts(body),
            nextToken = JSONObject(body).optJSONObject("meta")?.optString("next_token")?.ifBlank { null },
            rateLimitLimit = connection.getHeaderField("x-rate-limit-limit")?.toIntOrNull(),
            rateLimitRemaining = connection.getHeaderField("x-rate-limit-remaining")?.toIntOrNull(),
            rateLimitReset = connection.getHeaderField("x-rate-limit-reset")?.toLongOrNull(),
        )
    }

    fun fetchPostMetrics(accessToken: String, postIds: List<String>): XPostMetricsResult {
        require(accessToken.isNotBlank()) { "Access Token is required." }
        require(postIds.isNotEmpty() && postIds.size <= 100) { "Post IDs must contain 1 to 100 items." }
        val query = linkedMapOf(
            "ids" to postIds.joinToString(","),
            "tweet.fields" to "id,public_metrics",
        )
        val connection = (URL("https://api.x.com/2/tweets?${query.toQueryString()}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        val body = connection.readBodyOrThrow()
        val root = JSONObject(body)
        val data = root.optJSONArray("data")
        val posts = (0 until (data?.length() ?: 0)).mapNotNull { index ->
            val post = data!!.getJSONObject(index)
            val metrics = post.optJSONObject("public_metrics") ?: return@mapNotNull null
            XPostMetric(post.getString("id"), metrics.optLong("like_count"))
        }
        val errorItems = root.optJSONArray("errors")
        val errors = (0 until (errorItems?.length() ?: 0)).mapNotNull { index ->
            val error = errorItems!!.getJSONObject(index)
            val id = error.optString("resource_id").ifBlank { error.optString("value") }
            id.takeIf(String::isNotBlank)?.let {
                XPostMetricError(it, error.optString("title"), error.optString("detail"))
            }
        }
        return XPostMetricsResult(
            posts = posts,
            errors = errors,
            rateLimitLimit = connection.getHeaderField("x-rate-limit-limit")?.toIntOrNull(),
            rateLimitRemaining = connection.getHeaderField("x-rate-limit-remaining")?.toIntOrNull(),
            rateLimitReset = connection.getHeaderField("x-rate-limit-reset")?.toLongOrNull(),
        )
    }

    fun getMyUser(accessToken: String): XUser {
        val connection = (URL("https://api.x.com/2/users/me?user.fields=id,name,username").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        val body = connection.readBodyOrThrow()
        val data = JSONObject(body).getJSONObject("data")
        return XUser(
            id = data.getString("id"),
            name = data.optString("name"),
            username = data.optString("username"),
        )
    }

    fun revokeToken(clientId: String, token: String) {
        val body = linkedMapOf("client_id" to clientId, "token" to token).toQueryString()
        val connection = (URL("https://api.x.com/2/oauth2/revoke").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        connection.readBodyOrThrow()
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
                likeCount = tweet.optJSONObject("public_metrics")?.optLong("like_count"),
            )
        }
    }
}

private fun HttpURLConnection.readBodyOrThrow(): String {
    val status = responseCode
    if (status in 200..299) return inputStream.bufferedReader().use { it.readText() }
    val error = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
    throw XApiException(status, error)
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
