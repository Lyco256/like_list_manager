package com.lyco256.llm.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class ImageFileSignature(
    val length: Long,
    val lastModified: Long,
)

object ImageEmbeddingFingerprint {
    const val PREPROCESS_REVISION = "japanese-clip-image-preprocess-v1-224-black-center-pad-rgb"
    const val SYNC_REVISION = "image-embedding-sync-v1"

    fun sourceSignature(file: File): ImageFileSignature? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L) return null
        return ImageFileSignature(length = length, lastModified = file.lastModified())
    }

    fun calculate(asset: AssetEntity, signature: ImageFileSignature): String {
        val digest = MessageDigest.getInstance("SHA-256")
        addField(digest, "assetId", asset.id.toString())
        addField(digest, "clipId", asset.clipId.toString())
        addField(digest, "mediaKey", asset.mediaKey)
        addField(digest, "type", asset.type)
        addField(digest, "declaredSizeBytes", asset.sizeBytes?.toString() ?: "<null>")
        addField(digest, "fileLength", signature.length.toString())
        addField(digest, "fileLastModified", signature.lastModified.toString())
        addField(digest, "modelRevision", JapaneseClipModelSpec.REVISION)
        addField(digest, "preprocessRevision", PREPROCESS_REVISION)
        addField(digest, "syncRevision", SYNC_REVISION)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun addField(digest: MessageDigest, name: String, value: String) {
        addLengthPrefixed(digest, name.encodeToByteArray())
        addLengthPrefixed(digest, value.encodeToByteArray())
    }

    private fun addLengthPrefixed(digest: MessageDigest, bytes: ByteArray) {
        digest.update(
            ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(bytes.size)
                .array(),
        )
        digest.update(bytes)
    }
}
