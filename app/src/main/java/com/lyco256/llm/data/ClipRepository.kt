package com.lyco256.llm.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.room.withTransaction
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

private const val WEBP_QUALITY = 85
private const val LIKE_COUNT_FINAL_AFTER_DAYS = 7L

data class LikeCountRefreshEstimate(
    val totalTargets: Int,
    val executableTargets: Int,
    val estimatedCostUsd: Double,
)

data class MediaGridClipSource(
    override val clip: ClipEntity,
    override val tags: List<TagEntity>,
    val assets: List<MediaGridAssetRow>,
) : ClassifiedClipItem

@OptIn(ExperimentalCoroutinesApi::class)
class ClipRepository(
    private val context: Context,
    private val postStorageManager: PostStorageManager,
    private val apiSettingsStore: SettingsStore,
    private val xOAuthManager: OAuthGateway,
    private val xApiClient: XApiGateway,
    private val ocrTextGateway: OcrTextGateway = FakeOcrTextGateway(),
    private val includeSeedMedia: Boolean = true,
) {

    val apiSettings: ApiSettings
        get() = apiSettingsStore.load()

    suspend fun loadApiSettings(): ApiSettings = withContext(Dispatchers.IO) {
        apiSettingsStore.load()
    }

    suspend fun loadOAuthSession(): OAuthSession? = withContext(Dispatchers.IO) {
        apiSettingsStore.loadSession()
    }

    suspend fun loadSettingsSnapshot(): SettingsSnapshot = withContext(Dispatchers.IO) {
        val database = postStorageManager.database.value ?: return@withContext SettingsSnapshot()
        val clipDao = database.clipDao()
        val syncState = clipDao.getSyncState() ?: SyncStateEntity()
        val monthState = currentMonthState(syncState)
        SettingsSnapshot(
            monthlyApiUsage = monthState.monthlyFetchedCount.toLong(),
            cumulativeApiUsage = clipDao.getTotalBillableReadCount(),
            monthlyWarningLimit = syncState.monthlyWarningLimit,
            monthlyStopLimit = syncState.monthlyStopLimit,
            rateLimitRemaining = syncState.rateLimitRemaining,
            rateLimitLimit = syncState.rateLimitLimit,
            rateLimitResetEpochSeconds = syncState.rateLimitResetEpochSeconds,
            lastSyncAt = syncState.lastSyncAt,
            saveCount = clipDao.countActiveClips(),
            imageCount = try {
                postStorageManager.countManagedImages()
            } catch (_: Exception) {
                0
            },
            tweetDataBytes = postStorageManager.state.value.locations.firstOrNull { it.isCurrent }?.usedBytes,
        )
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

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val settings = apiSettingsStore.load()
        val session = apiSettingsStore.loadSession()
        var revokeFailed = false
        if (settings.clientId.isNotBlank() && session != null) {
            revokeFailed = runCatching { xApiClient.revokeToken(settings.clientId, session.refreshToken ?: session.accessToken) }.isFailure
        }
        apiSettingsStore.clearSession()
        revokeFailed
    }

    val storageState = postStorageManager.state

    val syncState: Flow<SyncStateEntity?> = postStorageManager.database.flatMapLatest { database ->
        database?.clipDao()?.observeSyncState() ?: flowOf(null)
    }

    val tagHierarchy: Flow<TagHierarchy> = postStorageManager.database.flatMapLatest { database ->
        if (database == null) return@flatMapLatest flowOf(TagHierarchy())
        val tagDao = database.tagDao()
        combine(
            tagDao.observeGroups(),
            tagDao.observeTags(),
            tagDao.observeTagCounts(),
            database.clipDao().observeActiveClipTags(),
        ) { groups, tags, counts, clipTags ->
            val countMap = counts.associate { it.tagId to it.count }
            TagHierarchy(
                groups = groups,
                tags = tags.map { TagWithCount(it, countMap[it.id] ?: 0) },
                clipTags = clipTags,
            )
        }
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

    fun observeClipWithDetails(clipId: Long): Flow<ClipWithDetails?> = postStorageManager.database.flatMapLatest { database ->
        if (database == null) return@flatMapLatest flowOf(null)
        combine(
            database.clipDao().observeActiveClip(clipId),
            database.clipDao().observeAssetsForClip(clipId),
            database.tagDao().observeTagsForClip(clipId),
        ) { clip, assets, tags ->
            clip?.let { ClipWithDetails(clip = it, assets = assets, tags = tags) }
        }
    }

    val mediaGridSource: Flow<List<MediaGridClipSource>> = postStorageManager.database.flatMapLatest { database ->
        if (database == null) return@flatMapLatest flowOf(emptyList())
        val clipDao = database.clipDao()
        val tagDao = database.tagDao()
        combine(
            clipDao.observeActiveClips(),
            clipDao.observeActiveMediaGridAssetRows(),
            clipDao.observeActiveClipTags(),
            clipDao.observeActiveMediaGridClipTags(),
            tagDao.observeTags(),
        ) { clips, assets, activeClipTags, mediaClipTags, tags ->
            val tagsById = tags.associateBy { it.id }
            val activeTagsByClip = activeClipTags
                .groupBy { it.clipId }
                .mapValues { (_, rows) -> rows.mapNotNull { tagsById[it.tagId] } }
            val mediaTagsByClip = mediaClipTags
                .groupBy { it.clipId }
                .mapValues { (_, rows) -> rows.mapNotNull { tagsById[it.tagId] } }
            val assetsByClip = assets
                .groupBy { it.clipId }
                .mapValues { (_, rows) -> rows.sortedBy { it.assetId } }

            clips.map { clip ->
                val clipAssets = assetsByClip[clip.id].orEmpty()
                val clipTags = if (clipAssets.isEmpty()) {
                    activeTagsByClip[clip.id].orEmpty()
                } else {
                    mediaTagsByClip[clip.id].orEmpty().ifEmpty { activeTagsByClip[clip.id].orEmpty() }
                }
                MediaGridClipSource(
                    clip = clip,
                    tags = clipTags,
                    assets = clipAssets,
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
            if (includeSeedMedia && id > 0 && index != 1) {
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
        var session = validSession() ?: return@withDatabase "X API設定からXにログインしてください"

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

        var state = previous
        var paginationToken: String? = previous.likedPostsNextToken
        var resumingContinuation = paginationToken != null
        var fetched = 0
        var inserted = 0
        var lastResult: XApiResult? = null
        val existingPostIds = clipDao.getActiveClips().mapTo(mutableSetOf()) { it.xPostId }
        var newestReturnedPostId: String? = null
        var firstPageFromTop = !resumingContinuation
        var reachedExistingBoundary = false
        var resumedContinuation = resumingContinuation

        while (fetched < remainingBudget) {
            val pageLimit = if (firstPageFromTop && existingPostIds.isNotEmpty()) 5 else 100
            val maxResults = minOf(pageLimit, remainingBudget - fetched)
            if (maxResults <= 0) break

            val fetchPage = {
                xApiClient.fetchLikedPosts(session.accessToken, session.xUserId, maxResults, paginationToken)
            }
            val result = try {
                fetchPage()
            } catch (error: XApiException) {
                if (error.statusCode == 429) {
                    state = state.copy(
                        likedPostsNextToken = paginationToken,
                        rateLimitLimit = error.rateLimitLimit ?: state.rateLimitLimit,
                        rateLimitRemaining = error.rateLimitRemaining ?: 0,
                        rateLimitResetEpochSeconds = error.rateLimitReset ?: state.rateLimitResetEpochSeconds,
                    )
                    clipDao.upsertSyncState(state)
                }
                if (error.statusCode != 401) {
                    if (error.statusCode != 429) {
                        clipDao.upsertSyncState(state.copy(likedPostsNextToken = paginationToken))
                    }
                    throw IllegalStateException(error.toUserMessage(), error)
                }
                session = refreshSession(session) ?: run {
                    throw IllegalStateException(error.toUserMessage(), error)
                }
                try {
                    fetchPage()
                } catch (retryError: XApiException) {
                    if (retryError.statusCode == 401) apiSettingsStore.clearSession()
                    throw IllegalStateException(retryError.toUserMessage(), retryError)
                }
            }
            lastResult = result
            if (result.posts.isEmpty()) {
                paginationToken = null
                state = state.copy(
                    likedPostsNextToken = null,
                    rateLimitLimit = result.rateLimitLimit,
                    rateLimitRemaining = result.rateLimitRemaining,
                    rateLimitResetEpochSeconds = result.rateLimitReset,
                )
                clipDao.upsertSyncState(state)
                break
            }
            fetched += result.posts.size
            if (firstPageFromTop) newestReturnedPostId = result.posts.firstOrNull()?.id
            val postsToInsert = postsBeforeFirstExisting(result.posts, existingPostIds)
            val pageReachedBoundary = postsToInsert.size < result.posts.size
            reachedExistingBoundary = reachedExistingBoundary || pageReachedBoundary

            postsToInsert.forEach { post ->
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
                        likeCount = post.likeCount,
                        likeCountFetchedAt = post.likeCount?.let { now },
                    ),
                )
                if (clipId > 0) {
                    inserted += 1
                    existingPostIds += post.id
                    clipDao.insertAssets(
                        post.media.mapNotNull { media ->
                            createAssetForMedia(
                                clipId = clipId,
                                postId = post.id,
                                media = media,
                                now = now,
                            )
                        },
                    )
                }
            }
            paginationToken = if (pageReachedBoundary) null else result.nextToken
            state = recordApiUsage(database, state, result.posts.size)
            clipDao.upsertSyncState(
                state.copy(
                    likedPostsNextToken = paginationToken,
                    rateLimitLimit = result.rateLimitLimit,
                    rateLimitRemaining = result.rateLimitRemaining,
                    rateLimitResetEpochSeconds = result.rateLimitReset,
                ),
            )

            if (paginationToken == null) {
                if (resumingContinuation && fetched < remainingBudget) {
                    // The saved continuation is complete. Check the top once more so likes
                    // added while it was pending are not delayed until another manual sync.
                    resumingContinuation = false
                    firstPageFromTop = true
                    continue
                }
                break
            }
            firstPageFromTop = false
        }

        clipDao.upsertSyncState(
            state.copy(
                lastSyncAt = now,
                newestSeenPostId = newestReturnedPostId ?: previous.newestSeenPostId,
                usageMonth = currentMonth,
                likedPostsNextToken = paginationToken,
                rateLimitLimit = lastResult?.rateLimitLimit ?: state.rateLimitLimit,
                rateLimitRemaining = lastResult?.rateLimitRemaining ?: state.rateLimitRemaining,
                rateLimitResetEpochSeconds = lastResult?.rateLimitReset ?: state.rateLimitResetEpochSeconds,
            ),
        )
        buildString {
            append("同期しました: 新規 $inserted 件 / 取得 $fetched 件")
            if (reachedExistingBoundary) append("（取得済み地点で停止）")
            if (paginationToken != null) append("（続きは次回同期）")
            if (resumedContinuation) append("（前回の続きから再開）")
        }
        }
    }

    suspend fun estimateLikeCountRefresh(): LikeCountRefreshEstimate = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val dao = database.clipDao()
            val state = currentMonthState(dao.getSyncState() ?: SyncStateEntity())
            val targets = likeCountRefreshTargets(dao.getActiveClips(), Instant.now())
            val remaining = (state.monthlyBudgetLimit - state.monthlyFetchedCount).coerceAtLeast(0)
            val executable = if (state.monthlyFetchedCount >= state.monthlyStopLimit) 0 else minOf(targets.size, remaining)
            LikeCountRefreshEstimate(targets.size, executable, executable * 0.001)
        }
    }

    suspend fun refreshLikeCounts(): String = withContext(Dispatchers.IO) {
        val session = validSession() ?: return@withContext "Xへのログインが必要です。"
        postStorageManager.withDatabase { database ->
            val dao = database.clipDao()
            var state = currentMonthState(dao.getSyncState() ?: SyncStateEntity())
            val remaining = (state.monthlyBudgetLimit - state.monthlyFetchedCount).coerceAtLeast(0)
            val allTargets = likeCountRefreshTargets(dao.getActiveClips(), Instant.now())
            val targets = if (state.monthlyFetchedCount >= state.monthlyStopLimit) emptyList() else allTargets.take(remaining)
            if (targets.isEmpty()) {
                return@withDatabase if (allTargets.isEmpty()) "再取得対象はありません。" else "月間取得上限に達しているため再取得できません。"
            }

            var success = 0
            var permanentFailures = 0
            var attempted = 0
            var interruptedMessage: String? = null
            for (batch in targets.chunked(100)) {
                val now = Instant.now().toString()
                val byPostId = batch.associateBy { it.xPostId }
                attempted += batch.size
                val result = try {
                    xApiClient.fetchPostMetrics(session.accessToken, batch.map { it.xPostId })
                } catch (error: Exception) {
                    if (error is XApiException && error.statusCode == 401) apiSettingsStore.clearSession()
                    interruptedMessage = if (error is XApiException) error.toUserMessage() else "通信エラーにより中断しました"
                    break
                }

                result.posts.forEach { post ->
                    byPostId[post.id]?.let { dao.updateLikeCount(it.id, post.likeCount, now) }
                    success += 1
                }
                result.errors.filter { it.isPermanentPostFailure() }.forEach { error ->
                    byPostId[error.postId]?.let {
                        dao.recordLikeCountFailure(it.id, now, error.userMessage())
                        permanentFailures += 1
                    }
                }
                state = recordApiUsage(database, state, result.posts.size)
                dao.upsertSyncState(
                    state.copy(
                        rateLimitLimit = result.rateLimitLimit,
                        rateLimitRemaining = result.rateLimitRemaining,
                        rateLimitResetEpochSeconds = result.rateLimitReset,
                    ),
                )
            }

            buildString {
                append("取得成功: ${success}件 / 取得失敗: ${permanentFailures}件")
                if (interruptedMessage != null) append("\n$interruptedMessage。未処理の投稿は次回も対象です。")
                if (permanentFailures > 0) append("\n恒久失敗の投稿は次回以降の対象から除外されます。")
                if (attempted < allTargets.size) append("\n月間残り枠の範囲で${attempted}件を処理しました。")
            }
        }
    }

    private suspend fun recordApiUsage(database: LikeListDatabase, state: SyncStateEntity, delta: Int): SyncStateEntity {
        if (delta <= 0) return state
        val current = currentMonthState(state)
        val next = current.copy(monthlyFetchedCount = current.monthlyFetchedCount + delta)
        val now = Instant.now().toString()
        database.withTransaction {
            database.clipDao().upsertSyncState(next)
            database.clipDao().incrementApiUsageMonth(next.usageMonth ?: YearMonth.now().toString(), delta.toLong(), now)
        }
        return next
    }

    private fun currentMonthState(state: SyncStateEntity): SyncStateEntity {
        val month = YearMonth.now().toString()
        return if (state.usageMonth != month) {
            state.copy(monthlyFetchedCount = 0, usageMonth = month)
        } else {
            state.copy(usageMonth = state.usageMonth ?: month)
        }
    }

    private fun likeCountRefreshTargets(clips: List<ClipEntity>, now: Instant): List<ClipEntity> = clips.filter { clip ->
        if (clip.isDeleted || clip.likeCountFetchFailedAt != null || clip.xPostId.any { !it.isDigit() }) return@filter false
        if (clip.likeCount == null) return@filter true
        val created = runCatching { Instant.parse(clip.xCreatedAt) }.getOrNull() ?: return@filter false
        val fetched = clip.likeCountFetchedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@filter false
        val finalAt = created.plusSeconds(LIKE_COUNT_FINAL_AFTER_DAYS * 24 * 60 * 60)
        fetched.isBefore(finalAt) && !now.isBefore(finalAt)
    }

    private suspend fun validSession(): OAuthSession? {
        val current = apiSettingsStore.loadSession() ?: return null
        if (!current.isExpired) return current
        return refreshSession(current)
    }

    private suspend fun refreshSession(current: OAuthSession): OAuthSession? {
        val refreshToken = current.refreshToken ?: return null
        val clientId = apiSettingsStore.load().clientId
        if (clientId.isBlank()) return null
        val refreshed = runCatching { xOAuthManager.refresh(clientId, refreshToken) }.getOrNull() ?: return null
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
    ): AssetEntity? {
        val isPhoto = media.type == "photo"
        val isVideoLike = media.type == "video" || media.type == "animated_gif"
        val remote = when {
            isPhoto -> media.url
            isVideoLike -> media.previewImageUrl
            else -> null
        } ?: return null
        val shouldDownload = isPhoto || isVideoLike
        val localPath = if (shouldDownload) {
            runCatching {
                if (isPhoto) {
                    downloadPhotoAsWebp(postId, media.mediaKey, remote)
                } else {
                    downloadMedia(postId, media.mediaKey, remote)
                }
            }.getOrNull()
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
            downloadState = if (localPath != null) "downloaded" else "failed",
            sizeBytes = localPath?.let { File(it).length() },
            createdAt = now,
        )
    }

    private fun downloadPhotoAsWebp(postId: String, mediaKey: String, url: String): String =
        downloadImageAsWebp(postId, mediaKey, url)

    private fun downloadMedia(postId: String, mediaKey: String, url: String): String =
        downloadImageAsWebp(postId, mediaKey, url)

    private fun downloadImageAsWebp(postId: String, mediaKey: String, url: String): String {
        val imageDir = postStorageManager.imageDirectory()
        val target = File(imageDir, "${postId}_${mediaKey}.webp")
        val sourceBytes = openConnection(url).inputStream.use { input -> input.readBytes() }
        val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: error("画像を読み込めませんでした")
        val bitmap = decoded.withBlackBackgroundIfTransparent()
        try {
            target.outputStream().use { output ->
                check(bitmap.compress(webpCompressFormat(), WEBP_QUALITY, output)) { "WebP変換に失敗しました" }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
        return target.absolutePath
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }

    private fun Bitmap.withBlackBackgroundIfTransparent(): Bitmap {
        if (!hasAlpha()) return this
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(this@withBlackBackgroundIfTransparent, 0f, 0f, null)
        }
        return output
    }

    @Suppress("DEPRECATION")
    private fun webpCompressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    suspend fun createTag(name: String, parentGroupId: Long? = null, colorId: String = TagColorId.STANDARD.id) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val clean = cleanNodeName(name)
            val tagDao = database.tagDao()
            validateParent(tagDao, parentGroupId)
            ensureUniqueSiblingName(tagDao, parentGroupId, clean)
            val now = Instant.now().toString()
            val order = siblingNodes(tagDao, parentGroupId).size
            check(tagDao.insertTag(TagEntity(name = clean, parentGroupId = parentGroupId, sortOrder = order, createdAt = now, updatedAt = now, colorId = colorId)) > 0) {
                "タグを追加できませんでした"
            }
        }
    }

    suspend fun renameTag(tag: TagEntity, name: String) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val clean = cleanNodeName(name)
            val tagDao = database.tagDao()
            ensureUniqueSiblingName(tagDao, tag.parentGroupId, clean, TagNodeRef(TagNodeType.TAG, tag.id))
            tagDao.updateTag(tag.copy(name = clean, updatedAt = Instant.now().toString()))
        }
    }

    suspend fun createGroup(name: String, parentGroupId: Long? = null, colorId: String = TagColorId.STANDARD.id) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val clean = cleanNodeName(name)
            val tagDao = database.tagDao()
            validateParent(tagDao, parentGroupId)
            ensureUniqueSiblingName(tagDao, parentGroupId, clean)
            val now = Instant.now().toString()
            val order = siblingNodes(tagDao, parentGroupId).size
            tagDao.insertGroup(
                TagGroupEntity(
                    name = clean,
                    parentGroupId = parentGroupId,
                    sortOrder = order,
                    createdAt = now,
                    updatedAt = now,
                    colorId = colorId,
                ),
            )
        }
    }

    suspend fun renameGroup(group: TagGroupEntity, name: String) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val clean = cleanNodeName(name)
            val tagDao = database.tagDao()
            ensureUniqueSiblingName(tagDao, group.parentGroupId, clean, TagNodeRef(TagNodeType.GROUP, group.id))
            tagDao.updateGroup(group.copy(name = clean, updatedAt = Instant.now().toString()))
        }
    }

    suspend fun deleteTag(tagId: Long) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { it.tagDao().deleteTag(tagId) }
    }

    suspend fun deleteGroup(groupId: Long) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val tagDao = database.tagDao()
            require(tagDao.countChildGroups(groupId) == 0 && tagDao.countChildTags(groupId) == 0) {
                "子要素があるグループは削除できません"
            }
            tagDao.deleteGroup(groupId)
        }
    }

    suspend fun moveNode(node: TagNodeRef, parentGroupId: Long?) = moveNodeToParentAt(node, parentGroupId, Int.MAX_VALUE)

    suspend fun moveNodeToParentAtSlot(
        node: TagNodeRef,
        parentGroupId: Long?,
        indexInDestinationWithoutDragged: Int,
    ) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val tagDao = database.tagDao()
            validateParent(tagDao, parentGroupId)
            val groups = tagDao.getGroups()
            val tags = tagDao.getTags()
            val sourceParentGroupId = parentGroupIdForMove(node, groups, tags)
            when (node.type) {
                TagNodeType.TAG -> {
                    val tag = tags.firstOrNull { it.id == node.id } ?: error("タグが見つかりません")
                    ensureUniqueSiblingName(tagDao, parentGroupId, tag.name, node)
                    val sourceOrder = siblingNodes(groups, tags, sourceParentGroupId).map { it.first }.filterNot { it == node }
                    val destinationOrder = orderNodesAfterMoveAtSlot(
                        currentDestinationNodes = siblingNodes(groups, tags, parentGroupId).map { it.first },
                        node = node,
                        indexInDestinationWithoutDragged = indexInDestinationWithoutDragged,
                    )
                    if (sourceParentGroupId != parentGroupId) {
                        applySiblingOrder(tagDao, groups, tags, sourceParentGroupId, sourceOrder)
                    }
                    applySiblingOrder(tagDao, groups, tags, parentGroupId, destinationOrder, movedNode = node)
                }
                TagNodeType.GROUP -> {
                    val group = groups.firstOrNull { it.id == node.id } ?: error("グループが見つかりません")
                    requireValidGroupDestination(groups, group.id, parentGroupId)
                    ensureUniqueSiblingName(tagDao, parentGroupId, group.name, node)
                    val sourceOrder = siblingNodes(groups, tags, sourceParentGroupId).map { it.first }.filterNot { it == node }
                    val destinationOrder = orderNodesAfterMoveAtSlot(
                        currentDestinationNodes = siblingNodes(groups, tags, parentGroupId).map { it.first },
                        node = node,
                        indexInDestinationWithoutDragged = indexInDestinationWithoutDragged,
                    )
                    if (sourceParentGroupId != parentGroupId) {
                        applySiblingOrder(tagDao, groups, tags, sourceParentGroupId, sourceOrder)
                    }
                    applySiblingOrder(tagDao, groups, tags, parentGroupId, destinationOrder, movedNode = node)
                }
            }
        }
    }

    suspend fun moveNodeToParentAt(node: TagNodeRef, parentGroupId: Long?, index: Int) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val tagDao = database.tagDao()
            validateParent(tagDao, parentGroupId)
            val groups = tagDao.getGroups()
            val tags = tagDao.getTags()
            val sourceParentGroupId = parentGroupIdForMove(node, groups, tags)
            when (node.type) {
                TagNodeType.TAG -> {
                    val tag = tags.firstOrNull { it.id == node.id } ?: error("タグが見つかりません")
                    ensureUniqueSiblingName(tagDao, parentGroupId, tag.name, node)
                    val sourceOrder = siblingNodes(groups, tags, sourceParentGroupId).map { it.first }.filterNot { it == node }
                    val destinationOrder = orderNodesAfterMove(
                        currentDestinationNodes = siblingNodes(groups, tags, parentGroupId).map { it.first },
                        node = node,
                        index = index,
                    )
                    if (sourceParentGroupId != parentGroupId) {
                        applySiblingOrder(tagDao, groups, tags, sourceParentGroupId, sourceOrder)
                    }
                    applySiblingOrder(tagDao, groups, tags, parentGroupId, destinationOrder, movedNode = node)
                }
                TagNodeType.GROUP -> {
                    val group = groups.firstOrNull { it.id == node.id } ?: error("グループが見つかりません")
                    requireValidGroupDestination(groups, group.id, parentGroupId)
                    ensureUniqueSiblingName(tagDao, parentGroupId, group.name, node)
                    val sourceOrder = siblingNodes(groups, tags, sourceParentGroupId).map { it.first }.filterNot { it == node }
                    val destinationOrder = orderNodesAfterMove(
                        currentDestinationNodes = siblingNodes(groups, tags, parentGroupId).map { it.first },
                        node = node,
                        index = index,
                    )
                    if (sourceParentGroupId != parentGroupId) {
                        applySiblingOrder(tagDao, groups, tags, sourceParentGroupId, sourceOrder)
                    }
                    applySiblingOrder(tagDao, groups, tags, parentGroupId, destinationOrder, movedNode = node)
                }
            }
        }
    }

    suspend fun reorderSiblings(parentGroupId: Long?, orderedNodes: List<TagNodeRef>) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val tagDao = database.tagDao()
            val current = siblingNodes(tagDao, parentGroupId)
            require(current.map { it.first }.toSet() == orderedNodes.toSet()) { "並び替え対象が一致しません" }
            val groups = tagDao.getGroups()
            val tags = tagDao.getTags()
            applySiblingOrder(tagDao, groups, tags, parentGroupId, orderedNodes)
        }
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

    suspend fun updateOcrText(clip: ClipEntity, ocrText: String) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { it.clipDao().updateOcrText(clip.id, ocrText, Instant.now().toString()) }
    }

    suspend fun detectOcrText(clip: ClipWithDetails): String = withContext(Dispatchers.IO) {
        val eligibleAssets = clip.assets
            .filter { asset -> asset.localPath != null && asset.type in setOf("photo", "video_thumbnail") }
            .sortedBy { it.id }
        if (eligibleAssets.isEmpty()) return@withContext ""
        eligibleAssets.mapNotNull { asset ->
            val path = asset.localPath?.let(::File)?.takeIf(File::isFile) ?: return@mapNotNull null
            val bitmap = runCatching { BitmapFactory.decodeFile(path.absolutePath) }.getOrNull() ?: return@mapNotNull null
            try {
                ocrTextGateway.recognize(bitmap).trim().takeIf(String::isNotBlank)
            } finally {
                bitmap.recycle()
            }
        }.joinToString("\n\n")
    }

    suspend fun moveClipToTrash(clip: ClipEntity) = withContext(Dispatchers.IO) {
        postStorageManager.withDatabase { database ->
            val clipDao = database.clipDao()
            val assets = clipDao.assetsForClipIds(listOf(clip.id))
            val imageDir = runCatching { postStorageManager.imageDirectory().canonicalFile }.getOrNull()
                ?: error("保存先が利用できません")
            val filesToDelete = assets.mapNotNull { asset ->
                val localPath = asset.localPath ?: return@mapNotNull null
                val file = File(localPath)
                if (!file.exists()) return@mapNotNull null
                val canonical = runCatching { file.canonicalFile }.getOrNull()
                    ?: error("ファイルのパスを解決できません")
                require(canonical.path.startsWith(imageDir.path + File.separator) || canonical == imageDir) {
                    "管理画像ディレクトリ外のファイルは削除できません"
                }
                canonical
            }
            filesToDelete.forEach { file ->
                require(file.delete() || !file.exists()) { "ローカル画像の削除に失敗しました" }
            }
            database.withTransaction {
                clipDao.deleteClip(clip.id)
            }
        }
    }

    private fun cleanNodeName(name: String): String = name.trim().also {
        require(it.isNotEmpty()) { "名前を入力してください" }
    }

    private suspend fun validateParent(tagDao: TagDao, parentGroupId: Long?) {
        if (parentGroupId != null) require(tagDao.getGroups().any { it.id == parentGroupId }) { "移動先グループが見つかりません" }
    }

    private suspend fun ensureUniqueSiblingName(
        tagDao: TagDao,
        parentGroupId: Long?,
        name: String,
        except: TagNodeRef? = null,
    ) {
        requireSiblingNameAvailable(tagDao.getGroups(), tagDao.getTags(), parentGroupId, name, except)
    }

    private suspend fun siblingNodes(tagDao: TagDao, parentGroupId: Long?): List<Pair<TagNodeRef, Int>> =
        siblingNodes(tagDao.getGroups(), tagDao.getTags(), parentGroupId)

    private fun siblingNodes(
        groups: List<TagGroupEntity>,
        tags: List<TagEntity>,
        parentGroupId: Long?,
    ): List<Pair<TagNodeRef, Int>> = buildList {
        groups.filter { it.parentGroupId == parentGroupId }.forEach { add(TagNodeRef(TagNodeType.GROUP, it.id) to it.sortOrder) }
        tags.filter { it.parentGroupId == parentGroupId }.forEach { add(TagNodeRef(TagNodeType.TAG, it.id) to it.sortOrder) }
    }.sortedBy { it.second }

    private suspend fun normalizeSiblings(tagDao: TagDao, parentGroupId: Long?) {
        val groups = tagDao.getGroups().associateBy { it.id }
        val tags = tagDao.getTags().associateBy { it.id }
        siblingNodes(tagDao, parentGroupId).forEachIndexed { index, (ref, _) ->
            when (ref.type) {
                TagNodeType.GROUP -> groups[ref.id]?.takeIf { it.sortOrder != index }?.let { tagDao.updateGroup(it.copy(sortOrder = index)) }
                TagNodeType.TAG -> tags[ref.id]?.takeIf { it.sortOrder != index }?.let { tagDao.updateTag(it.copy(sortOrder = index)) }
            }
        }
    }

    private suspend fun applySiblingOrder(
        tagDao: TagDao,
        groups: List<TagGroupEntity>,
        tags: List<TagEntity>,
        parentGroupId: Long?,
        orderedNodes: List<TagNodeRef>,
        movedNode: TagNodeRef? = null,
    ) {
        val groupsById = groups.associateBy { it.id }
        val tagsById = tags.associateBy { it.id }
        orderedNodes.forEachIndexed { index, ref ->
            when (ref.type) {
                TagNodeType.GROUP -> groupsById[ref.id]?.let { group ->
                    tagDao.updateGroup(
                        if (ref == movedNode) {
                            group.copy(parentGroupId = parentGroupId, sortOrder = index, updatedAt = Instant.now().toString())
                        } else {
                            group.copy(sortOrder = index, updatedAt = Instant.now().toString())
                        },
                    )
                }
                TagNodeType.TAG -> tagsById[ref.id]?.let { tag ->
                    tagDao.updateTag(
                        if (ref == movedNode) {
                            tag.copy(parentGroupId = parentGroupId, sortOrder = index, updatedAt = Instant.now().toString())
                        } else {
                            tag.copy(sortOrder = index, updatedAt = Instant.now().toString())
                        },
                    )
                }
            }
        }
    }

}

internal fun requireSiblingNameAvailable(
    groups: List<TagGroupEntity>,
    tags: List<TagEntity>,
    parentGroupId: Long?,
    name: String,
    except: TagNodeRef? = null,
) {
    val duplicateGroup = groups.any {
        it.parentGroupId == parentGroupId && it.name == name && except != TagNodeRef(TagNodeType.GROUP, it.id)
    }
    val duplicateTag = tags.any {
        it.parentGroupId == parentGroupId && it.name == name && except != TagNodeRef(TagNodeType.TAG, it.id)
    }
    require(!duplicateGroup && !duplicateTag) { "同じ場所に同名のタグまたはグループがあります" }
}

internal fun requireValidGroupDestination(
    groups: List<TagGroupEntity>,
    groupId: Long,
    parentGroupId: Long?,
) {
    require(parentGroupId != groupId) { "グループ自身または配下のグループへ移動できません" }
    val byId = groups.associateBy { it.id }
    var current = parentGroupId
    while (current != null) {
        require(current != groupId) { "グループ自身または配下のグループへ移動できません" }
        current = byId[current]?.parentGroupId
    }
}

internal fun parentGroupIdForMove(
    node: TagNodeRef,
    groups: List<TagGroupEntity>,
    tags: List<TagEntity>,
): Long? = when (node.type) {
    TagNodeType.GROUP -> (groups.firstOrNull { it.id == node.id } ?: error("グループが見つかりません")).parentGroupId
    TagNodeType.TAG -> (tags.firstOrNull { it.id == node.id } ?: error("タグが見つかりません")).parentGroupId
}

internal fun orderNodesAfterMove(
    currentDestinationNodes: List<TagNodeRef>,
    node: TagNodeRef,
    index: Int,
): List<TagNodeRef> {
    require(index >= 0 || index == Int.MAX_VALUE) { "移動先indexが不正です" }
    val currentIndex = currentDestinationNodes.indexOf(node)
    val adjustedIndex = if (currentIndex >= 0 && currentIndex < index) index - 1 else index
    val ordered = currentDestinationNodes.filterNot { it == node }.toMutableList()
    ordered.add(adjustedIndex.coerceAtMost(ordered.size), node)
    return ordered
}

internal fun orderNodesAfterMoveAtSlot(
    currentDestinationNodes: List<TagNodeRef>,
    node: TagNodeRef,
    indexInDestinationWithoutDragged: Int,
): List<TagNodeRef> {
    require(indexInDestinationWithoutDragged >= 0 || indexInDestinationWithoutDragged == Int.MAX_VALUE) {
        "移動先indexが不正です"
    }
    val ordered = currentDestinationNodes.filterNot { it == node }.toMutableList()
    ordered.add(indexInDestinationWithoutDragged.coerceAtMost(ordered.size), node)
    return ordered
}

private fun XApiException.toUserMessage(): String = when (statusCode) {
    401 -> "Xの認証期限が切れました。もう一度ログインしてください"
    403 -> "X APIの権限が不足しています。Developer Consoleの権限とスコープを確認してください"
    429 -> "X APIの15分制限に達しました。回復後にもう一度同期してください"
    in 500..599 -> "X APIで一時的な障害が発生しています。時間を置いて再試行してください"
    else -> "X APIとの通信に失敗しました (HTTP $statusCode)"
}

private fun XPostMetricError.isPermanentPostFailure(): Boolean {
    val text = "$title $detail".lowercase()
    return listOf("not found", "deleted", "forbidden", "unauthorized", "protected", "suspended", "permission").any(text::contains)
}

private fun XPostMetricError.userMessage(): String = detail.ifBlank { title }.ifBlank {
    "削除済み、または表示権限がありません"
}

internal fun <T> postsBeforeFirstExisting(
    posts: List<T>,
    existingPostIds: Set<String>,
    id: (T) -> String,
): List<T> = posts.takeWhile { id(it) !in existingPostIds }

private fun postsBeforeFirstExisting(posts: List<XPost>, existingPostIds: Set<String>): List<XPost> =
    postsBeforeFirstExisting(posts, existingPostIds, XPost::id)

private fun Context.isWifiConnected(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
