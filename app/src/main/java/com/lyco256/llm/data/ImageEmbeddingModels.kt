package com.lyco256.llm.data

data class ImageEmbeddingDocument(
    val assetId: Long,
    val clipId: Long,
    val sourceFingerprint: String,
    val embedding: FloatArray,
)
