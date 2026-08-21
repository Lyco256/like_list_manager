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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        File(context.filesDir, DurableClipDeleteUndoStore.DIRECTORY_NAME).deleteRecursively()
        File(context.filesDir, "media_grid_rgb565_packs").deleteRecursively()
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

    private fun newRepository(
        previewEnqueuer: MediaGridPreviewEnqueuer = NoOpMediaGridPreviewEnqueuer,
        repairEnqueuer: MediaGridRgb565RepairEnqueuer = NoOpMediaGridRgb565RepairEnqueuer,
        rgb565Publisher: MediaGridRgb565Publisher = MediaGridRgb565PackStore(context.filesDir),
        deleteUndoStore: DurableClipDeleteUndoStore = DurableClipDeleteUndoStore(context.filesDir),
        heavyLocalWorkTracker: HeavyLocalWorkTracker = HeavyLocalWorkTracker(),
    ) = ClipRepository(
        context = context,
        postStorageManager = storage,
        apiSettingsStore = settings,
        xOAuthManager = oauth,
        xApiClient = api,
        ocrTextGateway = FakeOcrTextGateway { bitmap ->
            when {
                bitmap.width == 1 && bitmap.height == 1 -> OcrRecognitionResult(bitmap.width, bitmap.height, "")
                bitmap.width == 2 && bitmap.height == 2 -> throw IllegalStateException("Fake OCR failure")
                bitmap.width > bitmap.height -> OcrRecognitionResult(
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    fullText = "Landscape OCR\nSecond line",
                    regions = listOf(
                        OcrTextRegion(
                            text = "Landscape OCR",
                            polygon = OcrPolygon(
                                listOf(
                                    OcrPoint(0f, 0f),
                                    OcrPoint(2f, 0f),
                                    OcrPoint(2f, 1f),
                                    OcrPoint(0f, 1f),
                                ),
                            ),
                            confidence = 0.9f,
                        ),
                        OcrTextRegion(
                            text = "Second line",
                            confidence = 0.8f,
                            precedingSeparator = "\n",
                        ),
                    ),
                )
                else -> OcrRecognitionResult(bitmap.width, bitmap.height, "Portrait OCR")
            }
        },
        mediaGridPreviewEnqueuer = previewEnqueuer,
        mediaGridRgb565RepairEnqueuer = repairEnqueuer,
        mediaGridRgb565PackStore = rgb565Publisher,
        clipDeleteUndoStore = deleteUndoStore,
        heavyLocalWorkTracker = heavyLocalWorkTracker,
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
    fun summaryEditUndoRestoresOnlySummaryAndPreservesNewerClipFieldsAndRelations() = runBlocking {
        val relationCreatedAt = "2024-01-02T03:04:05Z"
        val fixture = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("summary-undo", summary = "before"))
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "Summary tag", createdAt = relationCreatedAt, updatedAt = relationCreatedAt),
            )
            database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, relationCreatedAt))
            requireNotNull(database.clipDao().getClip(clipId)) to tagId
        }
        val (staleClipSnapshot, tagId) = fixture

        storage.withDatabase { database ->
            database.clipDao().updateLikeCount(staleClipSnapshot.id, 10, "2026-08-12T01:00:00Z")
        }
        repository.updateSummary(staleClipSnapshot, "after")

        storage.withDatabase { database ->
            val saved = requireNotNull(database.clipDao().getClip(staleClipSnapshot.id))
            assertEquals("after", saved.summary)
            assertEquals(10L, saved.likeCount)
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.SUMMARY_EDITED.storageValue, slot.actionType)
            val payload = (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success)
                .payload as SummaryEditedUndoPayload
            assertEquals(SummaryEditedUndoPayload(staleClipSnapshot.id, "before"), payload)

            database.clipDao().updateLikeCount(staleClipSnapshot.id, 20, "2026-08-12T02:00:00Z")
            database.clipDao().updateOcrText(staleClipSnapshot.id, "new OCR", "2026-08-12T02:01:00Z")
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val restored = requireNotNull(database.clipDao().getClip(staleClipSnapshot.id))
            assertEquals("before", restored.summary)
            assertEquals(20L, restored.likeCount)
            assertEquals("2026-08-12T02:00:00Z", restored.likeCountFetchedAt)
            assertEquals("new OCR", restored.ocrText)
            assertEquals("2026-08-12T02:01:00Z", restored.ocrUpdatedAt)
            assertEquals(
                listOf(ClipTagEntity(staleClipSnapshot.id, tagId, relationCreatedAt)),
                database.clipDao().clipTagsForClipIds(listOf(staleClipSnapshot.id)),
            )
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun unchangedSummaryInvalidatesOldUndoWithoutCreatingAnotherSlot() = runBlocking {
        val now = "2024-01-02T03:04:05Z"
        val fixture = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("summary-no-op", summary = "same"))
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "Previous undo", createdAt = now, updatedAt = now),
            )
            requireNotNull(database.clipDao().getClip(clipId)) to tagId
        }
        val (clip, tagId) = fixture
        repository.setClipTags(clip.id, setOf(tagId))
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })

        repository.updateSummary(clip, "same")

        storage.withDatabase { database ->
            assertEquals("same", requireNotNull(database.clipDao().getClip(clip.id)).summary)
            assertEquals(listOf(tagId), database.clipDao().clipTagsForClipIds(listOf(clip.id)).map { it.tagId })
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun failedSummaryEditDoesNotCreateUndoSlot() = runBlocking {
        val missing = clip("missing-summary").copy(id = Long.MAX_VALUE, summary = "before")

        assertNotNull(runCatching { repository.updateSummary(missing, "after") }.exceptionOrNull())
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })
    }

    @Test
    fun ocrEditUndoRestoresOnlyOcrFieldsAndPreservesNewerClipFieldsAndRelations() = runBlocking {
        val originalOcrUpdatedAt = "2024-01-02T03:04:05Z"
        val relationCreatedAt = "2024-02-03T04:05:06Z"
        val fixture = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(
                clip("ocr-undo", summary = "original summary").copy(
                    ocrText = "before OCR",
                    ocrUpdatedAt = originalOcrUpdatedAt,
                    likeCount = 10,
                    likeCountFetchedAt = "2024-03-04T05:06:07Z",
                ),
            )
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "OCR tag", createdAt = relationCreatedAt, updatedAt = relationCreatedAt),
            )
            database.clipDao().insertClipTag(ClipTagEntity(clipId, tagId, relationCreatedAt))
            requireNotNull(database.clipDao().getClip(clipId)) to tagId
        }
        val (staleClipSnapshot, tagId) = fixture

        storage.withDatabase { database ->
            database.clipDao().updateLikeCount(staleClipSnapshot.id, 20, "2026-08-12T01:00:00Z")
        }
        repository.updateOcrText(staleClipSnapshot, "after OCR")

        storage.withDatabase { database ->
            val saved = requireNotNull(database.clipDao().getClip(staleClipSnapshot.id))
            assertEquals("after OCR", saved.ocrText)
            assertTrue(requireNotNull(saved.ocrUpdatedAt).isNotBlank())
            assertEquals(20L, saved.likeCount)
            assertEquals("2026-08-12T01:00:00Z", saved.likeCountFetchedAt)
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.OCR_EDITED.storageValue, slot.actionType)
            assertEquals("OCRを保存しました", slot.message)
            assertEquals(
                OcrEditedUndoPayload(staleClipSnapshot.id, "before OCR", originalOcrUpdatedAt),
                (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success).payload,
            )

            database.clipDao().updateLikeCount(staleClipSnapshot.id, 99, "2026-08-12T02:00:00Z")
            assertEquals(1, database.clipDao().updateSummary(staleClipSnapshot.id, "later summary"))
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val restored = requireNotNull(database.clipDao().getClip(staleClipSnapshot.id))
            assertEquals("before OCR", restored.ocrText)
            assertEquals(originalOcrUpdatedAt, restored.ocrUpdatedAt)
            assertEquals(99L, restored.likeCount)
            assertEquals("2026-08-12T02:00:00Z", restored.likeCountFetchedAt)
            assertEquals("later summary", restored.summary)
            assertEquals(
                listOf(ClipTagEntity(staleClipSnapshot.id, tagId, relationCreatedAt)),
                database.clipDao().clipTagsForClipIds(listOf(staleClipSnapshot.id)),
            )
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun firstOcrSaveUndoRestoresEmptyTextAndNullUpdatedAt() = runBlocking {
        val clip = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("ocr-first-save"))
            requireNotNull(database.clipDao().getClip(clipId))
        }

        repository.updateOcrText(clip, "first OCR")
        storage.withDatabase { database ->
            val saved = requireNotNull(database.clipDao().getClip(clip.id))
            assertEquals("first OCR", saved.ocrText)
            assertTrue(requireNotNull(saved.ocrUpdatedAt).isNotBlank())
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val restored = requireNotNull(database.clipDao().getClip(clip.id))
            assertEquals("", restored.ocrText)
            assertEquals(null, restored.ocrUpdatedAt)
        }
    }

    @Test
    fun unchangedOcrInvalidatesOldUndoWithoutUpdatingTimestampOrCreatingSlot() = runBlocking {
        val originalOcrUpdatedAt = "2024-01-02T03:04:05Z"
        val now = "2024-02-03T04:05:06Z"
        val fixture = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(
                clip("ocr-no-op").copy(ocrText = "same OCR", ocrUpdatedAt = originalOcrUpdatedAt),
            )
            val tagId = database.tagDao().insertTag(TagEntity(name = "Old undo", createdAt = now, updatedAt = now))
            requireNotNull(database.clipDao().getClip(clipId)) to tagId
        }
        val (clip, tagId) = fixture
        repository.setClipTags(clip.id, setOf(tagId))
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })

        repository.updateOcrText(clip.copy(ocrText = "stale OCR"), "same OCR")

        storage.withDatabase { database ->
            val unchanged = requireNotNull(database.clipDao().getClip(clip.id))
            assertEquals("same OCR", unchanged.ocrText)
            assertEquals(originalOcrUpdatedAt, unchanged.ocrUpdatedAt)
            assertEquals(listOf(tagId), database.clipDao().clipTagsForClipIds(listOf(clip.id)).map { it.tagId })
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun failedOcrSaveInvalidatesOldUndoAndDoesNotCreateNewSlot() = runBlocking {
        val now = "2024-01-02T03:04:05Z"
        val (clipId, tagId) = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("ocr-failure-source"))
            val tagId = database.tagDao().insertTag(TagEntity(name = "Old undo", createdAt = now, updatedAt = now))
            clipId to tagId
        }
        repository.setClipTags(clipId, setOf(tagId))
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })
        val missing = clip("ocr-missing").copy(id = Long.MAX_VALUE)

        assertNotNull(runCatching { repository.updateOcrText(missing, "after") }.exceptionOrNull())

        storage.withDatabase { database ->
            assertEquals(null, database.undoDao().getSlot())
            assertEquals(listOf(tagId), database.clipDao().clipTagsForClipIds(listOf(clipId)).map { it.tagId })
        }
    }

    @Test
    fun singleClipTagChangesUndoOnlyTheRelationDiffAndPreserveOtherUpdates() = runBlocking {
        val originalCreatedAt = "2024-01-02T03:04:05Z"
        val fixture = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("single-tag-undo"))
            val otherClipId = database.clipDao().insertClip(clip("single-tag-undo-other"))
            val originalTagId = database.tagDao().insertTag(
                TagEntity(name = "Original", createdAt = originalCreatedAt, updatedAt = originalCreatedAt),
            )
            val addedTagId = database.tagDao().insertTag(
                TagEntity(name = "Added", createdAt = originalCreatedAt, updatedAt = originalCreatedAt),
            )
            val replacementTagId = database.tagDao().insertTag(
                TagEntity(name = "Replacement", createdAt = originalCreatedAt, updatedAt = originalCreatedAt),
            )
            database.clipDao().insertClipTag(ClipTagEntity(clipId, originalTagId, originalCreatedAt))
            database.clipDao().insertClipTag(ClipTagEntity(otherClipId, originalTagId, originalCreatedAt))
            listOf(clipId, otherClipId, originalTagId, addedTagId, replacementTagId)
        }
        val (clipId, otherClipId, originalTagId, addedTagId, replacementTagId) = fixture

        repository.setClipTags(clipId, setOf(originalTagId, addedTagId))
        storage.withDatabase { database ->
            database.clipDao().updateLikeCount(clipId, 99, "2026-08-12T00:00:00Z")
            val payload = (UndoPayloadCodec.decode(requireNotNull(database.undoDao().getSlot()).payloadJson)
                as UndoPayloadDecodeResult.Success).payload as ClipTagChangeUndoPayload
            assertEquals(setOf(addedTagId), payload.addedRelations.map { it.tagId }.toSet())
            assertTrue(payload.removedRelations.isEmpty())
        }
        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        assertClipTagRelations(clipId, mapOf(originalTagId to originalCreatedAt))
        storage.withDatabase { database ->
            assertEquals(99L, database.clipDao().getAllClips().single { it.id == clipId }.likeCount)
            assertEquals(
                setOf(originalTagId),
                database.clipDao().clipTagsForClipIds(listOf(otherClipId)).map { it.tagId }.toSet(),
            )
        }

        repository.setClipTags(clipId, emptySet())
        assertTrue(storage.withDatabase { it.clipDao().clipTagsForClipIds(listOf(clipId)).isEmpty() })
        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        assertClipTagRelations(clipId, mapOf(originalTagId to originalCreatedAt))

        repository.setClipTags(clipId, setOf(replacementTagId))
        assertClipTagRelations(clipId, mapOf(replacementTagId to requireNotNull(
            storage.withDatabase { database ->
                database.clipDao().clipTagsForClipIds(listOf(clipId)).single().createdAt
            },
        )))
        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        assertClipTagRelations(clipId, mapOf(originalTagId to originalCreatedAt))
    }

    @Test
    fun internalSyncStateUpdateAfterTagApplyKeepsTheTagUndoSlot() = runBlocking {
        val now = "2026-08-13T00:00:00Z"
        val (clipId, tagId) = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("tag-apply-then-sync"))
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "Applied", createdAt = now, updatedAt = now),
            )
            clipId to tagId
        }

        repository.setClipTags(clipId, setOf(tagId))
        val slotBeforeSync = storage.withDatabase { database ->
            requireNotNull(database.undoDao().getSlot())
        }

        storage.withDatabase { database ->
            database.clipDao().upsertSyncState(SyncStateEntity(id = 1, lastSyncAt = "2026-08-13T00:01:00Z"))
        }

        assertEquals(slotBeforeSync, storage.withDatabase { it.undoDao().getSlot() })
        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            assertTrue(database.clipDao().clipTagsForClipIds(listOf(clipId)).isEmpty())
            assertEquals("2026-08-13T00:01:00Z", database.clipDao().getSyncState()?.lastSyncAt)
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun failedSingleClipTagChangeLeavesNoNewOrOldUndoSlot() = runBlocking {
        val now = "2024-01-02T03:04:05Z"
        val (clipId, tagId) = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("single-tag-failure"))
            val tagId = database.tagDao().insertTag(TagEntity(name = "Valid", createdAt = now, updatedAt = now))
            clipId to tagId
        }
        repository.setClipTags(clipId, setOf(tagId))
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })

        val failure = runCatching { repository.setClipTags(clipId, setOf(Long.MAX_VALUE)) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })
        assertClipTagRelations(clipId, mapOf(tagId to storage.withDatabase { database ->
            database.clipDao().clipTagsForClipIds(listOf(clipId)).single().createdAt
        }))
    }

    @Test
    fun createTagUndoDeletesOnlyTheCreatedIdAndReturnsItsPersistedEntity() = runBlocking {
        val now = "2024-01-02T03:04:05Z"
        val parentId = storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val parentId = tagDao.insertGroup(
                TagGroupEntity(name = "Parent", sortOrder = 0, createdAt = now, updatedAt = now),
            )
            tagDao.insertTag(
                TagEntity(
                    name = "Same name",
                    parentGroupId = parentId,
                    sortOrder = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            tagDao.insertTag(
                TagEntity(name = "Root sibling", sortOrder = 1, createdAt = now, updatedAt = now),
            )
            parentId
        }
        val before = storage.withDatabase { database ->
            database.tagDao().getGroups() to database.tagDao().getTags()
        }

        val created = repository.createTag("  Same name  ", colorId = TagColorId.PINK.id)

        assertTrue(created.id > 0)
        assertEquals("Same name", created.name)
        assertEquals(null, created.parentGroupId)
        assertEquals(TagColorId.PINK.id, created.colorId)
        storage.withDatabase { database ->
            assertEquals(created, database.tagDao().getTags().single { it.id == created.id })
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals("タグを作成しました", slot.message)
            val payload = (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success)
                .payload as TagCreatedUndoPayload
            assertEquals(created.id, payload.tagId)
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            assertEquals(before.first, database.tagDao().getGroups())
            assertEquals(before.second, database.tagDao().getTags())
            assertTrue(database.tagDao().getTags().any { it.parentGroupId == parentId && it.name == "Same name" })
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun createGroupUndoDeletesOnlyTheCreatedIdAndPreservesHierarchyAndSiblingOrder() = runBlocking {
        val now = "2024-01-02T03:04:05Z"
        val parentId = storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val parentId = tagDao.insertGroup(
                TagGroupEntity(name = "Parent", sortOrder = 0, createdAt = now, updatedAt = now),
            )
            tagDao.insertGroup(
                TagGroupEntity(
                    name = "Same name",
                    parentGroupId = parentId,
                    sortOrder = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            tagDao.insertGroup(
                TagGroupEntity(name = "Root sibling", sortOrder = 1, createdAt = now, updatedAt = now),
            )
            parentId
        }
        val before = storage.withDatabase { database ->
            database.tagDao().getGroups() to database.tagDao().getTags()
        }

        val created = repository.createGroup("Same name", colorId = TagColorId.BLUE.id)

        assertTrue(created.id > 0)
        assertEquals(TagColorId.BLUE.id, created.colorId)
        storage.withDatabase { database ->
            assertEquals(created, database.tagDao().getGroups().single { it.id == created.id })
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals("グループを作成しました", slot.message)
            val payload = (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success)
                .payload as GroupCreatedUndoPayload
            assertEquals(created.id, payload.groupId)
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            assertEquals(before.first, database.tagDao().getGroups())
            assertEquals(before.second, database.tagDao().getTags())
            assertTrue(database.tagDao().getGroups().any { it.parentGroupId == parentId && it.name == "Same name" })
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun failedCreationInvalidatesOldUndoAndDoesNotCreateANewSlot() = runBlocking {
        val created = repository.createGroup("Duplicate")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })

        val failure = runCatching { repository.createTag("Duplicate") }.exceptionOrNull()

        assertNotNull(failure)
        storage.withDatabase { database ->
            assertEquals(created, database.tagDao().getGroups().single { it.id == created.id })
            assertTrue(database.tagDao().getTags().isEmpty())
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun laterUserEditInvalidatesCreationUndoBeforeEditingTheCreatedTag() = runBlocking {
        val clipId = storage.withDatabase { database -> database.clipDao().insertClip(clip("creation-invalidation")) }
        val created = repository.createTag("Editable")

        repository.setClipTags(clipId, setOf(created.id))

        storage.withDatabase { database ->
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.CLIP_TAG_CHANGE.storageValue, slot.actionType)
            assertTrue(database.tagDao().getTags().any { it.id == created.id })
        }
        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            assertTrue(database.tagDao().getTags().any { it.id == created.id })
            assertTrue(database.clipDao().clipTagsForClipIds(listOf(clipId)).isEmpty())
        }
    }

    @Test
    fun tagNameEditUndoRestoresOnlyNameAndPreservesLaterColorHierarchyAndOrderChanges() = runBlocking {
        val originalAt = "2024-01-02T03:04:05Z"
        val fixture = storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val originalParentId = tagDao.insertGroup(
                TagGroupEntity(name = "Original parent", createdAt = originalAt, updatedAt = originalAt),
            )
            val laterParentId = tagDao.insertGroup(
                TagGroupEntity(name = "Later parent", sortOrder = 1, createdAt = originalAt, updatedAt = originalAt),
            )
            val tagId = tagDao.insertTag(
                TagEntity(
                    name = "Before name",
                    parentGroupId = originalParentId,
                    sortOrder = 2,
                    createdAt = originalAt,
                    updatedAt = originalAt,
                    colorId = TagColorId.BLUE.id,
                ),
            )
            Triple(tagDao.getTag(tagId)!!, originalParentId, laterParentId)
        }
        val (tag, _, laterParentId) = fixture

        repository.renameTag(tag, "  After name  ")

        storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val edited = tagDao.getTag(tag.id)!!
            assertEquals("After name", edited.name)
            assertEquals(TagColorId.BLUE.id, edited.colorId)
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.TAG_EDITED.storageValue, slot.actionType)
            assertEquals(
                TagEditedUndoPayload(tag.id, previousName = "Before name"),
                (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success).payload,
            )
            tagDao.updateTag(
                edited.copy(
                    parentGroupId = laterParentId,
                    sortOrder = 7,
                    colorId = TagColorId.PINK.id,
                    updatedAt = "2025-01-01T00:00:00Z",
                ),
            )
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val restored = database.tagDao().getTag(tag.id)!!
            assertEquals("Before name", restored.name)
            assertEquals(TagColorId.PINK.id, restored.colorId)
            assertEquals(laterParentId, restored.parentGroupId)
            assertEquals(7, restored.sortOrder)
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun tagColorEditUndoRestoresOnlyColorAndPreservesLaterNameChange() = runBlocking {
        val originalAt = "2024-01-02T03:04:05Z"
        val tag = storage.withDatabase { database ->
            val tagId = database.tagDao().insertTag(
                TagEntity(
                    name = "Color tag",
                    createdAt = originalAt,
                    updatedAt = originalAt,
                    colorId = TagColorId.GREEN.id,
                ),
            )
            database.tagDao().getTag(tagId)!!
        }

        repository.renameTag(tag.copy(colorId = TagColorId.ORANGE.id), tag.name)

        storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val edited = tagDao.getTag(tag.id)!!
            assertEquals(TagColorId.ORANGE.id, edited.colorId)
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(
                TagEditedUndoPayload(tag.id, previousColorId = TagColorId.GREEN.id),
                (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success).payload,
            )
            assertEquals(1, tagDao.updateTagName(tag.id, "Later name", "2025-01-01T00:00:00Z"))
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val restored = database.tagDao().getTag(tag.id)!!
            assertEquals("Later name", restored.name)
            assertEquals(TagColorId.GREEN.id, restored.colorId)
        }
    }

    @Test
    fun groupNameAndColorEditUsesOneUndoAndPreservesLaterParentAndOrderChanges() = runBlocking {
        val originalAt = "2024-01-02T03:04:05Z"
        val fixture = storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val originalParentId = tagDao.insertGroup(
                TagGroupEntity(name = "Group parent", createdAt = originalAt, updatedAt = originalAt),
            )
            val laterParentId = tagDao.insertGroup(
                TagGroupEntity(name = "Later group parent", sortOrder = 1, createdAt = originalAt, updatedAt = originalAt),
            )
            val groupId = tagDao.insertGroup(
                TagGroupEntity(
                    name = "Before group",
                    parentGroupId = originalParentId,
                    sortOrder = 3,
                    createdAt = originalAt,
                    updatedAt = originalAt,
                    colorId = TagColorId.CYAN.id,
                ),
            )
            tagDao.getGroup(groupId)!! to laterParentId
        }
        val (group, laterParentId) = fixture

        repository.renameGroup(group.copy(colorId = TagColorId.PURPLE.id), "After group")

        storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val edited = tagDao.getGroup(group.id)!!
            assertEquals("After group", edited.name)
            assertEquals(TagColorId.PURPLE.id, edited.colorId)
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.GROUP_EDITED.storageValue, slot.actionType)
            assertEquals(
                GroupEditedUndoPayload(
                    groupId = group.id,
                    previousName = "Before group",
                    previousColorId = TagColorId.CYAN.id,
                ),
                (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success).payload,
            )
            tagDao.updateGroup(
                edited.copy(
                    parentGroupId = laterParentId,
                    sortOrder = 9,
                    updatedAt = "2025-01-01T00:00:00Z",
                ),
            )
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val restored = database.tagDao().getGroup(group.id)!!
            assertEquals("Before group", restored.name)
            assertEquals(TagColorId.CYAN.id, restored.colorId)
            assertEquals(laterParentId, restored.parentGroupId)
            assertEquals(9, restored.sortOrder)
        }
    }

    @Test
    fun failedAndNoOpNodeEditsInvalidateOldUndoWithoutLeavingANewSlot() = runBlocking {
        val first = repository.createTag("First")
        repository.createGroup("Duplicate")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })

        val failure = runCatching { repository.renameTag(first, "Duplicate") }.exceptionOrNull()

        assertNotNull(failure)
        storage.withDatabase { database ->
            assertEquals("First", database.tagDao().getTag(first.id)?.name)
            assertEquals(null, database.undoDao().getSlot())
        }

        val unchanged = storage.withDatabase { it.tagDao().getTag(first.id)!! }
        repository.createGroup("Old undo")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })

        repository.renameTag(unchanged.copy(colorId = unchanged.colorId), "  ${unchanged.name}  ")

        storage.withDatabase { database ->
            assertEquals(unchanged, database.tagDao().getTag(first.id))
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun deleteTagUndoRestoresExactTagAndAllRelationsWithoutChangingOtherRelations() = runBlocking {
        val fixture = storage.withDatabase { database ->
            val parent = TagGroupEntity(
                name = "Parent",
                sortOrder = 3,
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-02T00:00:00Z",
            )
            val parentId = database.tagDao().insertGroup(parent)
            val firstClipId = database.clipDao().insertClip(clip("delete-tag-first"))
            val secondClipId = database.clipDao().insertClip(clip("delete-tag-second"))
            val tag = TagEntity(
                name = "Restore exactly",
                parentGroupId = parentId,
                sortOrder = 7,
                createdAt = "2024-02-01T01:02:03Z",
                updatedAt = "2024-03-04T05:06:07Z",
                colorId = TagColorId.PURPLE.id,
            )
            val tagId = database.tagDao().insertTag(tag)
            val persistedTag = tag.copy(id = tagId)
            val otherTagId = database.tagDao().insertTag(
                TagEntity(
                    name = "Other",
                    createdAt = "2024-04-01T00:00:00Z",
                    updatedAt = "2024-04-01T00:00:00Z",
                ),
            )
            val relations = listOf(
                ClipTagEntity(firstClipId, tagId, "2024-05-01T00:00:00Z"),
                ClipTagEntity(secondClipId, tagId, "2024-05-02T00:00:00Z"),
            )
            val otherRelation = ClipTagEntity(firstClipId, otherTagId, "2024-05-03T00:00:00Z")
            database.clipDao().insertClipTags(relations + otherRelation)
            Triple(persistedTag, relations, otherRelation)
        }
        val (tag, relations, otherRelation) = fixture

        repository.deleteTag(tag.id)

        storage.withDatabase { database ->
            assertEquals(null, database.tagDao().getTag(tag.id))
            assertTrue(database.clipDao().clipTagsForTag(tag.id).isEmpty())
            assertEquals(listOf(otherRelation), database.clipDao().clipTagsForTag(otherRelation.tagId))
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.TAG_DELETED.storageValue, slot.actionType)
            assertEquals(
                TagDeletedUndoPayload(tag, relations),
                (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success).payload,
            )
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())

        storage.withDatabase { database ->
            assertEquals(tag, database.tagDao().getTag(tag.id))
            assertEquals(relations, database.clipDao().clipTagsForTag(tag.id))
            assertEquals(listOf(otherRelation), database.clipDao().clipTagsForTag(otherRelation.tagId))
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun deleteTagUndoDoesNotOverwriteUnexpectedTagIdCollisionAndKeepsSlot() = runBlocking {
        val deleted = repository.createTag("Deleted")
        repository.deleteTag(deleted.id)
        val replacement = deleted.copy(
            name = "Unexpected replacement",
            updatedAt = "2025-01-01T00:00:00Z",
            colorId = TagColorId.RED.id,
        )
        storage.withDatabase { database ->
            assertEquals(deleted.id, database.tagDao().insertTag(replacement))
        }

        val result = repository.undoPendingEdit()

        assertTrue(result is UndoCoordinatorResult.Failure)
        storage.withDatabase { database ->
            assertEquals(replacement, database.tagDao().getTag(deleted.id))
            assertNotNull(database.undoDao().getSlot())
        }
    }

    @Test
    fun failedTagDeletionInvalidatesOldUndoAndCommitsNeitherDeleteNorNewSlot() = runBlocking {
        val existing = repository.createTag("Existing")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })

        val failure = runCatching { repository.deleteTag(Long.MAX_VALUE) }.exceptionOrNull()

        assertNotNull(failure)
        storage.withDatabase { database ->
            assertEquals(existing, database.tagDao().getTag(existing.id))
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun deleteTagUndoRollsBackTagAndRelationsWhenAReferencedClipIsMissing() = runBlocking {
        val fixture = storage.withDatabase { database ->
            val firstClipId = database.clipDao().insertClip(clip("missing-relation-first"))
            val missingClipId = database.clipDao().insertClip(clip("missing-relation-second"))
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "Deleted", createdAt = "created", updatedAt = "updated"),
            )
            database.clipDao().insertClipTags(
                listOf(
                    ClipTagEntity(firstClipId, tagId, "relation-first"),
                    ClipTagEntity(missingClipId, tagId, "relation-second"),
                ),
            )
            Triple(firstClipId, missingClipId, tagId)
        }
        val (firstClipId, missingClipId, tagId) = fixture
        repository.deleteTag(tagId)
        storage.withDatabase { it.clipDao().deleteClip(missingClipId) }

        val result = repository.undoPendingEdit()

        assertTrue(result is UndoCoordinatorResult.Failure)
        storage.withDatabase { database ->
            assertEquals(null, database.tagDao().getTag(tagId))
            assertTrue(database.clipDao().clipTagsForClipIds(listOf(firstClipId)).isEmpty())
            assertNotNull(database.undoDao().getSlot())
        }
    }

    @Test
    fun deleteEmptyGroupUndoRestoresEveryFieldAndPreservesParentAndSiblings() = runBlocking {
        val fixture = storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val parentId = tagDao.insertGroup(
                TagGroupEntity(
                    name = "Parent",
                    sortOrder = 2,
                    createdAt = "2024-01-01T00:00:00Z",
                    updatedAt = "2024-01-02T00:00:00Z",
                    colorId = TagColorId.BLUE.id,
                ),
            )
            tagDao.insertGroup(
                TagGroupEntity(
                    name = "Earlier sibling",
                    parentGroupId = parentId,
                    sortOrder = 1,
                    createdAt = "2024-02-01T00:00:00Z",
                    updatedAt = "2024-02-02T00:00:00Z",
                    colorId = TagColorId.GREEN.id,
                ),
            )
            val deletedId = tagDao.insertGroup(
                TagGroupEntity(
                    name = "Restore exactly",
                    parentGroupId = parentId,
                    sortOrder = 4,
                    createdAt = "2024-03-01T01:02:03Z",
                    updatedAt = "2024-04-05T06:07:08Z",
                    colorId = TagColorId.PURPLE.id,
                ),
            )
            tagDao.insertGroup(
                TagGroupEntity(
                    name = "Later sibling",
                    parentGroupId = parentId,
                    sortOrder = 9,
                    createdAt = "2024-05-01T00:00:00Z",
                    updatedAt = "2024-05-02T00:00:00Z",
                    colorId = TagColorId.RED.id,
                ),
            )
            tagDao.insertTag(
                TagEntity(
                    name = "Sibling tag",
                    parentGroupId = parentId,
                    sortOrder = 7,
                    createdAt = "2024-06-01T00:00:00Z",
                    updatedAt = "2024-06-02T00:00:00Z",
                    colorId = TagColorId.PINK.id,
                ),
            )
            Triple(
                requireNotNull(tagDao.getGroup(deletedId)),
                tagDao.getGroups(),
                tagDao.getTags(),
            )
        }
        val (deleted, groupsBefore, tagsBefore) = fixture

        repository.deleteGroup(deleted.id)

        storage.withDatabase { database ->
            assertEquals(null, database.tagDao().getGroup(deleted.id))
            assertEquals(groupsBefore.filterNot { it.id == deleted.id }, database.tagDao().getGroups())
            assertEquals(tagsBefore, database.tagDao().getTags())
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.GROUP_DELETED.storageValue, slot.actionType)
            assertEquals("グループを削除しました", slot.message)
            assertEquals(
                GroupDeletedUndoPayload(deleted),
                (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success).payload,
            )
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())

        storage.withDatabase { database ->
            assertEquals(deleted, database.tagDao().getGroup(deleted.id))
            assertEquals(groupsBefore, database.tagDao().getGroups())
            assertEquals(tagsBefore, database.tagDao().getTags())
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun deleteNonEmptyGroupStillFailsAndInvalidatesPreviousUndo() = runBlocking {
        val fixture = storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val parentId = tagDao.insertGroup(
                TagGroupEntity(name = "Non-empty", createdAt = "created", updatedAt = "updated"),
            )
            val childGroupId = tagDao.insertGroup(
                TagGroupEntity(
                    name = "Child group",
                    parentGroupId = parentId,
                    createdAt = "created",
                    updatedAt = "updated",
                ),
            )
            val childTagId = tagDao.insertTag(
                TagEntity(
                    name = "Child tag",
                    parentGroupId = parentId,
                    sortOrder = 1,
                    createdAt = "created",
                    updatedAt = "updated",
                ),
            )
            Triple(parentId, childGroupId, childTagId)
        }
        repository.createTag("Previous undo")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })

        val failure = runCatching { repository.deleteGroup(fixture.first) }.exceptionOrNull()

        assertNotNull(failure)
        storage.withDatabase { database ->
            assertNotNull(database.tagDao().getGroup(fixture.first))
            assertNotNull(database.tagDao().getGroup(fixture.second))
            assertNotNull(database.tagDao().getTag(fixture.third))
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun deleteGroupUndoDoesNotOverwriteIdCollisionAndKeepsSlot() = runBlocking {
        val deleted = repository.createGroup("Deleted")
        repository.deleteGroup(deleted.id)
        val replacement = deleted.copy(
            name = "Unexpected replacement",
            updatedAt = "2025-01-01T00:00:00Z",
            colorId = TagColorId.RED.id,
        )
        storage.withDatabase { database ->
            assertEquals(deleted.id, database.tagDao().insertGroup(replacement))
        }

        val result = repository.undoPendingEdit()

        assertTrue(result is UndoCoordinatorResult.Failure)
        storage.withDatabase { database ->
            assertEquals(replacement, database.tagDao().getGroup(deleted.id))
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.GROUP_DELETED.storageValue, slot.actionType)
        }
    }

    @Test
    fun deleteGroupUndoFailsWhenParentDisappearsAndKeepsSlot() = runBlocking {
        val (parentId, deleted) = storage.withDatabase { database ->
            val tagDao = database.tagDao()
            val parentId = tagDao.insertGroup(
                TagGroupEntity(name = "Parent", createdAt = "created", updatedAt = "updated"),
            )
            val childId = tagDao.insertGroup(
                TagGroupEntity(
                    name = "Deleted child",
                    parentGroupId = parentId,
                    sortOrder = 3,
                    createdAt = "child-created",
                    updatedAt = "child-updated",
                ),
            )
            parentId to requireNotNull(tagDao.getGroup(childId))
        }
        repository.deleteGroup(deleted.id)
        storage.withDatabase { database ->
            assertEquals(1, database.tagDao().deleteGroup(parentId))
        }

        val result = repository.undoPendingEdit()

        assertTrue(result is UndoCoordinatorResult.Failure)
        storage.withDatabase { database ->
            assertEquals(null, database.tagDao().getGroup(parentId))
            assertEquals(null, database.tagDao().getGroup(deleted.id))
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.GROUP_DELETED.storageValue, slot.actionType)
        }
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
    fun bulkTagChangesUndoOnlyActualRelationDiffAsOneSlot() = runBlocking {
        val firstRemovedAt = "2024-02-01T00:00:00Z"
        val secondRemovedAt = "2024-02-02T00:00:00Z"
        val existingAddedAt = "2024-01-01T00:00:00Z"
        val unrelatedAt = "2023-12-01T00:00:00Z"
        val fixture = storage.withDatabase { database ->
            val firstClipId = database.clipDao().insertClip(clip("bulk-undo-first"))
            val secondClipId = database.clipDao().insertClip(clip("bulk-undo-second"))
            val untouchedClipId = database.clipDao().insertClip(clip("bulk-undo-untouched"))
            val addTagId = database.tagDao().insertTag(
                TagEntity(name = "Bulk add", createdAt = existingAddedAt, updatedAt = existingAddedAt),
            )
            val removeTagId = database.tagDao().insertTag(
                TagEntity(name = "Bulk remove", createdAt = existingAddedAt, updatedAt = existingAddedAt),
            )
            val unrelatedTagId = database.tagDao().insertTag(
                TagEntity(name = "Unrelated", createdAt = unrelatedAt, updatedAt = unrelatedAt),
            )
            database.clipDao().insertClipTag(ClipTagEntity(firstClipId, addTagId, existingAddedAt))
            database.clipDao().insertClipTag(ClipTagEntity(firstClipId, removeTagId, firstRemovedAt))
            database.clipDao().insertClipTag(ClipTagEntity(secondClipId, removeTagId, secondRemovedAt))
            database.clipDao().insertClipTag(ClipTagEntity(firstClipId, unrelatedTagId, unrelatedAt))
            database.clipDao().insertClipTag(ClipTagEntity(secondClipId, unrelatedTagId, unrelatedAt))
            database.clipDao().insertClipTag(ClipTagEntity(untouchedClipId, removeTagId, unrelatedAt))
            listOf(firstClipId, secondClipId, untouchedClipId, addTagId, removeTagId, unrelatedTagId)
        }
        val firstClipId = fixture[0]
        val secondClipId = fixture[1]
        val untouchedClipId = fixture[2]
        val addTagId = fixture[3]
        val removeTagId = fixture[4]
        val unrelatedTagId = fixture[5]

        repository.applyClipTagChanges(
            clipIds = listOf(firstClipId, firstClipId, secondClipId).toSet(),
            pendingAddTagIds = setOf(addTagId),
            pendingRemoveTagIds = setOf(removeTagId),
        )

        storage.withDatabase { database ->
            val slot = requireNotNull(database.undoDao().getSlot())
            val payload = (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success)
                .payload as ClipTagChangeUndoPayload
            assertEquals(setOf(secondClipId to addTagId), payload.addedRelations.map { it.clipId to it.tagId }.toSet())
            assertEquals(
                mapOf(firstClipId to firstRemovedAt, secondClipId to secondRemovedAt),
                payload.removedRelations.associate { it.clipId to it.createdAt },
            )
            assertEquals(payload.addedRelations.size, payload.addedRelations.map { it.clipId to it.tagId }.toSet().size)
            assertEquals(payload.removedRelations.size, payload.removedRelations.map { it.clipId to it.tagId }.toSet().size)
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        assertClipTagRelations(
            firstClipId,
            mapOf(addTagId to existingAddedAt, removeTagId to firstRemovedAt, unrelatedTagId to unrelatedAt),
        )
        assertClipTagRelations(
            secondClipId,
            mapOf(removeTagId to secondRemovedAt, unrelatedTagId to unrelatedAt),
        )
        assertClipTagRelations(untouchedClipId, mapOf(removeTagId to unrelatedAt))
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })
    }

    @Test
    fun bulkTagChangesWithNoRelationDiffCreatesNoUndoSlot() = runBlocking {
        val now = "2024-01-01T00:00:00Z"
        val fixture = storage.withDatabase { database ->
            val firstClipId = database.clipDao().insertClip(clip("bulk-no-diff-first"))
            val secondClipId = database.clipDao().insertClip(clip("bulk-no-diff-second"))
            val presentTagId = database.tagDao().insertTag(
                TagEntity(name = "Already present", createdAt = now, updatedAt = now),
            )
            val absentTagId = database.tagDao().insertTag(
                TagEntity(name = "Already absent", createdAt = now, updatedAt = now),
            )
            database.clipDao().insertClipTags(
                listOf(
                    ClipTagEntity(firstClipId, presentTagId, now),
                    ClipTagEntity(secondClipId, presentTagId, now),
                ),
            )
            listOf(firstClipId, secondClipId, presentTagId, absentTagId)
        }
        val (firstClipId, secondClipId, presentTagId, absentTagId) = fixture

        repository.applyClipTagChanges(
            clipIds = setOf(firstClipId, secondClipId),
            pendingAddTagIds = setOf(presentTagId),
            pendingRemoveTagIds = setOf(absentTagId),
        )

        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })
        assertClipTagRelations(firstClipId, mapOf(presentTagId to now))
        assertClipTagRelations(secondClipId, mapOf(presentTagId to now))
    }

    @Test
    fun bulkTagChangesRemainConsistentForLargeSelectionAndUndo() = runBlocking {
        val now = "2024-01-01T00:00:00Z"
        val fixture = storage.withDatabase { database ->
            val addTagId = database.tagDao().insertTag(TagEntity(name = "Large add", createdAt = now, updatedAt = now))
            val removeTagId = database.tagDao().insertTag(TagEntity(name = "Large remove", createdAt = now, updatedAt = now))
            val clipIds = (0 until 200).map { index ->
                database.clipDao().insertClip(clip("bulk-large-$index"))
            }
            database.clipDao().insertClipTags(clipIds.map { ClipTagEntity(it, removeTagId, now) })
            Triple(clipIds, addTagId, removeTagId)
        }
        val (clipIds, addTagId, removeTagId) = fixture

        repository.applyClipTagChanges(clipIds.toSet(), setOf(addTagId), setOf(removeTagId))
        storage.withDatabase { database ->
            val changed = database.clipDao().clipTagsForClipIds(clipIds)
            assertEquals(clipIds.size, changed.size)
            assertTrue(changed.all { it.tagId == addTagId })
            val payload = (UndoPayloadCodec.decode(requireNotNull(database.undoDao().getSlot()).payloadJson)
                as UndoPayloadDecodeResult.Success).payload as ClipTagChangeUndoPayload
            assertEquals(clipIds.size, payload.addedRelations.size)
            assertEquals(clipIds.size, payload.removedRelations.size)
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val restored = database.clipDao().clipTagsForClipIds(clipIds)
            assertEquals(clipIds.size, restored.size)
            assertTrue(restored.all { it.tagId == removeTagId && it.createdAt == now })
        }
    }

    @Test
    fun bulkTagChangesPublishHeavyLocalWorkWithoutLeavingTrackerActive() = runBlocking {
        val tracker = HeavyLocalWorkTracker()
        val trackedRepository = newRepository(heavyLocalWorkTracker = tracker)
        val activeStates = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            tracker.isActive.collect { activeStates += it }
        }
        val now = Instant.now().toString()
        val fixture = storage.withDatabase { database ->
            val tagId = database.tagDao().insertTag(TagEntity(name = "Tracked bulk", createdAt = now, updatedAt = now))
            val clipIds = (0 until 100).map { index -> database.clipDao().insertClip(clip("tracked-bulk-$index")) }
            clipIds to tagId
        }

        trackedRepository.applyClipTagChanges(fixture.first.toSet(), setOf(fixture.second), emptySet())
        yield()

        assertTrue(activeStates.contains(true))
        assertFalse(tracker.isActive.value)
        collector.cancelAndJoin()
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

    @Test
    fun addAllFromTagToTagUndoRemovesOnlyTheSevenNewTargetRelations() = runBlocking {
        val sourceCreatedAt = "2024-01-01T00:00:00Z"
        val existingTargetCreatedAt = "2024-02-01T00:00:00Z"
        val fixture = storage.withDatabase { database ->
            val sourceTagId = database.tagDao().insertTag(
                TagEntity(name = "Add-all source", createdAt = sourceCreatedAt, updatedAt = sourceCreatedAt),
            )
            val targetTagId = database.tagDao().insertTag(
                TagEntity(name = "Add-all target", createdAt = sourceCreatedAt, updatedAt = sourceCreatedAt),
            )
            val clipIds = (0 until 10).map { index ->
                database.clipDao().insertClip(clip("add-all-undo-$index"))
            }
            database.clipDao().insertClipTags(
                clipIds.map { ClipTagEntity(it, sourceTagId, sourceCreatedAt) } +
                    clipIds.take(3).map { ClipTagEntity(it, targetTagId, existingTargetCreatedAt) },
            )
            Triple(clipIds, sourceTagId, targetTagId)
        }
        val (clipIds, sourceTagId, targetTagId) = fixture

        repository.addAllFromTagToTag(sourceTagId, targetTagId)

        storage.withDatabase { database ->
            val relations = database.clipDao().clipTagsForClipIds(clipIds)
            val sourceRelations = relations.filter { it.tagId == sourceTagId }
            val targetRelations = relations.filter { it.tagId == targetTagId }
            assertEquals(10, sourceRelations.size)
            assertTrue(sourceRelations.all { it.createdAt == sourceCreatedAt })
            assertEquals(10, targetRelations.size)
            assertEquals(
                clipIds.take(3).associateWith { existingTargetCreatedAt },
                targetRelations.filter { it.clipId in clipIds.take(3) }.associate { it.clipId to it.createdAt },
            )

            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.CLIP_TAG_CHANGE.storageValue, slot.actionType)
            val payload = (UndoPayloadCodec.decode(slot.payloadJson) as UndoPayloadDecodeResult.Success)
                .payload as ClipTagChangeUndoPayload
            assertEquals(clipIds.drop(3).toSet(), payload.addedRelations.map { it.clipId }.toSet())
            assertTrue(payload.addedRelations.all { it.tagId == targetTagId })
            assertTrue(payload.removedRelations.isEmpty())
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val relations = database.clipDao().clipTagsForClipIds(clipIds)
            assertEquals(
                clipIds.associateWith { sourceCreatedAt },
                relations.filter { it.tagId == sourceTagId }.associate { it.clipId to it.createdAt },
            )
            assertEquals(
                clipIds.take(3).associateWith { existingTargetCreatedAt },
                relations.filter { it.tagId == targetTagId }.associate { it.clipId to it.createdAt },
            )
            assertEquals(null, database.undoDao().getSlot())
        }
    }

    @Test
    fun addAllFromTagToTagWithNoNewRelationCreatesNoUndoSlot() = runBlocking {
        val now = "2024-01-01T00:00:00Z"
        val (sourceTagId, targetTagId) = storage.withDatabase { database ->
            val sourceTagId = database.tagDao().insertTag(
                TagEntity(name = "No-op source", createdAt = now, updatedAt = now),
            )
            val targetTagId = database.tagDao().insertTag(
                TagEntity(name = "No-op target", createdAt = now, updatedAt = now),
            )
            val clipIds = (0 until 4).map { index -> database.clipDao().insertClip(clip("add-all-no-op-$index")) }
            database.clipDao().insertClipTags(
                clipIds.flatMap { clipId ->
                    listOf(ClipTagEntity(clipId, sourceTagId, now), ClipTagEntity(clipId, targetTagId, now))
                },
            )
            sourceTagId to targetTagId
        }

        repository.addAllFromTagToTag(sourceTagId, targetTagId)

        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })
        storage.withDatabase { database ->
            assertEquals(4, database.clipDao().clipTagsForTag(sourceTagId).size)
            assertEquals(4, database.clipDao().clipTagsForTag(targetTagId).size)
        }
    }

    @Test
    fun addAllFromTagToTagKeepsLargeRelationSetUniqueThroughUndo() = runBlocking {
        val now = "2024-01-01T00:00:00Z"
        val fixture = storage.withDatabase { database ->
            val sourceTagId = database.tagDao().insertTag(
                TagEntity(name = "Large source", createdAt = now, updatedAt = now),
            )
            val targetTagId = database.tagDao().insertTag(
                TagEntity(name = "Large target", createdAt = now, updatedAt = now),
            )
            val clipIds = (0 until 250).map { index -> database.clipDao().insertClip(clip("add-all-large-$index")) }
            database.clipDao().insertClipTags(clipIds.map { ClipTagEntity(it, sourceTagId, now) })
            Triple(clipIds, sourceTagId, targetTagId)
        }
        val (clipIds, sourceTagId, targetTagId) = fixture

        repository.addAllFromTagToTag(sourceTagId, targetTagId)

        storage.withDatabase { database ->
            val relations = database.clipDao().clipTagsForClipIds(clipIds)
            assertEquals(clipIds.size * 2, relations.size)
            assertEquals(relations.size, relations.map { it.clipId to it.tagId }.toSet().size)
            val payload = (UndoPayloadCodec.decode(requireNotNull(database.undoDao().getSlot()).payloadJson)
                as UndoPayloadDecodeResult.Success).payload as ClipTagChangeUndoPayload
            assertEquals(clipIds.size, payload.addedRelations.size)
            assertEquals(payload.addedRelations.size, payload.addedRelations.map { it.clipId to it.tagId }.toSet().size)
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())
        storage.withDatabase { database ->
            val relations = database.clipDao().clipTagsForClipIds(clipIds)
            assertEquals(clipIds.size, relations.size)
            assertTrue(relations.all { it.tagId == sourceTagId })
            assertTrue(database.clipDao().clipTagsForTag(targetTagId).isEmpty())
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
            val clips = database.clipDao().getAllClips()
            assertEquals(2, clips.size)
            val existing = clips.single { it.id == existingId }
            assertEquals("手動概要", existing.summary)
            assertEquals("original", existing.text)
            assertEquals(1, database.clipDao().clipTagsForClipIds(listOf(existingId)).size)
        }
    }

    @Test
    fun resyncRecreatesADeletedPostAsCurrentApiData() = runBlocking {
        val deletedId = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("150", summary = "削除前メモ"))
            database.clipDao().deleteClip(clipId)
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
            val postIds = database.clipDao().getAllClips().map { it.xPostId }.toSet()
            assertEquals(setOf("150", "151"), postIds)
            assertFalse(database.clipDao().getAllClips().any { it.id == deletedId })
            assertEquals(2, database.clipDao().countClips())
        }
    }

    @Test
    fun paginationPersistsUsageAndDoesNotDuplicatePosts() = runBlocking {
        api.likedResponses += XApiResult(listOf(post("201")), "next-1", 75, 70, 1234)
        api.likedResponses += XApiResult(listOf(post("202"), post("201")), null, 75, 68, 1234)

        repository.syncNow()

        storage.withDatabase { database ->
            assertEquals(setOf("201", "202"), database.clipDao().getAllClips().map { it.xPostId }.toSet())
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
            val postIds = database.clipDao().getAllClips().map { it.xPostId }
            assertEquals(expectedIds, postIds.toSet())
            assertEquals(expectedIds.size, postIds.size)
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
        storage.withDatabase { database -> assertTrue(database.clipDao().getAllClips().isEmpty()) }
    }

    @Test
    fun syncNetworkWaitIsInactiveAndFetchedPostsUseHeavyLocalTracker() = runBlocking {
        val tracker = HeavyLocalWorkTracker()
        val trackedRepository = newRepository(heavyLocalWorkTracker = tracker)
        val activeStates = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            tracker.isActive.collect { activeStates += it }
        }
        api.beforeLikedResponse = {
            assertFalse("X API response waiting must not be tracked as local work", tracker.isActive.value)
        }
        api.likedResponses += XApiResult(
            posts = (0 until 20).map { index -> post("tracked-sync-$index") },
            nextToken = null,
            rateLimitLimit = 75,
            rateLimitRemaining = 74,
            rateLimitReset = 1234,
        )

        trackedRepository.syncNow()
        yield()

        assertTrue(activeStates.contains(true))
        assertFalse(tracker.isActive.value)
        collector.cancelAndJoin()
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
        val mediaSource = repository.mediaGridSource.first { it.clips.size == 3 }.clips

        val cardResult = sortClipsForDisplay(filterClipsForSearch(cardClips, hierarchy, filters), hierarchy, filters, sort)
        val mediaResult = mediaSource.sortedWith(compareByDescending<MediaGridClipSource> { it.clip.likeCount ?: Long.MIN_VALUE }.thenBy { it.sourceIndex })

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
            assertEquals(listOf("251"), database.clipDao().getAllClips().map { it.xPostId })
            assertEquals("next-251", database.clipDao().getSyncState()?.likedPostsNextToken)
        }

        api.likedResponses += XApiResult(listOf(post("252"), post("251")), null, 75, 72, 1234)
        val resumeCallIndex = api.likedCalls.size
        repository.syncNow()

        assertEquals("next-251", api.likedCalls[resumeCallIndex].paginationToken)
        assertEquals(null, api.likedCalls.last().paginationToken)
        storage.withDatabase { database ->
            assertEquals(setOf("251", "252"), database.clipDao().getAllClips().map { it.xPostId }.toSet())
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
                assertTrue(database.clipDao().getAllClips().isEmpty())
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
            assertTrue(database.clipDao().getAllClips().isEmpty())
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
            assertEquals(2, database.clipDao().countClips())
            val clips = database.clipDao().getAllClips().associateBy { it.id }
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
    fun settingsSnapshotCountsAllClipsAndExistingManagedImages() = runBlocking {
        val now = Instant.now().toString()
        val image = File(storage.imageDirectory(), "snapshot-counted.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("801"))
            database.clipDao().insertClip(clip("802"))
            database.clipDao().insertAssets(
                listOf(
                    AssetEntity(
                        clipId = clipId,
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
                        clipId = clipId,
                        mediaKey = "failed",
                        type = "photo",
                        remoteUrl = null,
                        previewUrl = null,
                        localPath = null,
                        downloadState = "failed",
                        createdAt = now,
                    ),
                    AssetEntity(
                        clipId = clipId,
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

        assertEquals(2, snapshot.saveCount)
        assertEquals(1, snapshot.imageCount)
    }

    @Test
    fun scopeAndServerErrorsDoNotWritePartialPosts() = runBlocking {
        listOf(403, 500).forEach { status ->
            api.likedResponses += XApiException(status, "error-$status")
            val failure = runCatching { repository.syncNow() }.exceptionOrNull()
            assertNotNull(failure)
        }

        storage.withDatabase { database -> assertTrue(database.clipDao().getAllClips().isEmpty()) }
        storage.withDatabase { database -> assertEquals(0L, database.clipDao().getTotalBillableReadCount()) }
        assertEquals(2, api.likedCalls.size)
    }

    @Test
    fun photoIsStoredAsWebpAndExistingPostIsNotDownloadedTwice() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val jpegEnqueued = mutableListOf<Long>()
            repository = newRepository(
                previewEnqueuer = object : MediaGridPreviewEnqueuer {
                    override fun enqueue(assetIds: Collection<Long>) {
                        jpegEnqueued += assetIds
                    }
                },
            )
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
                val store = MediaGridRgb565PackStore(context.filesDir)
                val source = MediaGridRgb565SourceSignature(
                    MediaGridRgb565SourceKind.LOCAL_WEBP,
                    local.length(),
                    local.lastModified(),
                )
                val slot = requireNotNull(store.readSlot(assets.single().id, listOf(source)))
                val rawBitmap = store.readBitmap(slot)
                try {
                    assertEquals(Bitmap.Config.RGB_565, rawBitmap.config)
                    assertEquals(256, rawBitmap.width)
                    assertEquals(256, rawBitmap.height)
                } finally {
                    rawBitmap.recycle()
                }
                assertEquals(listOf(assets.single().id), jpegEnqueued)
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rawPublishFailureKeepsWebpAndDatabaseAndEnqueuesOnlyRawRepair() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val repairIds = mutableListOf<Long>()
            val jpegIds = mutableListOf<Long>()
            var publishAttempts = 0
            repository = newRepository(
                previewEnqueuer = object : MediaGridPreviewEnqueuer {
                    override fun enqueue(assetIds: Collection<Long>) {
                        jpegIds += assetIds
                    }
                },
                repairEnqueuer = object : MediaGridRgb565RepairEnqueuer {
                    override fun enqueue(assetIds: Collection<Long>) {
                        repairIds += assetIds
                    }
                },
                rgb565Publisher = object : MediaGridRgb565Publisher {
                    override fun publish(
                        assetId: Long,
                        payload: MediaGridRgb565Payload,
                        source: MediaGridRgb565SourceSignature,
                        afterStage: ((MediaGridRgb565PublishStage) -> Unit)?,
                    ): MediaGridRgb565Slot {
                        publishAttempts += 1
                        throw java.io.IOException("simulated raw I/O failure")
                    }

                    override fun validatePayloadCrc(slot: MediaGridRgb565Slot): Boolean = false
                },
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(okio.Buffer().write(pngBytes(android.graphics.Color.GREEN))),
            )
            val media = XMedia(
                "raw-failure",
                "photo",
                server.url("/raw-failure.png").toString(),
                null,
                2,
                2,
            )
            api.likedResponses += XApiResult(
                listOf(post("raw-failure", media = listOf(media))),
                null,
                null,
                null,
                null,
            )

            repository.syncNow()

            storage.withDatabase { database ->
                val asset = database.clipDao().getAllAssets().single()
                val local = File(requireNotNull(asset.localPath))
                assertTrue(local.isFile)
                assertEquals("downloaded", asset.downloadState)
                assertEquals(listOf(asset.id), repairIds)
                assertEquals(listOf(asset.id), jpegIds)
            }
            assertEquals(3, publishAttempts)
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
                assertEquals(1, database.clipDao().getAllClips().count { it.xPostId == "306" })
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
            assertEquals(1, database.clipDao().getAllClips().count { it.xPostId == "302" })
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
        storage.withDatabase { database -> assertTrue(database.clipDao().getAllClips().any { it.id == clipId }) }
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
    fun nodeMoveApisInvalidatePreviousUndoAndDoNotCreateMoveUndo() = runBlocking {
        val now = Instant.now().toString()
        val fixture = storage.withDatabase { database ->
            val sourceGroup = database.tagDao().insertGroup(
                TagGroupEntity(name = "Move source", sortOrder = 0, createdAt = now, updatedAt = now),
            )
            val destinationGroup = database.tagDao().insertGroup(
                TagGroupEntity(name = "Move destination", sortOrder = 1, createdAt = now, updatedAt = now),
            )
            val firstTag = database.tagDao().insertTag(
                TagEntity(name = "Move first", parentGroupId = sourceGroup, sortOrder = 0, createdAt = now, updatedAt = now),
            )
            val secondTag = database.tagDao().insertTag(
                TagEntity(name = "Move second", parentGroupId = sourceGroup, sortOrder = 1, createdAt = now, updatedAt = now),
            )
            listOf(sourceGroup, destinationGroup, firstTag, secondTag)
        }
        val (sourceGroup, destinationGroup, firstTag, secondTag) = fixture

        repository.createTag("Pending before move")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })
        repository.moveNode(TagNodeRef(TagNodeType.TAG, firstTag), destinationGroup)
        assertEquals(destinationGroup, tagParentGroupId(firstTag))
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })

        repository.createGroup("Pending before slot move")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })
        repository.moveNodeToParentAtSlot(TagNodeRef(TagNodeType.TAG, secondTag), destinationGroup, 0)
        assertEquals(destinationGroup, tagParentGroupId(secondTag))
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })

        repository.createTag("Pending before indexed move")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })
        repository.moveNodeToParentAt(TagNodeRef(TagNodeType.TAG, secondTag), sourceGroup, 0)
        assertEquals(sourceGroup, tagParentGroupId(secondTag))
        assertEquals(UndoCoordinatorResult.NoPendingUndo, repository.undoPendingEdit())
    }

    @Test
    fun reorderInvalidatesPreviousUndoAndDoesNotCreateReorderUndo() = runBlocking {
        val now = Instant.now().toString()
        val (groupId, firstTag, secondTag) = storage.withDatabase { database ->
            val groupId = database.tagDao().insertGroup(
                TagGroupEntity(name = "Reorder parent", createdAt = now, updatedAt = now),
            )
            val firstTag = database.tagDao().insertTag(
                TagEntity(name = "Reorder first", parentGroupId = groupId, sortOrder = 0, createdAt = now, updatedAt = now),
            )
            val secondTag = database.tagDao().insertTag(
                TagEntity(name = "Reorder second", parentGroupId = groupId, sortOrder = 1, createdAt = now, updatedAt = now),
            )
            Triple(groupId, firstTag, secondTag)
        }

        repository.createTag("Pending before reorder")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })
        repository.reorderSiblings(
            groupId,
            listOf(
                TagNodeRef(TagNodeType.TAG, secondTag),
                TagNodeRef(TagNodeType.TAG, firstTag),
            ),
        )

        assertEquals(
            listOf("tag:$secondTag:0", "tag:$firstTag:1"),
            siblingOrder(groupId),
        )
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })
        assertEquals(UndoCoordinatorResult.NoPendingUndo, repository.undoPendingEdit())
    }

    @Test
    fun failedMoveAndReorderDoNotRestoreInvalidatedUndo() = runBlocking {
        val now = Instant.now().toString()
        val (groupId, tagId) = storage.withDatabase { database ->
            val groupId = database.tagDao().insertGroup(
                TagGroupEntity(name = "Failure parent", createdAt = now, updatedAt = now),
            )
            val tagId = database.tagDao().insertTag(
                TagEntity(name = "Failure tag", parentGroupId = groupId, createdAt = now, updatedAt = now),
            )
            groupId to tagId
        }

        repository.createTag("Pending before failed move")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })
        assertNotNull(
            runCatching {
                repository.moveNodeToParentAtSlot(
                    TagNodeRef(TagNodeType.TAG, tagId),
                    Long.MAX_VALUE,
                    0,
                )
            }.exceptionOrNull(),
        )
        assertEquals(groupId, tagParentGroupId(tagId))
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })

        repository.createGroup("Pending before failed reorder")
        assertNotNull(storage.withDatabase { it.undoDao().getSlot() })
        assertNotNull(
            runCatching {
                repository.reorderSiblings(groupId, emptyList())
            }.exceptionOrNull(),
        )
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })
        assertEquals(UndoCoordinatorResult.NoPendingUndo, repository.undoPendingEdit())
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
            val clip = database.clipDao().getAllClips().single { it.id == clipId }
            val assets = database.clipDao().assetsForClipIds(listOf(clipId))
            ClipWithDetails(clip = clip, assets = assets, tags = emptyList())
        }

        val recognized = repository.detectOcrText(clipWithDetails)

        assertEquals("Landscape OCR\nSecond line\n\nPortrait OCR", recognized)
        assertEquals(null, storage.withDatabase { it.undoDao().getSlot() })

        val clip = clipWithDetails.clip
        repository.updateOcrText(clip, recognized)

        storage.withDatabase { database ->
            val updated = database.clipDao().getAllClips().single { it.id == clipId }
            assertEquals(recognized, updated.ocrText)
            assertTrue(requireNotNull(updated.ocrUpdatedAt).isNotBlank())
        }
    }

    @Test
    fun fakeOcrGatewayCanSupplyArbitraryStructuredResult() = runBlocking {
        val expected = OcrRecognitionResult(
            imageWidth = 640,
            imageHeight = 480,
            fullText = "Injected",
            regions = listOf(
                OcrTextRegion(
                    text = "Injected",
                    polygon = OcrPolygon(
                        listOf(
                            OcrPoint(1.5f, 2.5f),
                            OcrPoint(30.25f, 3.5f),
                            OcrPoint(29.75f, 20.5f),
                            OcrPoint(0.75f, 19.5f),
                        ),
                    ),
                    confidence = 0.73f,
                ),
            ),
        )
        val gateway = FakeOcrTextGateway { expected }
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)

        try {
            assertEquals(expected, gateway.recognize(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun mlKitCornerPointAdapterKeepsAllCoordinatesInCommonPolygon() {
        val polygon = OcrPolygon.fromCornerPoints(
            arrayOf(
                android.graphics.Point(42, 70),
                android.graphics.Point(30, 90),
                android.graphics.Point(80, 20),
                android.graphics.Point(12, 10),
            ),
        )

        assertEquals(
            listOf(
                OcrPoint(12f, 10f),
                OcrPoint(80f, 20f),
                OcrPoint(42f, 70f),
                OcrPoint(30f, 90f),
            ),
            polygon?.points,
        )
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
        val clip = storage.withDatabase { database -> database.clipDao().getAllClips().single { it.id == clipId } }
        val beforeCount = storage.withDatabase { database -> database.clipDao().countClips() }

        repository.moveClipToTrash(clip)

        storage.withDatabase { database ->
            assertEquals(beforeCount - 1, database.clipDao().countClips())
            assertTrue(database.clipDao().getAllClips().none { it.id == clipId })
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
            assertTrue(database.clipDao().getAllClips().any { it.id == clipId })
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

    @Test
    fun deleteClipSurvivesDatabaseReopenAndUndoRestoresRowsRelationsAndImageBytes() = runBlocking {
        val imageDirectory = storage.imageDirectory()
        val firstBytes = "first-image-content".toByteArray()
        val secondBytes = "second-image-content".toByteArray()
        val firstFile = File(imageDirectory, "delete_first.webp").apply { writeBytes(firstBytes) }
        val secondFile = File(imageDirectory, "delete_second.webp").apply { writeBytes(secondBytes) }
        val fixture = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("durable-delete"))
            val now = "2026-08-12T12:34:56Z"
            val tagIds = listOf("Delete tag A", "Delete tag B").map { name ->
                database.tagDao().insertTag(TagEntity(name = name, createdAt = now, updatedAt = now))
            }
            val assetIds = database.clipDao().insertAssets(
                listOf(
                    AssetEntity(clipId = clipId, mediaKey = "delete-1", type = "photo", remoteUrl = null, previewUrl = null, localPath = firstFile.absolutePath, sizeBytes = firstFile.length(), downloadState = "downloaded", createdAt = now),
                    AssetEntity(clipId = clipId, mediaKey = "delete-2", type = "photo", remoteUrl = null, previewUrl = null, localPath = secondFile.absolutePath, sizeBytes = secondFile.length(), downloadState = "downloaded", createdAt = now),
                ),
            )
            val relations = tagIds.mapIndexed { index, tagId -> ClipTagEntity(clipId, tagId, "$now-$index") }
            database.clipDao().insertClipTags(relations)
            Triple(
                requireNotNull(database.clipDao().getClip(clipId)),
                database.clipDao().assetsForClipIds(listOf(clipId)),
                relations,
            ).also { assertEquals(2, assetIds.size) }
        }
        val (originalClip, originalAssets, originalRelations) = fixture

        repository.moveClipToTrash(originalClip)

        storage.withDatabase { database ->
            assertEquals(null, database.clipDao().getClip(originalClip.id))
            assertTrue(database.clipDao().assetsForClipIds(listOf(originalClip.id)).isEmpty())
            assertTrue(database.clipDao().clipTagsForClipIds(listOf(originalClip.id)).isEmpty())
            val slot = requireNotNull(database.undoDao().getSlot())
            assertEquals(UndoActionType.CLIP_DELETED.storageValue, slot.actionType)
        }
        assertFalse(firstFile.exists())
        assertFalse(secondFile.exists())
        val stagingRoot = File(context.filesDir, DurableClipDeleteUndoStore.DIRECTORY_NAME)
        assertEquals(2, stagingRoot.walkTopDown().count(File::isFile))

        storage.database.value?.close()
        storage = PostStorageManager(context, storageConfig)
        repository = newRepository()
        storage.withDatabase { database ->
            assertEquals(null, database.clipDao().getClip(originalClip.id))
            assertNotNull(database.undoDao().getSlot())
        }

        assertEquals(UndoCoordinatorResult.Success, repository.undoPendingEdit())

        storage.withDatabase { database ->
            assertEquals(originalClip, database.clipDao().getClip(originalClip.id))
            val restoredAssets = database.clipDao().assetsForClipIds(listOf(originalClip.id))
            assertEquals(
                originalAssets.map { it.copy(localPath = null) },
                restoredAssets.map { it.copy(localPath = null) },
            )
            val currentImageDirectory = storage.imageDirectory().canonicalFile
            originalAssets.zip(restoredAssets).forEach { (original, restored) ->
                val originalPath = requireNotNull(original.localPath)
                val restoredFile = File(requireNotNull(restored.localPath)).canonicalFile
                assertEquals(File(originalPath).name, restoredFile.name)
                assertEquals(currentImageDirectory, restoredFile.parentFile)
                assertTrue(restoredFile.isFile)
            }
            assertEquals(
                originalRelations.sortedBy(ClipTagEntity::tagId),
                database.clipDao().clipTagsForClipIds(listOf(originalClip.id)).sortedBy(ClipTagEntity::tagId),
            )
            assertEquals(null, database.undoDao().getSlot())
        }
        assertTrue(firstBytes.contentEquals(firstFile.readBytes()))
        assertTrue(secondBytes.contentEquals(secondFile.readBytes()))
        assertEquals(0, stagingRoot.walkTopDown().count(File::isFile))
    }

    @Test
    fun deleteClipCopyFailureKeepsDatabaseAndOriginalImage() = runBlocking {
        val image = File(storage.imageDirectory(), "copy_failure.webp").apply { writeText("must-survive") }
        val clip = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("copy-failure"))
            database.clipDao().insertAssets(
                listOf(AssetEntity(clipId = clipId, mediaKey = "copy-failure", type = "photo", remoteUrl = null, previewUrl = null, localPath = image.absolutePath, sizeBytes = image.length(), downloadState = "downloaded", createdAt = "created")),
            )
            requireNotNull(database.clipDao().getClip(clipId))
        }
        repository = newRepository(
            deleteUndoStore = DurableClipDeleteUndoStore(context.filesDir) { _, _ ->
                throw IllegalStateException("injected copy failure")
            },
        )

        assertNotNull(runCatching { repository.moveClipToTrash(clip) }.exceptionOrNull())

        storage.withDatabase { database ->
            assertEquals(clip, database.clipDao().getClip(clip.id))
            assertEquals(1, database.clipDao().assetsForClipIds(listOf(clip.id)).size)
            assertEquals(null, database.undoDao().getSlot())
        }
        assertEquals("must-survive", image.readText())
    }

    @Test
    fun overwritingClipDeleteUndoCleansOnlyItsStagingFiles() = runBlocking {
        val unrelated = File(context.filesDir, "same_name.webp").apply { writeText("unrelated") }
        val image = File(storage.imageDirectory(), unrelated.name).apply { writeText("managed") }
        val clip = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("overwrite-delete"))
            database.clipDao().insertAssets(
                listOf(AssetEntity(clipId = clipId, mediaKey = "overwrite", type = "photo", remoteUrl = null, previewUrl = null, localPath = image.absolutePath, sizeBytes = image.length(), downloadState = "downloaded", createdAt = "created")),
            )
            requireNotNull(database.clipDao().getClip(clipId))
        }
        repository.moveClipToTrash(clip)
        val stagingRoot = File(context.filesDir, DurableClipDeleteUndoStore.DIRECTORY_NAME)
        assertEquals(1, stagingRoot.walkTopDown().count(File::isFile))

        repository.createTag("overwrite undo")

        assertEquals(0, stagingRoot.walkTopDown().count(File::isFile))
        assertEquals("unrelated", unrelated.readText())
        storage.withDatabase { database ->
            assertEquals(UndoActionType.TAG_CREATED.storageValue, requireNotNull(database.undoDao().getSlot()).actionType)
        }
    }

    @Test
    fun failedClipDeleteUndoKeepsSlotAndStagedRestoreData() = runBlocking {
        val image = File(storage.imageDirectory(), "undo_failure.webp").apply { writeText("restore-data") }
        val deleted = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("undo-failure"))
            database.clipDao().insertAssets(
                listOf(AssetEntity(clipId = clipId, mediaKey = "failure", type = "photo", remoteUrl = null, previewUrl = null, localPath = image.absolutePath, sizeBytes = image.length(), downloadState = "downloaded", createdAt = "created")),
            )
            requireNotNull(database.clipDao().getClip(clipId))
        }
        repository.moveClipToTrash(deleted)
        val stagingRoot = File(context.filesDir, DurableClipDeleteUndoStore.DIRECTORY_NAME)
        val stagedBefore = stagingRoot.walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toList()
        storage.withDatabase { database ->
            assertEquals(deleted.id, database.clipDao().insertClip(deleted.copy(xPostId = "unexpected-collision")))
        }

        assertTrue(repository.undoPendingEdit() is UndoCoordinatorResult.Failure)

        storage.withDatabase { database ->
            assertNotNull(database.undoDao().getSlot())
            assertEquals("unexpected-collision", requireNotNull(database.clipDao().getClip(deleted.id)).xPostId)
        }
        assertEquals(
            stagedBefore,
            stagingRoot.walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toList(),
        )
        assertTrue(stagedBefore.isNotEmpty())
    }

    @Test
    fun dismissingClipDeleteUndoRemovesItsStagingAndKeepsDeletionCommitted() = runBlocking {
        val image = File(storage.imageDirectory(), "dismiss_delete.webp").apply { writeText("dismiss-data") }
        val deleted = storage.withDatabase { database ->
            val clipId = database.clipDao().insertClip(clip("dismiss-delete"))
            database.clipDao().insertAssets(
                listOf(AssetEntity(clipId = clipId, mediaKey = "dismiss", type = "photo", remoteUrl = null, previewUrl = null, localPath = image.absolutePath, sizeBytes = image.length(), downloadState = "downloaded", createdAt = "created")),
            )
            requireNotNull(database.clipDao().getClip(clipId))
        }
        repository.moveClipToTrash(deleted)
        val stagingRoot = File(context.filesDir, DurableClipDeleteUndoStore.DIRECTORY_NAME)
        assertEquals(1, stagingRoot.walkTopDown().count(File::isFile))

        assertEquals(UndoCoordinatorResult.Success, repository.finalizePendingUndo())

        assertEquals(0, stagingRoot.walkTopDown().count(File::isFile))
        storage.withDatabase { database ->
            assertEquals(null, database.clipDao().getClip(deleted.id))
            assertEquals(null, database.undoDao().getSlot())
        }
    }

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

    private suspend fun assertClipTagRelations(clipId: Long, expected: Map<Long, String>) {
        val actual = storage.withDatabase { database ->
            database.clipDao().clipTagsForClipIds(listOf(clipId)).associate { it.tagId to it.createdAt }
        }
        assertEquals(expected, actual)
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
    var beforeLikedResponse: (() -> Unit)? = null

    override fun fetchLikedPosts(accessToken: String, xUserId: String, maxResults: Int, paginationToken: String?): XApiResult {
        likedCalls += LikedCall(accessToken, paginationToken)
        beforeLikedResponse?.invoke()
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
