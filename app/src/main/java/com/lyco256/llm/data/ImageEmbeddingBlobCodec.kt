package com.lyco256.llm.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageEmbeddingBlobCodec {
    const val DIMENSION = JapaneseClipModelSpec.EMBEDDING_DIMENSION
    const val BLOB_SIZE_BYTES = DIMENSION * Float.SIZE_BYTES

    fun encode(embedding: MultimodalEmbedding): ByteArray = encode(embedding.toFloatArray())

    fun encode(values: FloatArray): ByteArray {
        require(values.size == DIMENSION) { "Image embedding must have $DIMENSION values" }
        require(values.all(Float::isFinite)) { "Image embedding contains non-finite values" }
        return ByteBuffer.allocate(BLOB_SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach(::putFloat) }
            .array()
    }

    fun decode(blob: ByteArray): FloatArray {
        require(blob.size == BLOB_SIZE_BYTES) {
            "Image embedding BLOB must be exactly $BLOB_SIZE_BYTES bytes"
        }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(DIMENSION) { buffer.getFloat() }.also { values ->
            require(values.all(Float::isFinite)) { "Image embedding BLOB contains non-finite values" }
        }
    }
}
