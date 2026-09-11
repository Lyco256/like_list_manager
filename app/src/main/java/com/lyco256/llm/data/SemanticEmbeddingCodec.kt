package com.lyco256.llm.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

object SemanticEmbeddingBlobCodec {
    const val DIMENSION = EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION
    const val BLOB_SIZE_BYTES = DIMENSION * Float.SIZE_BYTES

    fun encode(embedding: TextEmbedding): ByteArray = encode(embedding.toFloatArray())

    fun encode(values: FloatArray): ByteArray {
        require(values.size == DIMENSION) { "Semantic embedding must have $DIMENSION values" }
        require(values.all(Float::isFinite)) { "Semantic embedding contains non-finite values" }
        return ByteBuffer.allocate(BLOB_SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach(::putFloat) }
            .array()
    }

    fun decode(blob: ByteArray): FloatArray {
        require(blob.size == BLOB_SIZE_BYTES) {
            "Semantic embedding BLOB must be exactly $BLOB_SIZE_BYTES bytes"
        }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(DIMENSION) { buffer.getFloat() }.also { values ->
            require(values.all(Float::isFinite)) { "Semantic embedding BLOB contains non-finite values" }
        }
    }
}
