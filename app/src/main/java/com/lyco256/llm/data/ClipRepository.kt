package com.lyco256.llm.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.YearMonth

class ClipRepository(
    private val context: Context,
    private val clipDao: ClipDao,
    private val tagDao: TagDao,
    private val apiSettingsStore: ApiSettingsStore,
) {
    private val xApiClient = XApiClient()

    val apiSettings: ApiSettings
        get() = apiSettingsStore.load()

    suspend fun loadApiSettings(): ApiSettings = withContext(Dispatchers.IO) {
        apiSettingsStore.load()
    }

    val syncState: Flow<SyncStateEntity?> = clipDao.observeSyncState()

    val tagsWithCount: Flow<List<TagWithCount>> = combine(
        tagDao.observeTags(),
        tagDao.observeTagCounts(),
    ) { tags, counts ->
        val countMap = counts.associate { it.tagId to it.count }
        tags.map { TagWithCount(it, countMap[it.id] ?: 0) }
    }

    val clipsWithDetails: Flow<List<ClipWithDetails>> = combine(
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

    suspend fun saveApiSettings(settings: ApiSettings) = withContext(Dispatchers.IO) {
        apiSettingsStore.save(settings)
    }

    suspend fun clearApiSettings() = withContext(Dispatchers.IO) {
        apiSettingsStore.clear()
    }

    suspend fun ensureSeedData() = withContext(Dispatchers.IO) {
        val state = SyncStateEntity(
            usageMonth = YearMonth.now().toString(),
            rateLimitRemaining = 75,
            rateLimitLimit = 75,
            rateLimitResetEpochSeconds = Instant.now().plusSeconds(15 * 60).epochSecond,
        )
        clipDao.upsertSyncState(state)
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

    suspend fun syncNow(): String = withContext(Dispatchers.IO) {
        val settings = apiSettingsStore.load()
        if (
            settings.xUserId.isBlank() ||
            settings.apiKey.isBlank() ||
            settings.apiKeySecret.isBlank() ||
            settings.accessToken.isBlank() ||
            settings.accessTokenSecret.isBlank()
        ) {
            ensureSeedData()
            return@withContext "API設定が未完了のため、ダミーデータを確認用に用意しました。"
        }

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
            return@withContext "月間停止ラインに達しているため同期しませんでした。"
        }

        val now = Instant.now().toString()
        val remainingBudget = (previous.monthlyBudgetLimit - previous.monthlyFetchedCount).coerceAtLeast(0)
        if (remainingBudget <= 0) {
            return@withContext "月間取得上限に達しているため同期しませんでした。"
        }

        var paginationToken: String? = null
        var fetched = 0
        var inserted = 0
        var lastResult: XApiResult? = null
        val isWifi = context.isWifiConnected()

        do {
            val maxResults = minOf(100, remainingBudget - fetched)
            if (maxResults <= 0) break

            val result = xApiClient.fetchLikedPosts(
                settings = settings,
                maxResults = maxResults,
                paginationToken = paginationToken,
            )
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

    private fun createAssetForMedia(
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
        val imageDir = File(context.filesDir, "images").also { it.mkdirs() }
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
        val clean = name.trim()
        if (clean.isNotEmpty()) {
            val now = Instant.now().toString()
            tagDao.insertTag(TagEntity(name = clean, createdAt = now, updatedAt = now))
        }
    }

    suspend fun renameTag(tag: TagEntity, name: String) = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isNotEmpty()) {
            tagDao.updateTag(tag.copy(name = clean, updatedAt = Instant.now().toString()))
        }
    }

    suspend fun deleteTag(tagId: Long) = withContext(Dispatchers.IO) {
        tagDao.deleteTag(tagId)
    }

    suspend fun addAllFromTagToTag(sourceTagId: Long, targetTagId: Long) = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        tagDao.clipsForTag(sourceTagId).forEach { clip ->
            tagDao.insertClipTag(ClipTagEntity(clip.id, targetTagId, now))
        }
    }

    suspend fun setClipTags(clipId: Long, tagIds: Set<Long>) = withContext(Dispatchers.IO) {
        clipDao.replaceClipTags(clipId, tagIds, Instant.now().toString())
    }

    suspend fun updateSummary(clip: ClipEntity, summary: String) = withContext(Dispatchers.IO) {
        clipDao.updateClip(clip.copy(summary = summary))
    }

    suspend fun moveClipToTrash(clip: ClipEntity) = withContext(Dispatchers.IO) {
        clipDao.updateClip(clip.copy(isDeleted = true))
    }
}

private fun Context.isWifiConnected(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
