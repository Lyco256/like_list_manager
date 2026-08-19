package com.lyco256.llm.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoPayloadCodecTest {
    private val relationA = ClipTagEntity(Long.MAX_VALUE, 2, "2026-08-12T01:02:03Z")
    private val relationB = ClipTagEntity(3, Long.MAX_VALUE - 1, "日本語-😀")
    private val tag = TagEntity(
        id = Long.MAX_VALUE,
        name = "タグ😀",
        parentGroupId = null,
        sortOrder = -4,
        createdAt = "created",
        updatedAt = "updated",
        colorId = "色",
    )
    private val group = TagGroupEntity(
        id = Long.MAX_VALUE - 2,
        name = "",
        parentGroupId = 99,
        sortOrder = 7,
        createdAt = "group-created",
        updatedAt = "group-updated",
        colorId = "blue",
    )
    private val clip = ClipEntity(
        id = Long.MAX_VALUE - 3,
        xPostId = "x-post",
        authorId = null,
        authorName = "作者",
        authorUsername = "user",
        text = "本文😀",
        postUrl = "https://example.invalid/post",
        xCreatedAt = "x-created",
        savedAt = "saved",
        syncedAt = "synced",
        summary = "",
        ocrText = "OCR",
        ocrUpdatedAt = null,
        likeCount = Long.MAX_VALUE,
        likeCountFetchedAt = null,
        likeCountFetchFailedAt = "failed-at",
        likeCountFetchError = null,
    )
    private val asset = AssetEntity(
        id = Long.MAX_VALUE - 4,
        clipId = clip.id,
        mediaKey = "media",
        type = "photo",
        remoteUrl = null,
        previewUrl = "",
        localPath = "images/日本語.webp",
        width = null,
        height = 200,
        sizeBytes = Long.MAX_VALUE,
        downloadState = "local",
        createdAt = "asset-created",
    )

    @Test
    fun everyPayloadTypeRoundTripsWithoutChangingValues() {
        val payloads = listOf<UndoPayload>(
            ClipTagChangeUndoPayload(listOf(relationA), listOf(relationB, relationA)),
            TagCreatedUndoPayload(Long.MAX_VALUE),
            GroupCreatedUndoPayload(Long.MAX_VALUE - 1),
            TagEditedUndoPayload(tag.id, previousName = "旧名😀"),
            GroupEditedUndoPayload(group.id, previousName = "", previousColorId = "旧色"),
            TagDeletedUndoPayload(tag, listOf(relationA, relationB)),
            GroupDeletedUndoPayload(group),
            SummaryEditedUndoPayload(clip.id, ""),
            OcrEditedUndoPayload(clip.id, "認識前😀", null),
            ClipDeletedUndoPayload(
                clip,
                listOf(asset),
                listOf(relationA, relationB),
                listOf(UndoFileMetadata(asset.id, "slot/image.webp", Long.MAX_VALUE, "abcdef")),
            ),
        )

        payloads.forEach { payload ->
            assertEquals(
                "round-trip failed for ${payload.actionType}",
                UndoPayloadDecodeResult.Success(payload),
                UndoPayloadCodec.decode(UndoPayloadCodec.encode(payload)),
            )
        }
    }

    @Test
    fun nullableAndEmptyEditedFieldsRoundTrip() {
        val ocr = OcrEditedUndoPayload(1, "", null)
        val tagEdit = TagEditedUndoPayload(2, previousName = "", previousColorId = null)

        assertEquals(UndoPayloadDecodeResult.Success(ocr), UndoPayloadCodec.decode(UndoPayloadCodec.encode(ocr)))
        assertEquals(
            UndoPayloadDecodeResult.Success(tagEdit),
            UndoPayloadCodec.decode(UndoPayloadCodec.encode(tagEdit)),
        )
    }

    @Test
    fun unknownVersionAndActionTypeAreRejected() {
        val known = JSONObject(UndoPayloadCodec.encode(TagCreatedUndoPayload(1)))
        known.put("schemaVersion", UndoPayloadCodec.SCHEMA_VERSION + 1)
        assertEquals(
            UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.UNKNOWN_SCHEMA_VERSION),
            UndoPayloadCodec.decode(known.toString()),
        )

        known.put("schemaVersion", UndoPayloadCodec.SCHEMA_VERSION)
        known.put("actionType", "future_action")
        assertEquals(
            UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.UNKNOWN_ACTION_TYPE),
            UndoPayloadCodec.decode(known.toString()),
        )
    }

    @Test
    fun malformedAndStructurallyInvalidJsonAreRejected() {
        assertEquals(
            UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.MALFORMED_JSON),
            UndoPayloadCodec.decode("{not-json"),
        )
        assertEquals(
            UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.MALFORMED_JSON),
            UndoPayloadCodec.decode(UndoPayloadCodec.encode(TagCreatedUndoPayload(1)) + "broken"),
        )
        assertEquals(
            UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.INVALID_PAYLOAD),
            UndoPayloadCodec.decode("""{"schemaVersion":1,"actionType":"tag_created","payload":{}}"""),
        )
        assertEquals(
            UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.INVALID_PAYLOAD),
            UndoPayloadCodec.decode("""{"schemaVersion":1,"actionType":"tag_created","payload":{"tagId":"1"}}"""),
        )
    }

    @Test
    fun fieldLevelPayloadsContainOnlyFieldsTheyCanRestore() {
        val summaryBody = encodedBody(SummaryEditedUndoPayload(1, "before"))
        assertEquals(setOf("clipId", "previousSummary"), summaryBody.keys().asSequence().toSet())

        val ocrBody = encodedBody(OcrEditedUndoPayload(2, "before", null))
        assertEquals(
            setOf("clipId", "previousOcrText", "previousOcrUpdatedAt"),
            ocrBody.keys().asSequence().toSet(),
        )
        assertFalse(summaryBody.has("likeCount"))
        assertFalse(ocrBody.has("summary"))
        assertTrue(ocrBody.isNull("previousOcrUpdatedAt"))
    }

    private fun encodedBody(payload: UndoPayload): JSONObject =
        JSONObject(UndoPayloadCodec.encode(payload)).getJSONObject("payload")
}
