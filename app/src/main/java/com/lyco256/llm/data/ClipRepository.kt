package com.lyco256.llm.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class ClipRepository(
    private val context: Context,
    private val postStorageManager: PostStorageManager,
    private val apiSettingsStore: ApiSettingsStore,
    private val xOAuthManager: XOAuthManager,
) {
    private val xApiClient = XApiClient()

    val apiSettings: ApiSettings
        get() = apiSettingsStore.load()

    suspend fun loadApiSettings(): ApiSettings = withContext(Dispatchers.IO) {
        apiSettingsStore.load()
    }

    suspend fun loadOAuthSession(): OAuthSession? = withContext(Dispatchers.IO) {
        apiSettingsStore.loadSession()
    }

    fun createAuthorizationIntent(): Intent = xOAuthManager.createAuthorizationIntent(apiSettingsStore.load().clientId)

    suspend fun completeAuthorization(intent: Intent): OAuthSession = withContext(Dispatchers.IO) {
        val tokens = xOAuthManager.exchangeAuthorizationResult(intent)
        val user = xApiClient.getMyUser(tokens.accessToken)
        OAuthSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtEpochMillis = tokens.expiresAtEpochMillis,
            scopes = tokens.scopes,
            xUserId = user.id,
            username = user.username,
            displayName = user.name,
        ).also(apiSettingsStore::saveSession)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val settings = apiSettingsStore.load()
        val session = apiSettingsStore.loadSession()
        if (settings.clientId.isNotBlank() && session != null) {
            runCatching { xApiClient.revokeToken(settings.clientId, session.refreshToken ?: session.accessToken) }
        }
        apiSettingsStore.clearSession()
    }

    val storageState = postStorageManager.state

    val syncState: Flow<SyncStateEntity?> = postStorageManager.database.flatMapLatest { database ->
        database?.clipDao()?.observeSyncState() ?: flowOf(null)
    }

    val tagsWithCount: Flow<List<TagWithCount>> = postStorageManager.database.flatMapLatest { database ->
        if (database == null) return@flatMapLatest flowOf(emptyList())
        val tagDao = database.tagDao()
        combine(tagDao.observeTags(), tagDao.observeTagCounts()) { tags, counts ->
            val countMap = counts.associate { it.tagId to it.count }
            tags.map { TagWithCount(it, countMap[it.id] ?: 0) }
        }
    }

    val clipsWithDetails: Flow<List<ClipWithDetails>> = postStorageManager.database.flatMapLatest { database ->
        if (database == null) return@flatMapLatest flowOf(emptyList())
        val clipDao = database.clipDao()
        val tagDao = database.tagDao()
        combine(
            clipDao.observeActiveClips(),
            tagDao.observeTags(),
            clipDao.observeAssets(),
            clipDao.observeClipTags(),
        ) { clips, tags, assets, clipTags ->
            val ids = clips.map { it.id }.toSet()
            val visibleAssets = assets.filter { it.clipId in ids }
            val visibleClipTags = clipTags.filter { it.clipId in ids }
            val tagsById = tags.associateBy { it.id }
            val assetsByClip = visibleAssets.groupBy { it.clipId }
            val tagIdsByClip = visibleClipTags.groupBy { it.clipId }
            clips.map { clip ->
                ClipWithDetails(
                    clip = clip,
                    assets = assetsByClip[clip.id].orEmpty(),
                    tags = tagIdsByClip[clip.id].orEmpty().mapNotNull { tagsById[it.tagId] },
                )
            }
        }
    }

    suspend fun refreshStorageLocations() = withContext(Dispatchers.IO) {
        postStorageManager.refreshLocations()
    }

    suspend fun estimateStorageMove(targetId: String): PostStorageEstimate = withContext(Dispatchers.IO) {
        postStorageManager.estimateMove(targetId)
    }

    suspend fun movePostStorage(targetId: String): Result<Unit> = withContext(Dispatchers.IO) {
        postStorageManager.moveTo(targetId)
    }

    suspend fun saveApiSettings(settings: ApiSettings) = withContext(Dispatchers.IO) {
        apiSettingsStore.save(settings)
    }

    suspend fun clearApiSettings() = withContext(Dispatchers.IO) {
        apiSettingsStore.clear()
    }

    suspend fun ensureSeedData() = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
        val clipDao = database.clipDao()
        val tagDao = database.tagDao()
        if (clipDao.getSyncState() == null) {
            clipDao.upsertSyncState(SyncStateEntity(usageMonth = YearMonth.now().toString()))
        }
        if (clipDao.countClips() > 0) return@withDatabase
        val now = Instant.now().toString()
        val sampleTags = emptyList<TagEntity>()
        sampleTags.forEach { tagDao.insertTag(it) }

        val samples = listOf(
            ClipEntity(
                xPostId = "demo-1001",
                authorName = "robotics notes",
                authorUsername = "robot_notes",
                text = "ROS2のnavigation stackをあとで読み返したい。画像グリッドとメモ検索の確認用サンプル。",
                postUrl = "https://x.com/robot_notes/status/demo-1001",
                xCreatedAt = now,
                savedAt = now,
                syncedAt = now,
                summary = "ROS2ナビゲーション調査候補",
            ),
            ClipEntity(
                xPostId = "demo-1002",
                authorName = "design pocket",
                authorUsername = "design_pocket",
                text = "ダークUIでチップを並べる時の見え方。本文だけの投稿も保存する。",
                postUrl = "https://x.com/design_pocket/status/demo-1002",
                xCreatedAt = now,
                savedAt = now,
                syncedAt = now,
            ),
            ClipEntity(
                xPostId = "demo-1003",
                authorName = "paper finder",
                authorUsername = "paper_finder",
                text = "動画付き投稿のサムネ保存を想定したカード。動画本体はMVPでは保存しない。",
                postUrl = "https://x.com/paper_finder/status/demo-1003",
                xCreatedAt = now,
                savedAt = now,
                syncedAt = now,
            ),
        )

        samples.forEachIndexed { index, clip ->
            val id = clipDao.insertClip(clip)
            if (id > 0 && index != 1) {
                clipDao.insertAssets(
                    listOf(
                        AssetEntity(
                            clipId = id,
                            mediaKey = "demo-media-$index",
                            type = if (index == 2) "video_thumbnail" else "photo",
                            remoteUrl = "https://picsum.photos/seed/llm-$index/900/700",
                            previewUrl = "https://picsum.photos/seed/llm-$index/450/350",
                            createdAt = now,
                        ),
                    ),
                )
            }
        }
        }
    }

    suspend fun syncNow(): String = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
        val clipDao = database.clipDao()
        val session = validSession() ?: return@withDatabase "X API設定からXにログインしてください"

        val currentMonth = YearMonth.now().toString()
        val previousRaw = clipDao.getSyncState() ?: SyncStateEntity()
        val previous = if (previousRaw.usageMonth != null && previousRaw.usageMonth != currentMonth) {
            previousRaw.copy(
                monthlyFetchedCount = 0,
                usageMonth = currentMonth,
            )
        } else {
            previousRaw.copy(usageMonth = previousRaw.usageMonth ?: currentMonth)
        }
        if (previous.monthlyFetchedCount >= previous.monthlyStopLimit) {
            return@withDatabase "月間停止ラインに達しているため同期しませんでした。"
        }

        val now = Instant.now().toString()
        val remainingBudget = (previous.monthlyBudgetLimit - previous.monthlyFetchedCount).coerceAtLeast(0)
        if (remainingBudget <= 0) {
            return@withDatabase "月間取得上限に達しているため同期しませんでした。"
        }

        var paginationToken: String? = null
        var fetched = 0
        var inserted = 0
        var lastResult: XApiResult? = null
        val isWifi = context.isWifiConnected()

        do {
            val maxResults = minOf(100, remainingBudget - fetched)
            if (maxResults <= 0) break

            val result = try {
                xApiClient.fetchLikedPosts(
                    accessToken = session.accessToken,
                    xUserId = session.xUserId,
                    maxResults = maxResults,
                    paginationToken = paginationToken,
                )
            } catch (error: XApiException) {
                if (error.statusCode == 401) apiSettingsStore.clearSession()
                throw IllegalStateException(error.toUserMessage(), error)
            }
            lastResult = result
            fetched += result.posts.size

            result.posts.forEach { post ->
                val clipId = clipDao.insertClip(
                    ClipEntity(
                        xPostId = post.id,
                        authorId = post.authorId,
                        authorName = post.authorName.ifBlank { "unknown" },
                        authorUsername = post.authorUsername.ifBlank { "unknown" },
                        text = post.text,
                        postUrl = "https://x.com/${post.authorUsername}/status/${post.id}",
                        xCreatedAt = post.createdAt,
                        savedAt = now,
                        syncedAt = now,
                    ),
                )
                if (clipId > 0) {
                    inserted += 1
                    clipDao.insertAssets(
                        post.media.mapNotNull { media ->
                            createAssetForMedia(
                                clipId = clipId,
                                postId = post.id,
                                media = media,
                                now = now,
                                canDownloadVideoThumb = isWifi,
                            )
                        },
                    )
                }
            }
            paginationToken = result.nextToken
        } while (paginationToken != null && fetched < remainingBudget)

        clipDao.upsertSyncState(
            previous.copy(
                lastSyncAt = now,
                monthlyFetchedCount = previous.monthlyFetchedCount + fetched,
                usageMonth = currentMonth,
                rateLimitLimit = lastResult?.rateLimitLimit,
                rateLimitRemaining = lastResult?.rateLimitRemaining,
                rateLimitResetEpochSeconds = lastResult?.rateLimitReset,
            ),
        )
        "同期しました: 新規 $inserted 件 / 取得 $fetched 件"
        }
    }

    private suspend fun validSession(): OAuthSession? {
        val current = apiSettingsStore.loadSession() ?: return null
        if (!current.isExpired) return current
        val refreshToken = current.refreshToken ?: run {
            apiSettingsStore.clearSession()
            return null
        }
        val clientId = apiSettingsStore.load().clientId
        if (clientId.isBlank()) return null
        val refreshed = xOAuthManager.refresh(clientId, refreshToken)
        return current.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken ?: refreshToken,
            expiresAtEpochMillis = refreshed.expiresAtEpochMillis,
            scopes = refreshed.scopes,
        ).also(apiSettingsStore::saveSession)
    }

    private suspend fun createAssetForMedia(
        clipId: Long,
        postId: String,
        media: XMedia,
        now: String,
        canDownloadVideoThumb: Boolean,
    ): AssetEntity? {
        val isPhoto = media.type == "photo"
        val isVideoLike = media.type == "video" || media.type == "animated_gif"
        val remote = when {
            isPhoto -> media.url
            isVideoLike -> media.previewImageUrl
            else -> null
        } ?: return null
        val shouldDownload = isPhoto || (isVideoLike && canDownloadVideoThumb)
        val localPath = if (shouldDownload) {
            runCatching { downloadMedia(postId, media.mediaKey, remote) }.getOrNull()
        } else {
            null
        }
        return AssetEntity(
            clipId = clipId,
            mediaKey = media.mediaKey,
            type = if (isPhoto) "photo" else "video_thumbnail",
            remoteUrl = remote,
            previewUrl = media.previewImageUrl,
            localPath = localPath,
            width = media.width,
            height = media.height,
            downloadState = when {
                localPath != null -> "downloaded"
                shouldDownload -> "failed"
                else -> "wifi_waiting"
            },
            sizeBytes = localPath?.let { File(it).length() },
            createdAt = now,
        )
    }

    private fun downloadMedia(postId: String, mediaKey: String, url: String): String {
        val imageDir = postStorageManager.imageDirectory()
        val extension = url.substringBefore("?").substringAfterLast('.', "jpg").takeIf { it.length <= 5 } ?: "jpg"
        val target = File(imageDir, "${postId}_${mediaKey}.$extension")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        connection.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    suspend fun createTag(name: String) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
        val clean = name.trim()
        if (clean.isNotEmpty()) {
            val now = Instant.now().toString()
            database.tagDao().insertTag(TagEntity(name = clean, createdAt = now, updatedAt = now))
        }
        }
    }

    suspend fun renameTag(tag: TagEntity, name: String) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
        val clean = name.trim()
        if (clean.isNotEmpty()) {
            database.tagDao().updateTag(tag.copy(name = clean, updatedAt = Instant.now().toString()))
        }
        }
    }

    suspend fun deleteTag(tagId: Long) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { it.tagDao().deleteTag(tagId) }
    }

    suspend fun addAllFromTagToTag(sourceTagId: Long, targetTagId: Long) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
        val tagDao = database.tagDao()
        val now = Instant.now().toString()
        tagDao.clipsForTag(sourceTagId).forEach { clip ->
            tagDao.insertClipTag(ClipTagEntity(clip.id, targetTagId, now))
        }
        }
    }

    suspend fun setClipTags(clipId: Long, tagIds: Set<Long>) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { it.clipDao().replaceClipTags(clipId, tagIds, Instant.now().toString()) }
    }

    suspend fun updateSummary(clip: ClipEntity, summary: String) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { it.clipDao().updateClip(clip.copy(summary = summary)) }
    }

    suspend fun moveClipToTrash(clip: ClipEntity) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { it.clipDao().updateClip(clip.copy(isDeleted = true)) }
    }
}

private fun XApiException.toUserMessage(): String = when (statusCode) {
    401 -> "Xの認証期限が切れました。もう一度ログインしてください"
    403 -> "X APIの権限が不足しています。Developer Consoleの権限とスコープを確認してください"
    429 -> "X APIの15分制限に達しました。回復後にもう一度同期してください"
    in 500..599 -> "X APIで一時的な障害が発生しています。時間を置いて再試行してください"
    else -> "X APIとの通信に失敗しました (HTTP $statusCode)"
}

private fun Context.isWifiConnected(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
