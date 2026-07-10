package com.lyco256.llm.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeDatasetIntegrationTest {
    private lateinit var database: LikeListDatabase

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LikeListDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun tenThousandClipsRemainCompleteAndDeterministicallyOrdered() = runBlocking {
        val dao = database.clipDao()
        database.withTransaction {
            repeat(10_000) { index ->
                val ordinal = index.toString().padStart(5, '0')
                dao.insertClip(
                    ClipEntity(
                        xPostId = "large-$ordinal",
                        authorName = "Author ${index % 50}",
                        authorUsername = "author${index % 50}",
                        text = if (index % 1_000 == 0) "needle-$ordinal" else "body-$ordinal",
                        postUrl = "https://x.com/author/status/large-$ordinal",
                        xCreatedAt = "2026-01-01T00:00:00Z",
                        savedAt = ordinal,
                        syncedAt = "2026-01-01T00:00:00Z",
                        summary = if (index % 2 == 0) "summary" else "",
                        ocrText = if (index % 1_000 == 0) "ocr-$ordinal" else "",
                    ),
                )
            }
        }

        val clips = dao.getActiveClips()
        assertEquals(10_000, dao.countClips())
        assertEquals(10_000, clips.size)
        assertEquals("large-09999", clips.first().xPostId)
        assertEquals("large-00000", clips.last().xPostId)
        assertEquals(10, clips.count { it.text.startsWith("needle-") })
        assertEquals(10, clips.count { it.ocrText.startsWith("ocr-") })
    }

    @Test
    fun fiftyItemRiskSeedCoversEmptyFieldsMediaTagsAndDeepGroups() = runBlocking {
        val clipDao = database.clipDao()
        val tagDao = database.tagDao()
        val now = "2026-01-01T00:00:00Z"
        val clipIds = mutableListOf<Long>()
        database.withTransaction {
            val root = tagDao.insertGroup(TagGroupEntity(name = "root", createdAt = now, updatedAt = now))
            val child = tagDao.insertGroup(TagGroupEntity(name = "child", parentGroupId = root, createdAt = now, updatedAt = now))
            val sibling = tagDao.insertGroup(TagGroupEntity(name = "sibling", createdAt = now, updatedAt = now))
            tagDao.insertGroup(TagGroupEntity(name = "empty", createdAt = now, updatedAt = now))
            val sameNameA = tagDao.insertTag(TagEntity(name = "same-name", parentGroupId = child, createdAt = now, updatedAt = now))
            val sameNameB = tagDao.insertTag(TagEntity(name = "same-name", parentGroupId = sibling, createdAt = now, updatedAt = now))
            val rootTag = tagDao.insertTag(TagEntity(name = "root-tag", createdAt = now, updatedAt = now))

            repeat(50) { index ->
                val clipId = clipDao.insertClip(
                    ClipEntity(
                        xPostId = "risk-$index",
                        authorId = if (index % 7 == 0) null else "author-${index % 5}",
                        authorName = if (index % 7 == 0) "" else "Author ${index % 5}",
                        authorUsername = if (index % 7 == 0) "" else "author${index % 5}",
                        text = if (index % 10 == 0) "" else "body-$index",
                        postUrl = "https://x.com/author/status/risk-$index",
                        xCreatedAt = now,
                        savedAt = index.toString().padStart(2, '0'),
                        syncedAt = now,
                        summary = if (index % 2 == 0) "manual-summary-$index" else "",
                        isDeleted = index == 49,
                    ),
                )
                clipIds += clipId
                when (index % 3) {
                    0 -> clipDao.insertClipTag(ClipTagEntity(clipId, sameNameA, now))
                    1 -> {
                        clipDao.insertClipTag(ClipTagEntity(clipId, sameNameB, now))
                        clipDao.insertClipTag(ClipTagEntity(clipId, rootTag, now))
                    }
                }
                val assets = when (index % 10) {
                    1 -> listOf(asset(clipId, "photo-$index", "photo", now))
                    2 -> listOf(
                        asset(clipId, "photo-${index}-a", "photo", now),
                        asset(clipId, "photo-${index}-b", "photo", now),
                    )
                    3 -> listOf(asset(clipId, "video-$index", "video_thumbnail", now))
                    4 -> listOf(asset(clipId, "gif-$index", "video_thumbnail", now))
                    else -> emptyList()
                }
                clipDao.insertAssets(assets)
            }
        }

        val all = clipDao.getActiveClips()
        val assets = clipDao.getAllAssets()
        val relations = clipDao.clipTagsForClipIds(clipIds)
        assertEquals(50, clipDao.countClips())
        assertEquals(49, all.size)
        assertEquals(5, all.count { it.text.isEmpty() })
        assertTrue(all.any { it.summary.startsWith("manual-summary-") })
        assertTrue(all.any { it.authorId == null && it.authorName.isEmpty() })
        assertTrue(assets.any { it.type == "photo" })
        assertTrue(assets.any { it.type == "video_thumbnail" && it.mediaKey.startsWith("video-") })
        assertTrue(assets.any { it.type == "video_thumbnail" && it.mediaKey.startsWith("gif-") })
        assertTrue(relations.groupBy { it.clipId }.values.any { it.size == 2 })
        assertEquals(4, tagDao.getGroups().size)
        assertEquals(3, tagDao.getTags().size)
    }

    @Test
    fun mediaGridDaoQueriesReturnOnlyActiveSupportedRowsAndDistinctTags() = runBlocking {
        val clipDao = database.clipDao()
        val tagDao = database.tagDao()
        val deletedAt = "2026-06-15T08:00:00Z"
        val newerAt = "2026-06-15T11:00:00Z"
        val olderAt = "2026-06-15T09:00:00Z"
        database.withTransaction {
            val tagA = tagDao.insertTag(TagEntity(name = "tag-a", createdAt = deletedAt, updatedAt = deletedAt))
            val tagB = tagDao.insertTag(TagEntity(name = "tag-b", createdAt = deletedAt, updatedAt = deletedAt))
            val tagC = tagDao.insertTag(TagEntity(name = "tag-c", createdAt = deletedAt, updatedAt = deletedAt))

            val mediaFirst = clipDao.insertClip(
                ClipEntity(
                    xPostId = "media-first",
                    authorName = "First",
                    authorUsername = "first",
                    text = "first media clip",
                    postUrl = "https://x.com/first/status/media-first",
                    xCreatedAt = olderAt,
                    savedAt = olderAt,
                    syncedAt = olderAt,
                ),
            )
            clipDao.insertClipTag(ClipTagEntity(mediaFirst, tagA, deletedAt))
            clipDao.insertClipTag(ClipTagEntity(mediaFirst, tagB, deletedAt))
            clipDao.insertAssets(
                listOf(
                    asset(mediaFirst, 11, "first-photo", "photo", olderAt),
                    asset(mediaFirst, 12, "first-video", "video_thumbnail", olderAt),
                    asset(mediaFirst, 13, "first-gif", "animated_gif", olderAt),
                ),
            )

            val deletedClip = clipDao.insertClip(
                ClipEntity(
                    xPostId = "deleted-media",
                    authorName = "Deleted",
                    authorUsername = "deleted",
                    text = "deleted media clip",
                    postUrl = "https://x.com/deleted/status/deleted-media",
                    xCreatedAt = deletedAt,
                    savedAt = deletedAt,
                    syncedAt = deletedAt,
                    isDeleted = true,
                ),
            )
            clipDao.insertClipTag(ClipTagEntity(deletedClip, tagC, deletedAt))
            clipDao.insertAssets(listOf(asset(deletedClip, 21, "deleted-photo", "photo", deletedAt)))

            val mediaSecond = clipDao.insertClip(
                ClipEntity(
                    xPostId = "media-second",
                    authorName = "Second",
                    authorUsername = "second",
                    text = "second media clip",
                    postUrl = "https://x.com/second/status/media-second",
                    xCreatedAt = newerAt,
                    savedAt = newerAt,
                    syncedAt = newerAt,
                ),
            )
            clipDao.insertClipTag(ClipTagEntity(mediaSecond, tagC, deletedAt))
            clipDao.insertAssets(
                listOf(
                    asset(mediaSecond, 31, "second-photo", "photo", newerAt),
                    asset(mediaSecond, 32, "second-thumb", "video_thumbnail", newerAt),
                ),
            )

            val noMedia = clipDao.insertClip(
                ClipEntity(
                    xPostId = "no-media",
                    authorName = "Plain",
                    authorUsername = "plain",
                    text = "plain clip",
                    postUrl = "https://x.com/plain/status/no-media",
                    xCreatedAt = newerAt,
                    savedAt = newerAt,
                    syncedAt = newerAt,
                ),
            )
            clipDao.insertClipTag(ClipTagEntity(noMedia, tagA, deletedAt))
        }

        val rows = clipDao.observeActiveMediaGridAssetRows().first()
        val tags = clipDao.observeActiveMediaGridClipTags().first()

        assertEquals(listOf(31L, 32L, 11L, 12L), rows.map { it.assetId })
        assertEquals(listOf("photo", "video_thumbnail", "photo", "video_thumbnail"), rows.map { it.assetType })
        assertTrue(rows.none { it.assetType == "animated_gif" })
        assertTrue(rows.none { it.xPostId == "deleted-media" })
        assertEquals(setOf(31L, 32L, 11L, 12L), rows.map { it.assetId }.toSet())
        assertEquals(3, tags.size)
        assertEquals(3, tags.map { it.tagId }.toSet().size)
    }

    @Test
    fun mediaGridQueryHandlesTenThousandClipsWithoutTimingOut() = runBlocking {
        val clipDao = database.clipDao()
        database.withTransaction {
            repeat(10_000) { index ->
                val ordinal = index.toString().padStart(5, '0')
                val clipId = clipDao.insertClip(
                    ClipEntity(
                        xPostId = "grid-$ordinal",
                        authorName = "Author ${index % 50}",
                        authorUsername = "author${index % 50}",
                        text = if (index % 1_000 == 0) "needle-$ordinal" else "body-$ordinal",
                        postUrl = "https://x.com/author/status/grid-$ordinal",
                        xCreatedAt = "2026-01-01T00:00:00Z",
                        savedAt = ordinal,
                        syncedAt = "2026-01-01T00:00:00Z",
                    ),
                )
                if (index % 10 == 0) {
                    clipDao.insertAssets(
                        listOf(
                            asset(clipId, "grid-photo-$ordinal", "photo", "2026-01-01T00:00:00Z"),
                            asset(clipId, "grid-thumb-$ordinal", "video_thumbnail", "2026-01-01T00:00:00Z"),
                            asset(clipId, "grid-ignored-$ordinal", "animated_gif", "2026-01-01T00:00:00Z"),
                        ),
                    )
                }
            }
        }

        val rows = clipDao.observeActiveMediaGridAssetRows().first()
        assertEquals(2_000, rows.size)
        assertEquals(2L, rows.take(2).map { it.assetId }.distinct().size.toLong())
        assertEquals(listOf("photo", "video_thumbnail"), rows.take(2).map { it.assetType })
        assertTrue(rows.none { it.assetType == "animated_gif" })

        val selectedClipId = rows.first().clipId
        assertEquals(selectedClipId, clipDao.observeActiveClip(selectedClipId).first()?.id)
        assertEquals(2, clipDao.observeAssetsForClip(selectedClipId).first().size)
    }

    private fun asset(clipId: Long, id: Long, mediaKey: String, type: String, now: String) = AssetEntity(
        id = id,
        clipId = clipId,
        mediaKey = mediaKey,
        type = type,
        remoteUrl = "https://example.test/$mediaKey",
        previewUrl = null,
        createdAt = now,
    )

    private fun asset(clipId: Long, mediaKey: String, type: String, now: String) = AssetEntity(
        clipId = clipId,
        mediaKey = mediaKey,
        type = type,
        remoteUrl = "https://example.test/$mediaKey",
        previewUrl = null,
        createdAt = now,
    )
}
