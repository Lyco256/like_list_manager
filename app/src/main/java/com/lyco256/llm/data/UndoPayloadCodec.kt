package com.lyco256.llm.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Typed, durable content stored in [UndoEntity.payloadJson]. */
sealed interface UndoPayload {
    val actionType: UndoActionType
}

enum class UndoActionType(val storageValue: String) {
    CLIP_TAG_CHANGE("clip_tag_change"),
    TAG_CREATED("tag_created"),
    GROUP_CREATED("group_created"),
    TAG_EDITED("tag_edited"),
    GROUP_EDITED("group_edited"),
    TAG_DELETED("tag_deleted"),
    GROUP_DELETED("group_deleted"),
    SUMMARY_EDITED("summary_edited"),
    OCR_EDITED("ocr_edited"),
    CLIP_DELETED("clip_deleted"),
    ;

    companion object {
        fun fromStorageValue(value: String): UndoActionType? = entries.find { it.storageValue == value }
    }
}

data class ClipTagChangeUndoPayload(
    val addedRelations: List<ClipTagEntity>,
    val removedRelations: List<ClipTagEntity>,
) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.CLIP_TAG_CHANGE
}

data class TagCreatedUndoPayload(val tagId: Long) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.TAG_CREATED
}

data class GroupCreatedUndoPayload(val groupId: Long) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.GROUP_CREATED
}

/** Null means that field was not changed and therefore must not be restored. */
data class TagEditedUndoPayload(
    val tagId: Long,
    val previousName: String? = null,
    val previousColorId: String? = null,
) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.TAG_EDITED

    init {
        require(previousName != null || previousColorId != null)
    }
}

/** Null means that field was not changed and therefore must not be restored. */
data class GroupEditedUndoPayload(
    val groupId: Long,
    val previousName: String? = null,
    val previousColorId: String? = null,
) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.GROUP_EDITED

    init {
        require(previousName != null || previousColorId != null)
    }
}

data class TagDeletedUndoPayload(
    val tag: TagEntity,
    val relations: List<ClipTagEntity>,
) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.TAG_DELETED
}

data class GroupDeletedUndoPayload(val group: TagGroupEntity) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.GROUP_DELETED
}

data class SummaryEditedUndoPayload(
    val clipId: Long,
    val previousSummary: String,
) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.SUMMARY_EDITED
}

data class OcrEditedUndoPayload(
    val clipId: Long,
    val previousOcrText: String,
    val previousOcrUpdatedAt: String?,
) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.OCR_EDITED
}

/** Metadata only: image bytes remain in the durable staging area referenced here. */
data class UndoFileMetadata(
    val assetId: Long,
    val stagingRelativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class ClipDeletedUndoPayload(
    val clip: ClipEntity,
    val assets: List<AssetEntity>,
    val relations: List<ClipTagEntity>,
    val files: List<UndoFileMetadata>,
) : UndoPayload {
    override val actionType: UndoActionType = UndoActionType.CLIP_DELETED
}

sealed interface UndoPayloadDecodeResult {
    data class Success(val payload: UndoPayload) : UndoPayloadDecodeResult
    data class Error(val reason: Reason) : UndoPayloadDecodeResult {
        enum class Reason { MALFORMED_JSON, UNKNOWN_SCHEMA_VERSION, UNKNOWN_ACTION_TYPE, INVALID_PAYLOAD }
    }
}

object UndoPayloadCodec {
    const val SCHEMA_VERSION = 1

    fun encode(payload: UndoPayload): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("actionType", payload.actionType.storageValue)
        .put("payload", encodeBody(payload))
        .toString()

    fun decode(json: String): UndoPayloadDecodeResult {
        val envelope = try {
            val tokener = JSONTokener(json)
            val decoded = tokener.nextValue() as? JSONObject
                ?: return UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.MALFORMED_JSON)
            if (tokener.nextClean().code != 0) {
                return UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.MALFORMED_JSON)
            }
            decoded
        } catch (_: JSONException) {
            return UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.MALFORMED_JSON)
        }
        val version = envelope.strictInt("schemaVersion")
            ?: return UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.INVALID_PAYLOAD)
        if (version != SCHEMA_VERSION) {
            return UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.UNKNOWN_SCHEMA_VERSION)
        }
        val actionName = envelope.strictString("actionType")
            ?: return UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.INVALID_PAYLOAD)
        val actionType = UndoActionType.fromStorageValue(actionName)
            ?: return UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.UNKNOWN_ACTION_TYPE)
        val body = envelope.strictObject("payload")
            ?: return UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.INVALID_PAYLOAD)
        val payload = try {
            decodeBody(actionType, body)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JSONException) {
            null
        }
        return payload?.let(UndoPayloadDecodeResult::Success)
            ?: UndoPayloadDecodeResult.Error(UndoPayloadDecodeResult.Error.Reason.INVALID_PAYLOAD)
    }

    private fun encodeBody(payload: UndoPayload): JSONObject = when (payload) {
        is ClipTagChangeUndoPayload -> JSONObject()
            .put("addedRelations", payload.addedRelations.toJsonArray(::encodeClipTag))
            .put("removedRelations", payload.removedRelations.toJsonArray(::encodeClipTag))
        is TagCreatedUndoPayload -> JSONObject().put("tagId", payload.tagId)
        is GroupCreatedUndoPayload -> JSONObject().put("groupId", payload.groupId)
        is TagEditedUndoPayload -> JSONObject().put("tagId", payload.tagId)
            .putNullable("previousName", payload.previousName)
            .putNullable("previousColorId", payload.previousColorId)
        is GroupEditedUndoPayload -> JSONObject().put("groupId", payload.groupId)
            .putNullable("previousName", payload.previousName)
            .putNullable("previousColorId", payload.previousColorId)
        is TagDeletedUndoPayload -> JSONObject()
            .put("tag", encodeTag(payload.tag))
            .put("relations", payload.relations.toJsonArray(::encodeClipTag))
        is GroupDeletedUndoPayload -> JSONObject().put("group", encodeGroup(payload.group))
        is SummaryEditedUndoPayload -> JSONObject()
            .put("clipId", payload.clipId)
            .put("previousSummary", payload.previousSummary)
        is OcrEditedUndoPayload -> JSONObject()
            .put("clipId", payload.clipId)
            .put("previousOcrText", payload.previousOcrText)
            .putNullable("previousOcrUpdatedAt", payload.previousOcrUpdatedAt)
        is ClipDeletedUndoPayload -> JSONObject()
            .put("clip", encodeClip(payload.clip))
            .put("assets", payload.assets.toJsonArray(::encodeAsset))
            .put("relations", payload.relations.toJsonArray(::encodeClipTag))
            .put("files", payload.files.toJsonArray(::encodeFile))
    }

    private fun decodeBody(type: UndoActionType, body: JSONObject): UndoPayload? {
        return when (type) {
        UndoActionType.CLIP_TAG_CHANGE -> ClipTagChangeUndoPayload(
            body.strictArray("addedRelations")?.mapObjects(::decodeClipTag) ?: return null,
            body.strictArray("removedRelations")?.mapObjects(::decodeClipTag) ?: return null,
        )
        UndoActionType.TAG_CREATED -> TagCreatedUndoPayload(body.strictLong("tagId") ?: return null)
        UndoActionType.GROUP_CREATED -> GroupCreatedUndoPayload(body.strictLong("groupId") ?: return null)
        UndoActionType.TAG_EDITED -> TagEditedUndoPayload(
            body.strictLong("tagId") ?: return null,
            body.requiredNullableString("previousName"),
            body.requiredNullableString("previousColorId"),
        )
        UndoActionType.GROUP_EDITED -> GroupEditedUndoPayload(
            body.strictLong("groupId") ?: return null,
            body.requiredNullableString("previousName"),
            body.requiredNullableString("previousColorId"),
        )
        UndoActionType.TAG_DELETED -> TagDeletedUndoPayload(
            body.strictObject("tag")?.let(::decodeTag) ?: return null,
            body.strictArray("relations")?.mapObjects(::decodeClipTag) ?: return null,
        )
        UndoActionType.GROUP_DELETED -> GroupDeletedUndoPayload(
            body.strictObject("group")?.let(::decodeGroup) ?: return null,
        )
        UndoActionType.SUMMARY_EDITED -> SummaryEditedUndoPayload(
            body.strictLong("clipId") ?: return null,
            body.strictString("previousSummary") ?: return null,
        )
        UndoActionType.OCR_EDITED -> OcrEditedUndoPayload(
            body.strictLong("clipId") ?: return null,
            body.strictString("previousOcrText") ?: return null,
            body.requiredNullableString("previousOcrUpdatedAt"),
        )
        UndoActionType.CLIP_DELETED -> ClipDeletedUndoPayload(
            body.strictObject("clip")?.let(::decodeClip) ?: return null,
            body.strictArray("assets")?.mapObjects(::decodeAsset) ?: return null,
            body.strictArray("relations")?.mapObjects(::decodeClipTag) ?: return null,
            body.strictArray("files")?.mapObjects(::decodeFile) ?: return null,
        )
        }
    }

    private fun encodeClipTag(value: ClipTagEntity) = JSONObject()
        .put("clipId", value.clipId).put("tagId", value.tagId).put("createdAt", value.createdAt)

    private fun decodeClipTag(value: JSONObject) = ClipTagEntity(
        value.strictLong("clipId") ?: throw JSONException("clipId"),
        value.strictLong("tagId") ?: throw JSONException("tagId"),
        value.strictString("createdAt") ?: throw JSONException("createdAt"),
    )

    private fun encodeTag(value: TagEntity) = JSONObject().put("id", value.id).put("name", value.name)
        .putNullable("parentGroupId", value.parentGroupId).put("sortOrder", value.sortOrder)
        .put("createdAt", value.createdAt).put("updatedAt", value.updatedAt).put("colorId", value.colorId)

    private fun decodeTag(value: JSONObject) = TagEntity(
        id = value.strictLong("id") ?: throw JSONException("id"),
        name = value.strictString("name") ?: throw JSONException("name"),
        parentGroupId = value.requiredNullableLong("parentGroupId"),
        sortOrder = value.strictInt("sortOrder") ?: throw JSONException("sortOrder"),
        createdAt = value.strictString("createdAt") ?: throw JSONException("createdAt"),
        updatedAt = value.strictString("updatedAt") ?: throw JSONException("updatedAt"),
        colorId = value.strictString("colorId") ?: throw JSONException("colorId"),
    )

    private fun encodeGroup(value: TagGroupEntity) = JSONObject().put("id", value.id).put("name", value.name)
        .putNullable("parentGroupId", value.parentGroupId).put("sortOrder", value.sortOrder)
        .put("createdAt", value.createdAt).put("updatedAt", value.updatedAt).put("colorId", value.colorId)

    private fun decodeGroup(value: JSONObject) = TagGroupEntity(
        id = value.strictLong("id") ?: throw JSONException("id"),
        name = value.strictString("name") ?: throw JSONException("name"),
        parentGroupId = value.requiredNullableLong("parentGroupId"),
        sortOrder = value.strictInt("sortOrder") ?: throw JSONException("sortOrder"),
        createdAt = value.strictString("createdAt") ?: throw JSONException("createdAt"),
        updatedAt = value.strictString("updatedAt") ?: throw JSONException("updatedAt"),
        colorId = value.strictString("colorId") ?: throw JSONException("colorId"),
    )

    private fun encodeClip(value: ClipEntity) = JSONObject()
        .put("id", value.id).put("xPostId", value.xPostId).putNullable("authorId", value.authorId)
        .put("authorName", value.authorName).put("authorUsername", value.authorUsername).put("text", value.text)
        .put("postUrl", value.postUrl).put("xCreatedAt", value.xCreatedAt).put("savedAt", value.savedAt)
        .put("syncedAt", value.syncedAt).put("summary", value.summary).put("ocrText", value.ocrText)
        .putNullable("ocrUpdatedAt", value.ocrUpdatedAt).putNullable("likeCount", value.likeCount)
        .putNullable("likeCountFetchedAt", value.likeCountFetchedAt)
        .putNullable("likeCountFetchFailedAt", value.likeCountFetchFailedAt)
        .putNullable("likeCountFetchError", value.likeCountFetchError)

    private fun decodeClip(value: JSONObject) = ClipEntity(
        id = value.strictLong("id") ?: throw JSONException("id"),
        xPostId = value.strictString("xPostId") ?: throw JSONException("xPostId"),
        authorId = value.requiredNullableString("authorId"),
        authorName = value.strictString("authorName") ?: throw JSONException("authorName"),
        authorUsername = value.strictString("authorUsername") ?: throw JSONException("authorUsername"),
        text = value.strictString("text") ?: throw JSONException("text"),
        postUrl = value.strictString("postUrl") ?: throw JSONException("postUrl"),
        xCreatedAt = value.strictString("xCreatedAt") ?: throw JSONException("xCreatedAt"),
        savedAt = value.strictString("savedAt") ?: throw JSONException("savedAt"),
        syncedAt = value.strictString("syncedAt") ?: throw JSONException("syncedAt"),
        summary = value.strictString("summary") ?: throw JSONException("summary"),
        ocrText = value.strictString("ocrText") ?: throw JSONException("ocrText"),
        ocrUpdatedAt = value.requiredNullableString("ocrUpdatedAt"),
        likeCount = value.requiredNullableLong("likeCount"),
        likeCountFetchedAt = value.requiredNullableString("likeCountFetchedAt"),
        likeCountFetchFailedAt = value.requiredNullableString("likeCountFetchFailedAt"),
        likeCountFetchError = value.requiredNullableString("likeCountFetchError"),
    )

    private fun encodeAsset(value: AssetEntity) = JSONObject()
        .put("id", value.id).put("clipId", value.clipId).put("mediaKey", value.mediaKey).put("type", value.type)
        .putNullable("remoteUrl", value.remoteUrl).putNullable("previewUrl", value.previewUrl)
        .putNullable("localPath", value.localPath).putNullable("width", value.width).putNullable("height", value.height)
        .putNullable("sizeBytes", value.sizeBytes).put("downloadState", value.downloadState).put("createdAt", value.createdAt)

    private fun decodeAsset(value: JSONObject) = AssetEntity(
        id = value.strictLong("id") ?: throw JSONException("id"),
        clipId = value.strictLong("clipId") ?: throw JSONException("clipId"),
        mediaKey = value.strictString("mediaKey") ?: throw JSONException("mediaKey"),
        type = value.strictString("type") ?: throw JSONException("type"),
        remoteUrl = value.requiredNullableString("remoteUrl"),
        previewUrl = value.requiredNullableString("previewUrl"),
        localPath = value.requiredNullableString("localPath"),
        width = value.requiredNullableInt("width"),
        height = value.requiredNullableInt("height"),
        sizeBytes = value.requiredNullableLong("sizeBytes"),
        downloadState = value.strictString("downloadState") ?: throw JSONException("downloadState"),
        createdAt = value.strictString("createdAt") ?: throw JSONException("createdAt"),
    )

    private fun encodeFile(value: UndoFileMetadata) = JSONObject().put("assetId", value.assetId)
        .put("stagingRelativePath", value.stagingRelativePath).put("sizeBytes", value.sizeBytes).put("sha256", value.sha256)

    private fun decodeFile(value: JSONObject) = UndoFileMetadata(
        assetId = value.strictLong("assetId") ?: throw JSONException("assetId"),
        stagingRelativePath = value.strictString("stagingRelativePath") ?: throw JSONException("stagingRelativePath"),
        sizeBytes = value.strictLong("sizeBytes") ?: throw JSONException("sizeBytes"),
        sha256 = value.strictString("sha256") ?: throw JSONException("sha256"),
    )
}

private fun JSONObject.strictString(key: String): String? = opt(key) as? String
private fun JSONObject.strictObject(key: String): JSONObject? = opt(key) as? JSONObject
private fun JSONObject.strictArray(key: String): JSONArray? = opt(key) as? JSONArray
private fun JSONObject.strictLong(key: String): Long? = (opt(key) as? Number)?.let {
    if (it is Double || it is Float) null else it.toString().toLongOrNull()
}
private fun JSONObject.strictInt(key: String): Int? = strictLong(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
private fun JSONObject.requiredNullableString(key: String): String? {
    if (!has(key)) throw JSONException(key)
    return if (isNull(key)) null else strictString(key) ?: throw JSONException(key)
}
private fun JSONObject.requiredNullableLong(key: String): Long? {
    if (!has(key)) throw JSONException(key)
    return if (isNull(key)) null else strictLong(key) ?: throw JSONException(key)
}
private fun JSONObject.requiredNullableInt(key: String): Int? {
    if (!has(key)) throw JSONException(key)
    return if (isNull(key)) null else strictInt(key) ?: throw JSONException(key)
}
private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

private fun <T> List<T>.toJsonArray(encode: (T) -> JSONObject): JSONArray = JSONArray().also { array ->
    forEach { array.put(encode(it)) }
}

private fun <T> JSONArray.mapObjects(decode: (JSONObject) -> T): List<T> = buildList(length()) {
    for (index in 0 until length()) add(decode(opt(index) as? JSONObject ?: throw JSONException("array[$index]")))
}
