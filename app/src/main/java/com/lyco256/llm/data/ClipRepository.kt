package com.lyco256.llm.data

import com.lyco256.llm.TweetAuthorKey
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

private const val WEBP_QUALITY = 85
private const val RGB565_SAVE_PUBLISH_ATTEMPTS = 3
private const val LIKE_COUNT_FINAL_AFTER_DAYS = 7L

data class LikeCountRefreshEstimate(
    val totalTargets: Int,
    val executableTargets: Int,
    val estimatedCostUsd: Double,
)

data class MediaGridClipSource(
    val clip: ClipEntity,
    val assets: List<MediaGridAssetRow>,
    val tagIds: LongArray = LongArray(0),
    val sourceIndex: Int = 0,
    val postTimeMillis: Long? = null,
    val postLocalEpochDay: Long? = null,
    val authorKey: TweetAuthorKey = TweetAuthorKey(clip.authorId?.takeIf { it.isNotBlank() }, clip.authorUsername.lowercase()),
    val authorNameKey: String = clip.authorUsername.lowercase(),
    val mediaAssetCount: Int = assets.count { it.assetType == "photo" || it.assetType == "video_thumbnail" },
)

data class MediaGridSourceSnapshot(val revision: Long, val clips: List<MediaGridClipSource>)

data class OcrAssetRecognitionResult(
    val assetId: Long,
    val localPath: String,
    val recognition: OcrRecognitionResult,
)

data class OcrPostRecognitionResult(
    val clipId: Long,
    val assets: List<OcrAssetRecognitionResult>,
    val fullText: String,
)

data class OcrDetectionResult(
    val recognition: OcrPostRecognitionResult,
    val metadata: OcrDetectionMetadata,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ClipRepository internal constructor(
    private val context: Context,
    private val postStorageManager: PostStorageManager,
    private val apiSettingsStore: SettingsStore,
    private val xOAuthManager: OAuthGateway,
    private val xApiClient: XApiGateway,
    private val ocrTextGateway: OcrTextGateway = FakeOcrTextGateway(),
    private val ppOcrTextGateway: OcrTextGateway? = null,
    private val includeSeedMedia: Boolean = true,
    private val mediaGridPreviewEnqueuer: MediaGridPreviewEnqueuer = NoOpMediaGridPreviewEnqueuer,
    private val mediaGridRgb565RepairEnqueuer: MediaGridRgb565RepairEnqueuer =
        NoOpMediaGridRgb565RepairEnqueuer,
    private val mediaGridRgb565PackStore: MediaGridRgb565Publisher =
        MediaGridRgb565PackStore(context.filesDir),
    private val clipDeleteUndoStore: DurableClipDeleteUndoStore = DurableClipDeleteUndoStore(context.filesDir),
    private val heavyLocalWorkTracker: HeavyLocalWorkTracker = HeavyLocalWorkTracker(),
) {
    private val mediaGridPreviewStore = MediaGridPersistentPreviewStore(context.filesDir)
    private val undoCoordinator = UndoCoordinator(
        databaseProvider = postStorageManager,
        handlers = listOf(
            UndoActionHandler(UndoActionType.CLIP_TAG_CHANGE, undo = { database, rawPayload ->
                val payload = rawPayload as ClipTagChangeUndoPayload
                heavyLocalWorkTracker.trackIf(
                    payload.addedRelations.size + payload.removedRelations.size > 1,
                ) {
                    val clipDao = database.clipDao()
                    if (payload.addedRelations.isNotEmpty()) clipDao.deleteClipTags(payload.addedRelations)
                    if (payload.removedRelations.isNotEmpty()) clipDao.insertClipTags(payload.removedRelations)
                }
            }),
            UndoActionHandler(UndoActionType.TAG_CREATED, undo = { database, rawPayload ->
                database.tagDao().deleteTag((rawPayload as TagCreatedUndoPayload).tagId)
            }),
            UndoActionHandler(UndoActionType.GROUP_CREATED, undo = { database, rawPayload ->
                database.tagDao().deleteGroup((rawPayload as GroupCreatedUndoPayload).groupId)
            }),
            UndoActionHandler(UndoActionType.TAG_EDITED, undo = { database, rawPayload ->
                val payload = rawPayload as TagEditedUndoPayload
                val tagDao = database.tagDao()
                val now = Instant.now().toString()
                payload.previousName?.let { previousName ->
                    check(tagDao.updateTagName(payload.tagId, previousName, now) == 1) {
                        "タグが見つかりません"
                    }
                }
                payload.previousColorId?.let { previousColorId ->
                    check(tagDao.updateTagColor(payload.tagId, previousColorId, now) == 1) {
                        "タグが見つかりません"
                    }
                }
            }),
            UndoActionHandler(UndoActionType.GROUP_EDITED, undo = { database, rawPayload ->
                val payload = rawPayload as GroupEditedUndoPayload
                val tagDao = database.tagDao()
                val now = Instant.now().toString()
                payload.previousName?.let { previousName ->
                    check(tagDao.updateGroupName(payload.groupId, previousName, now) == 1) {
                        "グループが見つかりません"
                    }
                }
                payload.previousColorId?.let { previousColorId ->
                    check(tagDao.updateGroupColor(payload.groupId, previousColorId, now) == 1) {
                        "グループが見つかりません"
                    }
                }
            }),
            UndoActionHandler(UndoActionType.TAG_DELETED, undo = { database, rawPayload ->
                val payload = rawPayload as TagDeletedUndoPayload
                heavyLocalWorkTracker.trackIf(payload.relations.size > 1) {
                    val tagDao = database.tagDao()
                    val clipDao = database.clipDao()
                    check(tagDao.getTag(payload.tag.id) == null) {
                        "同じIDのタグが既に存在します"
                    }
                    check(payload.relations.all { it.tagId == payload.tag.id }) {
                        "タグ削除のUndoデータが不正です"
                    }
                    check(tagDao.insertTag(payload.tag) == payload.tag.id) {
                        "タグを復元できませんでした"
                    }
                    if (payload.relations.isNotEmpty()) clipDao.insertClipTags(payload.relations)
                    check(clipDao.clipTagsForTag(payload.tag.id) == payload.relations.sortedBy(ClipTagEntity::clipId)) {
                        "タグと投稿の関連を完全に復元できませんでした"
                    }
                }
            }),
            UndoActionHandler(UndoActionType.GROUP_DELETED, undo = { database, rawPayload ->
                val group = (rawPayload as GroupDeletedUndoPayload).group
                val tagDao = database.tagDao()
                check(tagDao.getGroup(group.id) == null) {
                    "同じIDのグループが既に存在します"
                }
                group.parentGroupId?.let { parentGroupId ->
                    check(tagDao.getGroup(parentGroupId) != null) {
                        "復元先の親グループが見つかりません"
                    }
                }
                check(tagDao.insertGroup(group) == group.id) {
                    "グループを復元できませんでした"
                }
                check(tagDao.getGroup(group.id) == group) {
                    "グループを完全に復元できませんでした"
                }
            }),
            UndoActionHandler(UndoActionType.SUMMARY_EDITED, undo = { database, rawPayload ->
                val payload = rawPayload as SummaryEditedUndoPayload
                check(database.clipDao().updateSummary(payload.clipId, payload.previousSummary) == 1) {
                    "投稿が見つかりません"
                }
            }),
            UndoActionHandler(UndoActionType.OCR_EDITED, undo = { database, rawPayload ->
                val payload = rawPayload as OcrEditedUndoPayload
                check(
                    database.clipDao().updateOcrText(
                        payload.clipId,
                        payload.previousOcrText,
                        payload.previousOcrUpdatedAt,
                    ) == 1,
                ) {
                    "投稿が見つかりません"
                }
            }),
            UndoActionHandler(
                UndoActionType.CLIP_DELETED,
                undo = { database, rawPayload ->
                    val payload = rawPayload as ClipDeletedUndoPayload
                    val isHeavyRestore = payload.files.isNotEmpty() || payload.assets.size + payload.relations.size > 1
                    heavyLocalWorkTracker.trackIf(isHeavyRestore) {
                        val clipDao = database.clipDao()
                        check(clipDao.getClip(payload.clip.id) == null) { "同じIDの投稿が既に存在します" }
                        check(payload.assets.all { it.clipId == payload.clip.id }) { "投稿画像のUndoデータが不正です" }
                        check(payload.relations.all { it.clipId == payload.clip.id }) { "投稿タグのUndoデータが不正です" }
                        check(payload.files.map(UndoFileMetadata::assetId).toSet().size == payload.files.size) {
                            "投稿画像のUndoデータが重複しています"
                        }
                        val restoredPaths = clipDeleteUndoStore.restore(payload, postStorageManager.imageDirectory())
                        check(clipDao.insertClip(payload.clip) == payload.clip.id) { "投稿を復元できませんでした" }
                        val restoredAssets = payload.assets.map { asset ->
                            if (asset.id in restoredPaths) asset.copy(localPath = restoredPaths.getValue(asset.id))
                            else if (asset.localPath != null) asset.copy(localPath = null)
                            else asset
                        }
                        if (restoredAssets.isNotEmpty()) {
                            check(clipDao.insertAssets(restoredAssets).all { it > 0L }) { "投稿画像を復元できませんでした" }
                        }
                        if (payload.relations.isNotEmpty()) clipDao.insertClipTags(payload.relations)
                        check(clipDao.getClip(payload.clip.id) == payload.clip) { "投稿を完全に復元できませんでした" }
                        check(clipDao.assetsForClipIds(listOf(payload.clip.id)) == restoredAssets.sortedBy(AssetEntity::id)) {
                            "投稿画像を完全に復元できませんでした"
                        }
                        check(
                            clipDao.clipTagsForClipIds(listOf(payload.clip.id)).sortedWith(
                                compareBy(ClipTagEntity::tagId, ClipTagEntity::createdAt),
                            ) == payload.relations.sortedWith(compareBy(ClipTagEntity::tagId, ClipTagEntity::createdAt)),
                        ) { "投稿タグを完全に復元できませんでした" }
                    }
                },
                afterUndo = { rawPayload ->
                    val payload = rawPayload as ClipDeletedUndoPayload
                    heavyLocalWorkTracker.trackIf(payload.files.isNotEmpty()) { clipDeleteUndoStore.cleanup(payload) }
                    runCatching { mediaGridPreviewEnqueuer.enqueue(payload.files.map(UndoFileMetadata::assetId)) }
                },
                afterFinalize = { rawPayload ->
                    val payload = rawPayload as ClipDeletedUndoPayload
                    heavyLocalWorkTracker.trackIf(payload.files.isNotEmpty()) {
                        clipDeleteUndoStore.cleanup(payload)
                    }
                },
            ),
        ),
    )

    val pendingUndo: Flow<UndoEntity?> = undoCoordinator.pendingUndo
    val heavyLocalWorkActive: StateFlow<Boolean> = heavyLocalWorkTracker.isActive

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
            saveCount = clipDao.countClips(),
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
            database.clipDao().observeClipTags(),
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
            clipDao.observeAllClips(),
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
            database.clipDao().observeClip(clipId),
            database.clipDao().observeAssetsForClip(clipId),
            database.tagDao().observeTagsForClip(clipId),
        ) { clip, assets, tags ->
            clip?.let { ClipWithDetails(clip = it, assets = assets, tags = tags) }
        }
    }

    private var mediaGridRevision = 0L
    val mediaGridSource: Flow<MediaGridSourceSnapshot> = postStorageManager.database.flatMapLatest { database ->
        if (database == null) return@flatMapLatest flowOf(MediaGridSourceSnapshot(++mediaGridRevision, emptyList()))
        val clipDao = database.clipDao()
        combine(
            clipDao.observeAllClips(),
            clipDao.observeMediaGridAssetRows(),
            clipDao.observeClipTags(),
        ) { clips, assets, clipTags ->
            val activeTagsByClip = HashMap<Long, ArrayList<Long>>()
            clipTags.forEach { row ->
                activeTagsByClip.getOrPut(row.clipId) { ArrayList() }.add(row.tagId)
            }
            val assetsByClip = HashMap<Long, ArrayList<MediaGridAssetRow>>()
            assets.forEach { row -> assetsByClip.getOrPut(row.clipId) { ArrayList() }.add(row) }

            val snapshot = clips.mapIndexed { index, clip ->
                val clipAssets = assetsByClip[clip.id].orEmpty()
                val clipTagIds = activeTagsByClip[clip.id]?.toLongArray() ?: LongArray(0)
                val postTime = runCatching { Instant.parse(clip.xCreatedAt).toEpochMilli() }.getOrNull()
                MediaGridClipSource(
                    clip = clip,
                    assets = clipAssets,
                    tagIds = clipTagIds,
                    sourceIndex = index,
                    postTimeMillis = postTime,
                    postLocalEpochDay = postTime?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() },
                    authorKey = TweetAuthorKey(clip.authorId?.takeIf { it.isNotBlank() }, clip.authorUsername.lowercase()),
                    authorNameKey = clip.authorUsername.lowercase(),
                    mediaAssetCount = clipAssets.count { it.assetType == "photo" || it.assetType == "video_thumbnail" },
                )
            }
            MediaGridSourceSnapshot(++mediaGridRevision, snapshot)
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
        val existingPostIds = clipDao.getAllClips().mapTo(mutableSetOf()) { it.xPostId }
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
                // HTTP response waiting is deliberately outside the local-work interval.
                val downloadedMedia = post.media.map { media ->
                    media to runCatching { fetchMediaBytes(media) }.getOrNull()
                }
                val isHeavyPersist = postsToInsert.size > 1 ||
                    downloadedMedia.size > 1 || downloadedMedia.any { (_, bytes) -> bytes != null }
                heavyLocalWorkTracker.trackIf(isHeavyPersist) {
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
                        downloadedMedia.forEach { (media, sourceBytes) ->
                            val prepared = createAssetForMedia(
                                clipId = clipId,
                                postId = post.id,
                                media = media,
                                sourceBytes = sourceBytes,
                                now = now,
                            ) ?: return@forEach
                            val assetId = clipDao.insertAssets(listOf(prepared.asset)).singleOrNull() ?: -1L
                            if (assetId <= 0L || prepared.asset.localPath == null) return@forEach
                            val rawPublished = prepared.rawPayload?.let { pending ->
                                publishMediaGridRgb565(assetId, pending)
                            } ?: false
                            if (!rawPublished) {
                                mediaGridRgb565RepairEnqueuer.enqueue(listOf(assetId))
                            }
                            mediaGridPreviewEnqueuer.enqueue(listOf(assetId))
                        }
                    }
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
            val targets = likeCountRefreshTargets(dao.getAllClips(), Instant.now())
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
            val allTargets = likeCountRefreshTargets(dao.getAllClips(), Instant.now())
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

                val localUpdateCount = result.posts.size + result.errors.count { it.isPermanentPostFailure() }
                heavyLocalWorkTracker.trackIf(localUpdateCount > 1) {
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
        if (clip.likeCountFetchFailedAt != null || clip.xPostId.any { !it.isDigit() }) return@filter false
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

    private data class PendingRgb565Publish(
        val payload: MediaGridRgb565Payload,
        val source: MediaGridRgb565SourceSignature,
    )

    private data class DownloadedImage(
        val path: String,
        val rawPayload: PendingRgb565Publish,
    )

    private data class PreparedAsset(
        val asset: AssetEntity,
        val rawPayload: PendingRgb565Publish?,
    )

    private suspend fun createAssetForMedia(
        clipId: Long,
        postId: String,
        media: XMedia,
        sourceBytes: ByteArray?,
        now: String,
    ): PreparedAsset? {
        val isPhoto = media.type == "photo"
        val isVideoLike = media.type == "video" || media.type == "animated_gif"
        val remote = mediaRemoteUrl(media) ?: return null
        val shouldDownload = isPhoto || isVideoLike
        val downloaded = if (shouldDownload && sourceBytes != null) {
            runCatching {
                if (isPhoto) {
                    savePhotoAsWebp(postId, media.mediaKey, sourceBytes)
                } else {
                    saveMedia(postId, media.mediaKey, sourceBytes)
                }
            }.getOrNull()
        } else {
            null
        }
        return PreparedAsset(
            asset = AssetEntity(
                clipId = clipId,
                mediaKey = media.mediaKey,
                type = if (isPhoto) "photo" else "video_thumbnail",
                remoteUrl = remote,
                previewUrl = media.previewImageUrl,
                localPath = downloaded?.path,
                width = media.width,
                height = media.height,
                downloadState = if (downloaded != null) "downloaded" else "failed",
                sizeBytes = downloaded?.path?.let { File(it).length() },
                createdAt = now,
            ),
            rawPayload = downloaded?.rawPayload,
        )
    }

    private fun mediaRemoteUrl(media: XMedia): String? = when (media.type) {
        "photo" -> media.url
        "video", "animated_gif" -> media.previewImageUrl
        else -> null
    }

    /** Reads the network response only; decode, compression and publication happen in the tracked local interval. */
    private fun fetchMediaBytes(media: XMedia): ByteArray? = mediaRemoteUrl(media)?.let { url ->
        openConnection(url).inputStream.use { input -> input.readBytes() }
    }

    private fun savePhotoAsWebp(postId: String, mediaKey: String, sourceBytes: ByteArray): DownloadedImage =
        saveImageAsWebp(postId, mediaKey, sourceBytes)

    private fun saveMedia(postId: String, mediaKey: String, sourceBytes: ByteArray): DownloadedImage =
        saveImageAsWebp(postId, mediaKey, sourceBytes)

    private fun saveImageAsWebp(postId: String, mediaKey: String, sourceBytes: ByteArray): DownloadedImage {
        val imageDir = postStorageManager.imageDirectory()
        val target = File(imageDir, "${postId}_${mediaKey}.webp")
        val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: error("画像を読み込めませんでした")
        val bitmap = decoded.withBlackBackgroundIfTransparent()
        try {
            val rawPayload = createMediaGridRgb565Payload(bitmap)
            target.outputStream().use { output ->
                check(bitmap.compress(webpCompressFormat(), WEBP_QUALITY, output)) { "WebP変換に失敗しました" }
            }
            val source = MediaGridRgb565SourceSignature(
                kind = MediaGridRgb565SourceKind.LOCAL_WEBP,
                length = target.length(),
                lastModified = target.lastModified(),
            )
            check(source.length > 0L && source.lastModified > 0L) {
                "保存画像のsource signatureを取得できませんでした"
            }
            return DownloadedImage(
                path = target.absolutePath,
                rawPayload = PendingRgb565Publish(rawPayload, source),
            )
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    private fun publishMediaGridRgb565(
        assetId: Long,
        pending: PendingRgb565Publish,
    ): Boolean {
        repeat(RGB565_SAVE_PUBLISH_ATTEMPTS) { attempt ->
            try {
                val published = mediaGridRgb565PackStore.publish(
                    assetId = assetId,
                    payload = pending.payload,
                    source = pending.source,
                )
                check(mediaGridRgb565PackStore.validatePayloadCrc(published))
                MediaGridPreviewNotifier.notifyPreviewChanged(assetId)
                return true
            } catch (_: java.io.IOException) {
                if (attempt == RGB565_SAVE_PUBLISH_ATTEMPTS - 1) return false
            } catch (_: RuntimeException) {
                return false
            }
        }
        return false
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

    suspend fun createTag(
        name: String,
        parentGroupId: Long? = null,
        colorId: String = TagColorId.STANDARD.id,
    ): TagEntity = withContext(Dispatchers.IO) {
        undoCoordinator.commitComputedDatabaseEdit(message = "タグを作成しました") { database ->
            val clean = cleanNodeName(name)
            val tagDao = database.tagDao()
            validateParent(tagDao, parentGroupId)
            ensureUniqueSiblingName(tagDao, parentGroupId, clean)
            val now = Instant.now().toString()
            val order = siblingNodes(tagDao, parentGroupId).size
            val pending = TagEntity(
                name = clean,
                parentGroupId = parentGroupId,
                sortOrder = order,
                createdAt = now,
                updatedAt = now,
                colorId = colorId,
            )
            val tagId = tagDao.insertTag(pending)
            check(tagId > 0) {
                "タグを追加できませんでした"
            }
            val created = pending.copy(id = tagId)
            UndoDatabaseEdit(
                result = created,
                payload = TagCreatedUndoPayload(tagId),
            )
        }
    }

    suspend fun renameTag(tag: TagEntity, name: String) = withContext(Dispatchers.IO) {
        undoCoordinator.commitComputedDatabaseEdit(message = "タグを変更しました") { database ->
            val clean = cleanNodeName(name)
            val tagDao = database.tagDao()
            val current = requireNotNull(tagDao.getTag(tag.id)) { "タグが見つかりません" }
            val previousName = current.name.takeIf { it != clean }
            val previousColorId = current.colorId.takeIf { it != tag.colorId }
            if (previousName != null) {
                ensureUniqueSiblingName(tagDao, current.parentGroupId, clean, TagNodeRef(TagNodeType.TAG, current.id))
            }
            val now = Instant.now().toString()
            previousName?.let {
                check(tagDao.updateTagName(current.id, clean, now) == 1) { "タグが見つかりません" }
            }
            previousColorId?.let {
                check(tagDao.updateTagColor(current.id, tag.colorId, now) == 1) { "タグが見つかりません" }
            }
            UndoDatabaseEdit(
                result = Unit,
                payload = if (previousName == null && previousColorId == null) null else TagEditedUndoPayload(
                    tagId = current.id,
                    previousName = previousName,
                    previousColorId = previousColorId,
                ),
            )
        }
    }

    suspend fun createGroup(
        name: String,
        parentGroupId: Long? = null,
        colorId: String = TagColorId.STANDARD.id,
    ): TagGroupEntity = withContext(Dispatchers.IO) {
        undoCoordinator.commitComputedDatabaseEdit(message = "グループを作成しました") { database ->
            val clean = cleanNodeName(name)
            val tagDao = database.tagDao()
            validateParent(tagDao, parentGroupId)
            ensureUniqueSiblingName(tagDao, parentGroupId, clean)
            val now = Instant.now().toString()
            val order = siblingNodes(tagDao, parentGroupId).size
            val pending = TagGroupEntity(
                name = clean,
                parentGroupId = parentGroupId,
                sortOrder = order,
                createdAt = now,
                updatedAt = now,
                colorId = colorId,
            )
            val groupId = tagDao.insertGroup(pending)
            check(groupId > 0) {
                "グループを追加できませんでした"
            }
            val created = pending.copy(id = groupId)
            UndoDatabaseEdit(
                result = created,
                payload = GroupCreatedUndoPayload(groupId),
            )
        }
    }

    suspend fun renameGroup(group: TagGroupEntity, name: String) = withContext(Dispatchers.IO) {
        undoCoordinator.commitComputedDatabaseEdit(message = "グループを変更しました") { database ->
            val clean = cleanNodeName(name)
            val tagDao = database.tagDao()
            val current = requireNotNull(tagDao.getGroup(group.id)) { "グループが見つかりません" }
            val previousName = current.name.takeIf { it != clean }
            val previousColorId = current.colorId.takeIf { it != group.colorId }
            if (previousName != null) {
                ensureUniqueSiblingName(tagDao, current.parentGroupId, clean, TagNodeRef(TagNodeType.GROUP, current.id))
            }
            val now = Instant.now().toString()
            previousName?.let {
                check(tagDao.updateGroupName(current.id, clean, now) == 1) { "グループが見つかりません" }
            }
            previousColorId?.let {
                check(tagDao.updateGroupColor(current.id, group.colorId, now) == 1) { "グループが見つかりません" }
            }
            UndoDatabaseEdit(
                result = Unit,
                payload = if (previousName == null && previousColorId == null) null else GroupEditedUndoPayload(
                    groupId = current.id,
                    previousName = previousName,
                    previousColorId = previousColorId,
                ),
            )
        }
    }

    suspend fun deleteTag(tagId: Long) = withContext(Dispatchers.IO) {
        undoCoordinator.commitComputedDatabaseEdit(message = "タグを削除しました") { database ->
            val tagDao = database.tagDao()
            val tag = requireNotNull(tagDao.getTag(tagId)) { "タグが見つかりません" }
            val relations = database.clipDao().clipTagsForTag(tagId)
            heavyLocalWorkTracker.trackIf(relations.size > 1) {
                check(tagDao.deleteTag(tagId) == 1) { "タグを削除できませんでした" }
            }
            UndoDatabaseEdit(
                result = Unit,
                payload = TagDeletedUndoPayload(tag, relations),
            )
        }
    }

    suspend fun deleteGroup(groupId: Long) = withContext(Dispatchers.IO) {
        undoCoordinator.commitComputedDatabaseEdit(message = "グループを削除しました") { database ->
            val tagDao = database.tagDao()
            val group = requireNotNull(tagDao.getGroup(groupId)) { "グループが見つかりません" }
            require(tagDao.countChildGroups(groupId) == 0 && tagDao.countChildTags(groupId) == 0) {
                "子要素があるグループは削除できません"
            }
            check(tagDao.deleteGroup(groupId) == 1) { "グループを削除できませんでした" }
            UndoDatabaseEdit(
                result = Unit,
                payload = GroupDeletedUndoPayload(group),
            )
        }
    }

    suspend fun moveNode(node: TagNodeRef, parentGroupId: Long?) = moveNodeToParentAt(node, parentGroupId, Int.MAX_VALUE)

    suspend fun moveNodeToParentAtSlot(
        node: TagNodeRef,
        parentGroupId: Long?,
        indexInDestinationWithoutDragged: Int,
    ) = withContext(Dispatchers.IO) {
        invalidateUndoBeforeNodeMoveOrReorder()
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
        invalidateUndoBeforeNodeMoveOrReorder()
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
        invalidateUndoBeforeNodeMoveOrReorder()
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
        undoCoordinator.commitComputedDatabaseEdit(message = "タグを一括追加しました") { database ->
            val clipDao = database.clipDao()
            val sourceClipIds = clipDao.clipTagsForTag(sourceTagId).mapTo(linkedSetOf()) { it.clipId }
            val changes = heavyLocalWorkTracker.trackIf(sourceClipIds.size > 1) {
                clipDao.applyClipTagChanges(
                    clipIds = sourceClipIds,
                    pendingAddTagIds = setOf(targetTagId),
                    pendingRemoveTagIds = emptySet(),
                    now = Instant.now().toString(),
                )
            }
            UndoDatabaseEdit(
                result = Unit,
                payload = if (changes.isEmpty) null else ClipTagChangeUndoPayload(
                    addedRelations = changes.addedRelations,
                    removedRelations = emptyList(),
                ),
            )
        }
    }

    suspend fun setClipTags(clipId: Long, tagIds: Set<Long>) = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        undoCoordinator.commitComputedDatabaseEdit(message = "タグを適用しました") { database ->
            val clipDao = database.clipDao()
            val currentRelations = clipDao.clipTagsForClipIds(listOf(clipId))
            val currentByTagId = currentRelations.associateBy(ClipTagEntity::tagId)
            val currentTagIds = currentByTagId.keys
            val removedRelations = currentTagIds.minus(tagIds).map { currentByTagId.getValue(it) }
            val addedRelations = tagIds.minus(currentTagIds).map { tagId ->
                ClipTagEntity(clipId = clipId, tagId = tagId, createdAt = now)
            }
            removedRelations.forEach { relation -> clipDao.deleteClipTag(relation.clipId, relation.tagId) }
            addedRelations.forEach { relation -> clipDao.insertClipTag(relation) }
            UndoDatabaseEdit(
                result = Unit,
                payload = ClipTagChangeUndoPayload(
                    addedRelations = addedRelations,
                    removedRelations = removedRelations,
                ),
            )
        }
    }

    suspend fun undoPendingEdit(): UndoCoordinatorResult = withContext(Dispatchers.IO) {
        undoCoordinator.undo()
    }

    suspend fun undoPendingEdit(expectedSlot: UndoEntity): UndoCoordinatorResult = withContext(Dispatchers.IO) {
        undoCoordinator.undo(expectedSlot)
    }

    suspend fun finalizePendingUndo(): UndoCoordinatorResult = withContext(Dispatchers.IO) {
        undoCoordinator.finalizePending()
    }

    suspend fun finalizePendingUndo(expectedSlot: UndoEntity): UndoCoordinatorResult = withContext(Dispatchers.IO) {
        undoCoordinator.finalizePending(expectedSlot)
    }

    suspend fun applyClipTagChanges(
        clipIds: Set<Long>,
        pendingAddTagIds: Set<Long>,
        pendingRemoveTagIds: Set<Long>,
    ) = withContext(Dispatchers.IO) {
        if (clipIds.isEmpty()) return@withContext
        undoCoordinator.commitComputedDatabaseEdit(message = "一括タグを変更しました") { database ->
            val changes = heavyLocalWorkTracker.trackIf(clipIds.size > 1) {
                database.clipDao().applyClipTagChanges(
                    clipIds = clipIds,
                    pendingAddTagIds = pendingAddTagIds,
                    pendingRemoveTagIds = pendingRemoveTagIds,
                    now = Instant.now().toString(),
                )
            }
            UndoDatabaseEdit(
                result = Unit,
                payload = if (changes.isEmpty) null else ClipTagChangeUndoPayload(
                    addedRelations = changes.addedRelations,
                    removedRelations = changes.removedRelations,
                ),
            )
        }
    }

    suspend fun updateSummary(clip: ClipEntity, summary: String) = withContext(Dispatchers.IO) {
        undoCoordinator.commitComputedDatabaseEdit(message = "概要を保存しました") { database ->
            val clipDao = database.clipDao()
            val current = checkNotNull(clipDao.getClip(clip.id)) { "投稿が見つかりません" }
            if (current.summary == summary) {
                UndoDatabaseEdit(result = Unit, payload = null)
            } else {
                check(clipDao.updateSummary(clip.id, summary) == 1) { "投稿が見つかりません" }
                UndoDatabaseEdit(
                    result = Unit,
                    payload = SummaryEditedUndoPayload(
                        clipId = clip.id,
                        previousSummary = current.summary,
                    ),
                )
            }
        }
    }

    suspend fun updateOcrText(clip: ClipEntity, ocrText: String) = withContext(Dispatchers.IO) {
        undoCoordinator.commitComputedDatabaseEdit(message = "OCRを保存しました") { database ->
            val clipDao = database.clipDao()
            val current = checkNotNull(clipDao.getClip(clip.id)) { "投稿が見つかりません" }
            if (current.ocrText == ocrText) {
                UndoDatabaseEdit(result = Unit, payload = null)
            } else {
                check(clipDao.updateOcrText(clip.id, ocrText, Instant.now().toString()) == 1) {
                    "投稿が見つかりません"
                }
                UndoDatabaseEdit(
                    result = Unit,
                    payload = OcrEditedUndoPayload(
                        clipId = clip.id,
                        previousOcrText = current.ocrText,
                        previousOcrUpdatedAt = current.ocrUpdatedAt,
                    ),
                )
            }
        }
    }

    suspend fun detectOcrText(clip: ClipWithDetails): OcrPostRecognitionResult =
        detectOcrTextInternal(clip, ocrTextGateway)

    suspend fun detectOcrTextForEngine(
        clip: ClipWithDetails,
        engine: OcrEngine,
    ): OcrDetectionResult {
        val gateway = when (engine) {
            OcrEngine.ML_KIT -> ocrTextGateway
            OcrEngine.PP_OCRV6_SMALL -> ppOcrTextGateway
                ?: error("PP-OCRv6 small OCR gateway is not configured")
        }
        var gatewayStartedAt: Long? = null
        val recognition = detectOcrTextInternal(clip, gateway) {
            if (gatewayStartedAt == null) gatewayStartedAt = System.nanoTime()
        }
        val elapsedMs = gatewayStartedAt?.let { startedAt ->
            ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        } ?: 0L
        return OcrDetectionResult(
            recognition = recognition,
            metadata = OcrDetectionMetadata(engine = engine, elapsedMs = elapsedMs),
        )
    }

    private suspend fun detectOcrTextInternal(
        clip: ClipWithDetails,
        gateway: OcrTextGateway,
        onGatewayStart: (() -> Unit)? = null,
    ): OcrPostRecognitionResult = withContext(Dispatchers.IO) {
        val eligibleAssets = clip.assets
            .filter { asset -> asset.localPath != null && asset.type in setOf("photo", "video_thumbnail") }
            .sortedBy { it.id }
        if (eligibleAssets.isEmpty()) {
            return@withContext OcrPostRecognitionResult(
                clipId = clip.clip.id,
                assets = emptyList(),
                fullText = "",
            )
        }
        val assetResults = eligibleAssets.mapNotNull { asset ->
            val path = asset.localPath?.let(::File)?.takeIf(File::isFile) ?: return@mapNotNull null
            val bitmap = runCatching { BitmapFactory.decodeFile(path.absolutePath) }.getOrNull() ?: return@mapNotNull null
            try {
                onGatewayStart?.invoke()
                OcrAssetRecognitionResult(
                    assetId = asset.id,
                    localPath = path.absolutePath,
                    recognition = gateway.recognize(bitmap),
                )
            } finally {
                bitmap.recycle()
            }
        }
        OcrPostRecognitionResult(
            clipId = clip.clip.id,
            assets = assetResults,
            fullText = assetResults
                .map { it.recognition.fullText.trim() }
                .filter(String::isNotBlank)
                .joinToString("\n\n"),
        )
    }

    private suspend fun invalidateUndoBeforeNodeMoveOrReorder() {
        val result = undoCoordinator.invalidateForUserEdit()
        check(result == UndoCoordinatorResult.Success || result == UndoCoordinatorResult.NoPendingUndo) {
            "以前のUndoを確定できませんでした"
        }
    }

    suspend fun moveClipToTrash(clip: ClipEntity) = withContext(Dispatchers.IO) {
        mediaGridPreviewStore.withPublishLock {
            val invalidated = undoCoordinator.invalidateForUserEdit()
            check(invalidated == UndoCoordinatorResult.Success || invalidated == UndoCoordinatorResult.NoPendingUndo) {
                "以前のUndoを確定できませんでした"
            }
            val snapshot = postStorageManager.withDatabase { database ->
                val clipDao = database.clipDao()
                val currentClip = checkNotNull(clipDao.getClip(clip.id)) { "投稿が見つかりません" }
                ClipDeletedUndoPayload(
                    clip = currentClip,
                    assets = clipDao.assetsForClipIds(listOf(clip.id)),
                    relations = clipDao.clipTagsForClipIds(listOf(clip.id)),
                    files = emptyList(),
                )
            }
            val hasImageStaging = snapshot.assets.any { asset ->
                asset.localPath?.let(::File)?.isFile == true
            }
            val isHeavyDelete = hasImageStaging || snapshot.assets.size + snapshot.relations.size > 1
            heavyLocalWorkTracker.trackIf(isHeavyDelete) {
                val prepared = clipDeleteUndoStore.prepare(
                    clipId = snapshot.clip.id,
                    assets = snapshot.assets,
                    imageDirectory = postStorageManager.imageDirectory(),
                )
                val payload = snapshot.copy(files = prepared.metadata)
                try {
                    undoCoordinator.commitDatabaseEdit(payload, "投稿を削除しました") { database ->
                        val clipDao = database.clipDao()
                        check(clipDao.getClip(payload.clip.id) == payload.clip) { "削除前に投稿が変更されました" }
                        check(clipDao.assetsForClipIds(listOf(payload.clip.id)) == payload.assets) {
                            "削除前に投稿画像が変更されました"
                        }
                        check(
                            clipDao.clipTagsForClipIds(listOf(payload.clip.id)).sortedWith(
                                compareBy(ClipTagEntity::tagId, ClipTagEntity::createdAt),
                            ) == payload.relations.sortedWith(compareBy(ClipTagEntity::tagId, ClipTagEntity::createdAt)),
                        ) { "削除前に投稿タグが変更されました" }
                        clipDao.deleteClip(payload.clip.id)
                        check(clipDao.getClip(payload.clip.id) == null) { "投稿を削除できませんでした" }
                    }
                } catch (error: Throwable) {
                    clipDeleteUndoStore.discardPrepared(prepared.metadata)
                    throw error
                }
                clipDeleteUndoStore.discardOriginals(prepared)
                payload.assets.forEach { asset -> runCatching { mediaGridPreviewStore.deletePreviewUnsafe(asset.id) } }
            }
        }
    }

    suspend fun recoverMediaGridPreview(
        assetId: Long,
        expectedPreviewIdentity: String,
    ) = withContext(Dispatchers.IO) {
        val asset = postStorageManager.withDatabase { database -> database.clipDao().getAsset(assetId) }
            ?: return@withContext
        val localPath = asset.localPath?.trim()?.takeIf(String::isNotEmpty) ?: return@withContext
        if (!File(localPath).isFile) return@withContext
        val preview = mediaGridPreviewStore.previewFile(assetId)
        if (!preview.isFile || preview.length() <= 0L) return@withContext
        val currentIdentity = "preview-v1|$assetId|${asset.mediaKey}|${preview.absolutePath}|${preview.length()}|${preview.lastModified()}"
        if (currentIdentity != expectedPreviewIdentity) return@withContext
        if (mediaGridPreviewStore.deletePreview(assetId)) {
            mediaGridPreviewEnqueuer.enqueue(listOf(assetId))
        }
    }

    internal suspend fun recoverMediaGridCandidate(
        assetId: Long,
        candidate: MediaGridPreparedCandidate,
    ) {
        when (candidate.kind) {
            MediaGridImageSourceKind.Rgb565Pack ->
                mediaGridRgb565RepairEnqueuer.enqueue(listOf(assetId))
            MediaGridImageSourceKind.PersistentPreview ->
                recoverMediaGridPreview(assetId, candidate.sourceIdentity)
            else -> Unit
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
